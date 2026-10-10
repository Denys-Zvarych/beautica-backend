package com.beautica.ci;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs the real CI shell scripts against throwaway trees: output symlink hardening and the template guard. */
class CiScriptHardeningTest {

    private static final Path REPO = Path.of("").toAbsolutePath();
    private static final Path SELECT = REPO.resolve("scripts/ci/select-tests.sh");
    private static final Path FORBID = REPO.resolve("scripts/forbid_th_utext_in_email.sh");

    @TempDir
    Path tmp;

    private record Result(int exit, String output) {}

    private Result run(Path cwd, Map<String, String> env, List<String> envUnset, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        envUnset.forEach(k -> pb.environment().remove(k));
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("timeout: " + out);
        }
        return new Result(p.exitValue(), out);
    }

    private Path gitRepo() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(repo.resolve("src/main/java/com/beautica"));
        Files.writeString(repo.resolve("src/main/java/com/beautica/A.java"), "package com.beautica; class A {}\n");
        git(repo, "init", "-q");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "base");
        return repo;
    }

    private void git(Path repo, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        assertThat(run(repo, Map.of(), List.of(), cmd.toArray(String[]::new)).exit()).isZero();
    }

    private Map<String, String> ciEnv(Path repo, Path outDir) {
        return Map.of("GITHUB_ACTIONS", "true", "CI_OUT_DIR", outDir.toString(), "PROJECT_ROOT", repo.toString(),
            "SELECTOR_CLASSES", tmp.resolve("sel-classes").toString());
    }

    @Test
    void should_notWriteThroughSymlink_when_changesPathIsSymlink() throws Exception {
        Path repo = gitRepo();
        Path victim = tmp.resolve("victim.sh");
        Files.writeString(victim, "#!/bin/sh\necho trusted\n");
        Path realOut = Files.createDirectories(tmp.resolve("real-out"));
        Path outLink = tmp.resolve("ci-out");
        Files.createSymbolicLink(outLink, realOut);
        // the old, pre-fix output location, planted as a symlink onto the victim
        Files.createSymbolicLink(tmp.resolve("ci-out.changes.txt"), victim);

        Result r = run(repo, ciEnv(repo, outLink), List.of(), "bash", SELECT.toString(), "--force-full", "x");

        assertThat(r.exit()).isNotZero();
        assertThat(Files.readString(victim)).isEqualTo("#!/bin/sh\necho trusted\n");
        try (var files = Files.list(realOut)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void should_notWriteThroughSymlink_when_oldChangesFileIsSymlinkAndOutDirIsRegular() throws Exception {
        Path repo = gitRepo();
        String head = "HEAD";
        Path victim = tmp.resolve("victim2.sh");
        Files.writeString(victim, "keep\n");
        Path out = tmp.resolve("ci-out");
        Files.createSymbolicLink(tmp.resolve("ci-out.changes.txt"), victim);
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "second");

        Result r = run(repo, new java.util.HashMap<>(ciEnv(repo, out)) {{
            put("BASE_SHA", "HEAD~1");
            put("HEAD_SHA", head);
        }}, List.of(), "bash", SELECT.toString());

        assertThat(r.output()).doesNotContain("Permission denied");
        assertThat(Files.readString(victim)).isEqualTo("keep\n");
        assertThat(Files.exists(out.resolve("changes.txt"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"build/ci.changes.txt", "build/ci/selection.env", "build/ci-selector/X.class", "build/anything"})
    void should_failClosed_when_prCommitsPathUnderBuild(String committed) throws Exception {
        Path repo = gitRepo();
        Path file = repo.resolve(committed);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x\n");
        git(repo, "add", "-f", committed);
        Path out = tmp.resolve("ci-out");

        Result r = run(repo, ciEnv(repo, out), List.of(), "bash", SELECT.toString(), "--force-full", "x");

        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).contains("committed path under build/");
        assertThat(Files.exists(out.resolve("selection.env"))).isFalse();
    }

    @Test
    void should_failClosed_when_buildIsCommittedSymlink() throws Exception {
        Path repo = gitRepo();
        Files.createSymbolicLink(repo.resolve("build"), tmp);
        git(repo, "add", "-f", "build");

        Result r = run(repo, ciEnv(repo, tmp.resolve("ci-out")), List.of(), "bash", SELECT.toString(), "--force-full", "x");

        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).contains("'build' is a symlink");
    }

    @Test
    void should_failClosed_when_gitLsFilesErrors() throws Exception {
        Path repo = gitRepo();
        Path shims = Files.createDirectories(tmp.resolve("shims"));
        Path fakeGit = shims.resolve("git");
        Files.writeString(fakeGit, "#!/bin/sh\nexit 128\n");
        fakeGit.toFile().setExecutable(true);
        Map<String, String> env = new java.util.HashMap<>(ciEnv(repo, tmp.resolve("ci-out")));
        env.put("PATH", shims + java.io.File.pathSeparator + System.getenv("PATH"));

        Result r = run(repo, env, List.of(), "bash", SELECT.toString(), "--force-full", "x");

        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).contains("git ls-files failed");
        assertThat(Files.exists(tmp.resolve("ci-out/selection.env"))).isFalse();
    }

    @Test
    void should_refuseSymlinkedOutDir_when_runLocally() throws Exception {
        Path repo = gitRepo();
        Path realOut = Files.createDirectories(tmp.resolve("real-out"));
        Path outLink = tmp.resolve("ci-out-link");
        Files.createSymbolicLink(outLink, realOut);

        Result r = run(repo, Map.of("CI_OUT_DIR", outLink.toString(), "PROJECT_ROOT", repo.toString(),
            "SELECTOR_CLASSES", tmp.resolve("sel-classes").toString()), List.of("GITHUB_ACTIONS"),
            "bash", SELECT.toString(), "--force-full", "x");

        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).contains("refusing symlinked out dir");
        try (var files = Files.list(realOut)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void should_exitOne_when_inCiWithoutOutDirOrRunnerTemp() throws Exception {
        Path repo = gitRepo();

        Result r = run(repo, Map.of("GITHUB_ACTIONS", "true", "PROJECT_ROOT", repo.toString(),
            "SELECTOR_CLASSES", tmp.resolve("sel-classes").toString()), List.of("CI_OUT_DIR", "RUNNER_TEMP"),
            "bash", SELECT.toString(), "--force-full", "x");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.output()).contains("CI_OUT_DIR (or RUNNER_TEMP) is required in CI");
    }

    @Test
    void should_failClosed_when_ciOutDirIsInsideTheTree() throws Exception {
        Path repo = gitRepo();

        Result r = run(repo, ciEnv(repo, repo.resolve("build/ci")), List.of(), "bash", SELECT.toString(), "--force-full", "x");

        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).contains("outside the PR tree");
    }

    private Path forbidTree() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("forbid"));
        Files.createDirectories(root.resolve("scripts"));
        Files.copy(FORBID, root.resolve("scripts/forbid_th_utext_in_email.sh"));
        Files.createDirectories(root.resolve("src/main/resources/templates/email"));
        return root;
    }

    @Test
    void should_failForbidGuard_when_templateIsSymlinkWithThUtext() throws Exception {
        Path root = forbidTree();
        Path outside = tmp.resolve("evil.html");
        Files.writeString(outside, "<p th:utext=\"${note}\"></p>\n");
        Files.createSymbolicLink(root.resolve("src/main/resources/templates/email/evil.html"), outside);

        Result r = run(root, Map.of(), List.of(), "bash", root.resolve("scripts/forbid_th_utext_in_email.sh").toString());

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.output()).contains("symlinks are forbidden");
    }

    @Test
    void should_failForbidGuard_when_thUtextInPlainTemplate() throws Exception {
        Path root = forbidTree();
        Files.writeString(root.resolve("src/main/resources/templates/email/a.html"), "<p th:utext=\"${x}\"></p>\n");

        Result r = run(root, Map.of(), List.of(), "bash", root.resolve("scripts/forbid_th_utext_in_email.sh").toString());

        assertThat(r.exit()).isEqualTo(1);
    }

    @Test
    void should_passForbidGuard_when_templatesAreCleanRegularFiles() throws Exception {
        Path root = forbidTree();
        Files.writeString(root.resolve("src/main/resources/templates/email/a.html"), "<p th:text=\"${x}\"></p>\n");

        Result r = run(root, Map.of(), List.of(), "bash", root.resolve("scripts/forbid_th_utext_in_email.sh").toString());

        assertThat(r.exit()).isZero();
    }
}
