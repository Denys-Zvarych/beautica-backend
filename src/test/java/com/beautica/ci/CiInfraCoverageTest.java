package com.beautica.ci;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps three hand-maintained lists in step: {@link SelectorRules#ALWAYS_FULL_GLOB_SOURCES}, the
 * CODEOWNERS ownership of those paths, and the plain-shell "CI infrastructure changed" regex in
 * pr-validate.yml (which forces a full run + the selector self-test and cannot be switched off by
 * editing the selector). A test-affecting path owned by nobody, or missing from the shell guard, would
 * let a PR neuter the suite without review or a full run.
 */
class CiInfraCoverageTest {

    private static final Path CODEOWNERS = Path.of(".github/CODEOWNERS");
    private static final Path WORKFLOW = Path.of(".github/workflows/pr-validate.yml");

    /**
     * SHA-256 of the raw bytes of .github/workflows/promotion-source.yml. Update in the SAME PR as any edit to
     * that file (`sha256sum .github/workflows/promotion-source.yml`).
     */
    static final String PROMOTION_SOURCE_SHA256 = "6919ea775a6bbc7b23b72559fbd859e8712faadb26376c91dac46f9d8a614889";

    /** Application source: always-full for the selector, but it cannot change how tests execute. */
    private static boolean isMainSource(String glob) {
        return glob.startsWith("src/main/");
    }

    /** Concrete sample paths for a glob: {@code **}/ both as zero and as one directory. */
    private static List<String> samples(String glob) {
        List<String> out = new ArrayList<>();
        out.add(glob.replace("**/", "").replace("**", "x").replace("*", "x"));
        out.add(glob.replace("**/", "x/").replace("**", "x").replace("*", "x"));
        return out;
    }

    private static List<String> testAffectingGlobs() {
        return java.util.Arrays.stream(SelectorRules.ALWAYS_FULL_GLOB_SOURCES)
            .filter(g -> !isMainSource(g))
            .toList();
    }

    /** gitignore-style CODEOWNERS pattern (all of ours are anchored with a leading slash). */
    private static Pattern ownerPattern(String pattern) {
        String p = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        boolean dir = p.endsWith("/");
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*' && p.startsWith("**/", i)) {
                re.append("(?:.*/)?");
                i += 2;
            } else if (c == '*' && p.startsWith("**", i)) {
                re.append(".*");
                i += 1;
            } else if (c == '*') {
                re.append("[^/]*");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        re.append(dir ? ".*" : "(?:/.*)?");
        return Pattern.compile(re.toString());
    }

    private static List<Pattern> codeownersPatterns() throws IOException {
        List<Pattern> out = new ArrayList<>();
        for (String line : Files.readAllLines(CODEOWNERS)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\\s+");
            assertThat(parts).as("CODEOWNERS line needs an owner: %s", t).hasSizeGreaterThanOrEqualTo(2);
            out.add(ownerPattern(parts[0]));
        }
        return out;
    }

    private static Pattern shellInfraRegex() throws IOException {
        Matcher m = Pattern.compile("(?m)^\\s*infra_re='([^']+)'").matcher(Files.readString(WORKFLOW));
        assertThat(m.find()).as("infra_re='...' assignment in pr-validate.yml").isTrue();
        return Pattern.compile(m.group(1));
    }

    @Test
    @DisplayName("should own every test affecting always full glob when codeowners read")
    void should_ownEveryTestAffectingAlwaysFullGlob_when_codeownersRead() throws IOException {
        List<Pattern> owners = codeownersPatterns();

        List<String> uncovered = new ArrayList<>();
        for (String glob : testAffectingGlobs()) {
            for (String sample : samples(glob)) {
                if (owners.stream().noneMatch(p -> p.matcher(sample).matches())) {
                    uncovered.add(glob + " (e.g. " + sample + ")");
                }
            }
        }

        assertThat(uncovered).as("ALWAYS_FULL globs with no CODEOWNERS entry").isEmpty();
    }

    @Test
    @DisplayName("should own gitleaks config when codeowners read")
    void should_ownGitleaksConfig_when_codeownersRead() throws IOException {
        List<Pattern> owners = codeownersPatterns();

        for (String file : List.of(".gitleaks.toml", ".gitleaksignore")) {
            assertThat(owners.stream().anyMatch(p -> p.matcher(file).matches()))
                .as("CODEOWNERS entry for %s (gitleaks is a required check)", file).isTrue();
        }
    }

    @Test
    @DisplayName("should match every test affecting always full glob when shell infra guard applied")
    void should_matchEveryTestAffectingAlwaysFullGlob_when_shellInfraGuardApplied() throws IOException {
        Pattern infra = shellInfraRegex();

        List<String> unguarded = new ArrayList<>();
        for (String glob : testAffectingGlobs()) {
            for (String sample : samples(glob)) {
                if (!infra.matcher(sample).find()) {
                    unguarded.add(glob + " (e.g. " + sample + ")");
                }
            }
        }

        assertThat(unguarded).as("ALWAYS_FULL globs the pr-validate.yml shell guard misses").isEmpty();
    }

    @Test
    @DisplayName("should force ci changed when gradle wrapper properties edited")
    void should_forceCiChanged_when_gradleWrapperPropertiesEdited() throws IOException {
        Pattern infra = shellInfraRegex();

        assertThat(infra.matcher("gradle/wrapper/gradle-wrapper.properties").find()).as("shell infra regex must match wrapper properties").isTrue();
        assertThat(infra.matcher("gradle.properties").find()).as("shell infra regex must match gradle.properties").isTrue();
        assertThat(infra.matcher("settings.gradle.kts").find()).as("shell infra regex must match settings.gradle.kts").isTrue();
        assertThat(infra.matcher("gradlew").find()).as("shell infra regex must match gradlew").isTrue();
        assertThat(infra.matcher("src/test/resources/application-test.yml").find()).as("shell infra regex must match test application config").isTrue();
        assertThat(infra.matcher("src/test/java/com/beautica/booking/AbstractIntegrationTest.java").find()).as("shell infra regex must match abstract integration test base").isTrue();
        assertThat(infra.matcher("src/test/java/com/beautica/support/Fixtures.java").find()).as("shell infra regex must match shared test support fixtures").isTrue();
        assertThat(infra.matcher("Dockerfile").find()).as("shell infra regex must match Dockerfile").isTrue();
    }

    @Test
    @DisplayName("should not force ci changed when ordinary main or test source edited")
    void should_notForceCiChanged_when_ordinaryMainOrTestSourceEdited() throws IOException {
        Pattern infra = shellInfraRegex();

        assertThat(infra.matcher("src/main/java/com/beautica/booking/BookingService.java").find()).isFalse();
        assertThat(infra.matcher("src/test/java/com/beautica/booking/BookingServiceTest.java").find()).isFalse();
        assertThat(infra.matcher("docs/backend-phases/backlog.md").find()).isFalse();
    }

    /** The destructive-migration gate's opt-out markers are author statements; both SQL and Java migrations need the owner. */
    @Test
    @DisplayName("should own sql and java migrations when codeowners read")
    void should_ownSqlAndJavaMigrations_when_codeownersRead() throws IOException {
        List<Pattern> owners = codeownersPatterns();

        for (String file : List.of(
                "src/main/resources/db/migration/V999__x.sql",
                "src/main/resources/db/migration/sub/V999__x.sql",
                "src/main/java/db/migration/V999__X.java",
                "src/main/java/db/migration/sub/V999__X.java")) {
            assertThat(owners.stream().anyMatch(p -> p.matcher(file).matches()))
                .as("CODEOWNERS must own migration %s", file).isTrue();
        }
    }

    /** The th:utext guard script is run from the base tree by `plan`; editing it must force full + self-test and need the owner. */
    @Test
    @DisplayName("should guard forbid utext script when infra regex and codeowners read")
    void should_guardForbidUtextScript_when_infraRegexAndCodeownersRead() throws IOException {
        String script = "scripts/forbid_th_utext_in_email.sh";

        assertThat(shellInfraRegex().matcher(script).find())
            .as("infra_re must force a full run when %s is edited", script).isTrue();
        assertThat(codeownersPatterns().stream().anyMatch(p -> p.matcher(script).matches()))
            .as("CODEOWNERS must own %s", script).isTrue();
    }

    @Test
    @DisplayName("should run forbid utext guard only from trusted copy when workflow read")
    void should_runForbidUtextGuardOnlyFromTrustedCopy_when_workflowRead() throws IOException {
        String wf = Files.readString(WORKFLOW);

        assertThat(wf).as("script must be archived from the base into the trusted dir")
            .contains("extract+=(scripts/forbid_th_utext_in_email.sh)");
        assertThat(wf).as("PR-tree invocation in plan is forbidden")
            .doesNotContain("run: ./scripts/forbid_th_utext_in_email.sh");
        assertThat(wf.indexOf("id: select")).isPositive()
            .isLessThan(wf.indexOf("Forbid th:utext in email templates (trusted copy)"));
    }

    @Test
    @DisplayName("should cover sample paths when sampled against always full globs")
    void should_coverSamplePaths_when_sampledAgainstAlwaysFullGlobs() {
        for (String glob : testAffectingGlobs()) {
            for (String sample : samples(glob)) {
                assertThat(SelectorRules.matchesAny(SelectorRules.ALWAYS_FULL_GLOBS, sample))
                    .as("sample %s for glob %s must itself be ALWAYS_FULL", sample, glob)
                    .isTrue();
            }
        }
    }

    /**
     * `pull_request_target` runs with the base branch's workflow and (by default) a privileged token, so it is
     * confined to exactly one workflow, the promotion-source guard, pinned by SHA-256 and structurally minimal.
     * The checker lives in {@link PullRequestTargetGuardChecker}; its mutation cases are in
     * {@link PullRequestTargetGuardCases}. The pin is always enforced here.
     */
    @Test
    @DisplayName("should confine pull request target to promotion guard when workflows read")
    void should_confinePullRequestTargetToPromotionGuard_when_workflowsRead() {
        List<String> violations = PullRequestTargetGuardChecker.check(Path.of(".github/workflows"), true);

        assertThat(violations).as("pull_request_target confinement violations").isEmpty();
    }

    /** Every checkout step in EVERY workflow must drop the GITHUB_TOKEN from .git/config. */
    @Test
    @DisplayName("should not persist credentials when any checkout step")
    void should_notPersistCredentials_when_anyCheckoutStep() throws IOException {
        int total = 0;
        List<Path> workflows;
        try (var stream = Files.list(Path.of(".github/workflows"))) {
            workflows = stream.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList();
        }
        assertThat(workflows).as("workflow files scanned").hasSizeGreaterThanOrEqualTo(4);
        for (Path workflow : workflows) {
            List<String> lines = Files.readAllLines(workflow);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.contains("uses: actions/checkout@")) {
                    continue;
                }
                total++;
                int indent = line.length() - line.stripLeading().length();
                boolean persistOff = false;
                for (int j = i + 1; j < lines.size(); j++) {
                    String next = lines.get(j);
                    if (next.isBlank()) {
                        continue;
                    }
                    int nextIndent = next.length() - next.stripLeading().length();
                    if (nextIndent < indent) {
                        break;
                    }
                    if (nextIndent == indent && !next.stripLeading().startsWith("with:")) {
                        break;
                    }
                    if (next.strip().equals("persist-credentials: false")) {
                        persistOff = true;
                    }
                }
                assertThat(persistOff).as("%s:%d checkout must set persist-credentials: false", workflow, i + 1).isTrue();
            }
        }
        assertThat(total).as("checkout steps found (guards against a vacuous scan)").isGreaterThanOrEqualTo(9);
    }

    // ---- pull_request_target guard: mutation cases (port of the mobile guard's --self-test) -------------------

    private static final Path PROMOTION_SOURCE = Path.of(".github/workflows/promotion-source.yml");

    static java.util.stream.Stream<PullRequestTargetGuardCases.Case> guardCases() {
        return PullRequestTargetGuardCases.negativeCaseStream();
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        return Files.write(dir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("should reject workflows when guard mutation applied")
    @MethodSource("guardCases")
    void should_rejectWorkflows_when_guardMutationApplied(PullRequestTargetGuardCases.Case c, @TempDir Path dir)
        throws IOException {
        String guard = c.guard().apply(Files.readString(PROMOTION_SOURCE, StandardCharsets.UTF_8));
        if (!c.name().equals("missing")) {
            write(dir, "promotion-source.yml", guard);
        }
        for (var f : c.files().entrySet()) {
            write(dir, f.getKey(), f.getValue());
        }

        List<String> violations = PullRequestTargetGuardChecker.check(dir, c.pinned());

        assertThat(violations).as("case %s must be rejected", c.name())
            .anyMatch(v -> v.contains(c.needle()));
    }

    @Test
    @DisplayName("should accept workflows when clean copy checked")
    void should_acceptWorkflows_when_cleanCopyChecked(@TempDir Path dir) throws IOException {
        Files.copy(PROMOTION_SOURCE, dir.resolve("promotion-source.yml"));
        write(dir, "other.yml", PullRequestTargetGuardCases.DEFAULT_OTHER);

        assertThat(PullRequestTargetGuardChecker.check(dir, true)).as("pinned").isEmpty();
        assertThat(PullRequestTargetGuardChecker.check(dir, false)).as("structural only").isEmpty();
    }

    @Test
    @DisplayName("should accept workflows when other file only mentions target in comment")
    void should_acceptWorkflows_when_otherFileOnlyMentionsTargetInComment(@TempDir Path dir) throws IOException {
        Files.copy(PROMOTION_SOURCE, dir.resolve("promotion-source.yml"));
        write(dir, "other.yml", PullRequestTargetGuardCases.DEFAULT_OTHER + "# pull_request_target in a comment\n");

        assertThat(PullRequestTargetGuardChecker.check(dir, false)).isEmpty();
    }

    @Test
    @DisplayName("should accept workflows when other file uses benign list trigger")
    void should_acceptWorkflows_when_otherFileUsesBenignListTrigger(@TempDir Path dir) throws IOException {
        Files.copy(PROMOTION_SOURCE, dir.resolve("promotion-source.yml"));
        write(dir, "other.yml", "on: [push, pull_request]\n");

        assertThat(PullRequestTargetGuardChecker.check(dir, false)).isEmpty();
    }

    @Test
    @DisplayName("should ignore non workflow files when extension is not yaml")
    void should_ignoreNonWorkflowFiles_when_extensionIsNotYaml(@TempDir Path dir) throws IOException {
        Files.copy(PROMOTION_SOURCE, dir.resolve("promotion-source.yml"));
        write(dir, "notes.txt", "on: pull_request_target\n");
        write(dir, "other.yml.bak", "on: pull_request_target\n");
        Files.createDirectory(dir.resolve("sub"));
        write(dir.resolve("sub"), "nested.yml", "on: pull_request_target\n");

        assertThat(PullRequestTargetGuardChecker.check(dir, false)).isEmpty();
    }

    @Test
    @DisplayName("should fail pin when guard changed")
    void should_failPin_when_guardChanged(@TempDir Path dir) throws IOException {
        write(dir, "promotion-source.yml", Files.readString(PROMOTION_SOURCE, StandardCharsets.UTF_8).replace("timeout-minutes: 5", "timeout-minutes: 6"));

        assertThat(PullRequestTargetGuardChecker.check(dir, true)).anyMatch(v -> v.contains("update the pinned hash"));
    }

    /**
     * The SHA-256 pin covers raw bytes, so git must never rewrite the guard's line endings (autocrlf, a later
     * {@code * text=auto}). Dropping the {@code -text} attribute would make the pin checkout-dependent.
     */
    @Test
    @org.junit.jupiter.api.DisplayName("promotion-source.yml is pinned -text in .gitattributes and committed with LF-only bytes")
    void should_pinGuardBytes_when_gitattributesAndGuardRead() throws IOException {
        List<String> attributes = Files.readAllLines(Path.of(".gitattributes")).stream()
            .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        byte[] guard = Files.readAllBytes(PROMOTION_SOURCE);

        assertThat(attributes).as(".gitattributes must keep git from normalizing the pinned guard")
            .anyMatch(l -> l.matches("/\\.github/workflows/promotion-source\\.yml\\s+(-text|binary)(\\s.*)?"));
        assertThat(new String(guard, StandardCharsets.UTF_8)).as("guard bytes must contain no CR")
            .doesNotContain("\r");
    }

    @Test
    @DisplayName("should split raw lines when unicode line breaks present")
    void should_splitRawLines_when_unicodeLineBreaksPresent() {
        assertThat(PullRequestTargetGuardChecker.splitRawLines("a\r\nb\nc\rd\u0085e\u2028f\u2029g\n"))
            .containsExactly("a", "b", "c", "d", "e", "f", "g");
    }

    @Test
    @DisplayName("should reject symlinked other workflow when other yml is link")
    void should_rejectSymlinkedOtherWorkflow_when_otherYmlIsLink(@TempDir Path dir) throws IOException {
        Files.copy(PROMOTION_SOURCE, dir.resolve("promotion-source.yml"));
        write(dir, "real.txt", "on: push\n");
        symlinkOrSkip(dir.resolve("other.yml"), Path.of("real.txt"));

        assertThat(PullRequestTargetGuardChecker.check(dir, false)).anyMatch(v -> v.contains("other.yml: is a symlink"));
    }

    @Test
    @DisplayName("should reject symlinked guard when promotion source is link")
    void should_rejectSymlinkedGuard_when_promotionSourceIsLink(@TempDir Path dir) throws IOException {
        Files.copy(PROMOTION_SOURCE, dir.resolve("real.txt"));
        write(dir, "other.yml", PullRequestTargetGuardCases.DEFAULT_OTHER);
        symlinkOrSkip(dir.resolve("promotion-source.yml"), Path.of("real.txt"));

        assertThat(PullRequestTargetGuardChecker.check(dir, false)).anyMatch(v -> v.contains("promotion-source.yml: is a symlink"));
    }

    private static void symlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlinks unsupported here");
        }
    }
}
