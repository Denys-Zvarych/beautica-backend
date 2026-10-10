package com.beautica.ci;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

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
    void should_ownGitleaksConfig_when_codeownersRead() throws IOException {
        List<Pattern> owners = codeownersPatterns();

        for (String file : List.of(".gitleaks.toml", ".gitleaksignore")) {
            assertThat(owners.stream().anyMatch(p -> p.matcher(file).matches()))
                .as("CODEOWNERS entry for %s (gitleaks is a required check)", file).isTrue();
        }
    }

    @Test
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
    void should_forceCiChanged_when_gradleWrapperPropertiesEdited() throws IOException {
        Pattern infra = shellInfraRegex();

        assertThat(infra.matcher("gradle/wrapper/gradle-wrapper.properties").find()).isTrue();
        assertThat(infra.matcher("gradle.properties").find()).isTrue();
        assertThat(infra.matcher("settings.gradle.kts").find()).isTrue();
        assertThat(infra.matcher("gradlew").find()).isTrue();
        assertThat(infra.matcher("src/test/resources/application-test.yml").find()).isTrue();
        assertThat(infra.matcher("src/test/java/com/beautica/booking/AbstractIntegrationTest.java").find()).isTrue();
        assertThat(infra.matcher("src/test/java/com/beautica/support/Fixtures.java").find()).isTrue();
        assertThat(infra.matcher("Dockerfile").find()).isTrue();
    }

    @Test
    void should_notForceCiChanged_when_ordinaryMainOrTestSourceEdited() throws IOException {
        Pattern infra = shellInfraRegex();

        assertThat(infra.matcher("src/main/java/com/beautica/booking/BookingService.java").find()).isFalse();
        assertThat(infra.matcher("src/test/java/com/beautica/booking/BookingServiceTest.java").find()).isFalse();
        assertThat(infra.matcher("docs/backend-phases/backlog.md").find()).isFalse();
    }

    /** The th:utext guard script is run from the base tree by `plan`; editing it must force full + self-test and need the owner. */
    @Test
    void should_guardForbidUtextScript_when_infraRegexAndCodeownersRead() throws IOException {
        String script = "scripts/forbid_th_utext_in_email.sh";

        assertThat(shellInfraRegex().matcher(script).find())
            .as("infra_re must force a full run when %s is edited", script).isTrue();
        assertThat(codeownersPatterns().stream().anyMatch(p -> p.matcher(script).matches()))
            .as("CODEOWNERS must own %s", script).isTrue();
    }

    @Test
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
    void should_coverSamplePaths_when_sampledAgainstAlwaysFullGlobs() {
        for (String glob : testAffectingGlobs()) {
            for (String sample : samples(glob)) {
                assertThat(SelectorRules.matchesAny(SelectorRules.ALWAYS_FULL_GLOBS, sample))
                    .as("sample %s for glob %s must itself be ALWAYS_FULL", sample, glob)
                    .isTrue();
            }
        }
    }

    /** Every checkout step in EVERY workflow must drop the GITHUB_TOKEN from .git/config. */
    @Test
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
}
