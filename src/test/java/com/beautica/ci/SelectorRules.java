package com.beautica.ci;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The single place to edit the affected-test selector's fail-safe rules (phase 358).
 *
 * <p>Every entry sits on its own line and each array keeps a trailing comma, so a rule can be
 * deleted line-by-line for mutation checking: {@code AffectedTestSelectorTest} pins every entry
 * with a literal case that does not read this class.
 */
final class SelectorRules {

    private SelectorRules() {
    }

    /** Any changed path matching one of these globs forces {@code MODE=full}. */
    static final String[] ALWAYS_FULL_GLOB_SOURCES = {
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradle/**",
        "gradlew*",
        "src/main/resources/**",
        "src/test/resources/**",
        "src/main/java/com/beautica/config/**",
        "src/main/java/com/beautica/common/security/**",
        "src/main/java/com/beautica/common/exception/**",
        "src/main/java/com/beautica/*.java",
        "src/test/java/com/beautica/*.java",
        "src/test/java/com/beautica/**/Abstract*.java",
        "src/test/java/com/beautica/**/support/**",
        "src/test/java/com/beautica/**/*Support.java",
        "src/test/java/com/beautica/**/Test*Config*.java",
        "src/test/java/com/beautica/ci/**",
        "scripts/ci/**",
        ".github/workflows/**",
        "docker/**",
    };

    /** Paths that never need a test run. Checked after {@link #ALWAYS_FULL_GLOBS}. */
    private static final String[] NO_TEST_GLOB_SOURCES = {
        "docs/**",
        "**/*.md",
        ".gitignore",
        ".gitleaks*",
    };

    /** Spring-wired-without-import markers (D4): a changed main file containing one forces full. */
    static final String[] FULL_CONTENT_REGEX_SOURCES = {
        "@Configuration\\b",
        "@ControllerAdvice\\b",
        "@RestControllerAdvice\\b",
        "@Aspect\\b",
        "\\bOncePerRequestFilter\\b",
        "@EnableWebSecurity\\b",
        "@ConfigurationProperties\\b",
        "@EventListener\\b",
        "@TransactionalEventListener\\b",
        "@Scheduled\\b",
        "\\bimplements\\b[^{]*\\bWebMvcConfigurer\\b",
        "\\bimplements\\b[^{]*\\bConverter\\b",
        "\\bimplements\\b[^{]*\\bHandlerMethodArgumentResolver\\b",
    };

    static final List<Pattern> ALWAYS_FULL_GLOBS = compileGlobs(ALWAYS_FULL_GLOB_SOURCES);
    static final List<Pattern> NO_TEST_GLOBS = compileGlobs(NO_TEST_GLOB_SOURCES);
    static final List<Pattern> FULL_CONTENT_REGEXES = compileRegexes(FULL_CONTENT_REGEX_SOURCES);

    /** Shard-balancing weights (see {@code AffectedTestSelector#weightOf}): relative, not seconds. */
    static final int HEAVY_TEST_WEIGHT = 10;
    static final int SLICE_TEST_WEIGHT = 3;
    static final int LIGHT_TEST_WEIGHT = 1;

    /** Fallback: above this share of all test classes the selection is replaced by a full run (D8). */
    static final double FULL_FALLBACK_RATIO = 0.40;

    /**
     * Reverse hops walked from a changed file. The service layer is one large strongly connected
     * component, so an unbounded closure reaches ~85% of all tests for any service change (measured
     * on commit 7ac47870: 561 of 652); two hops keep controller, service, repository and their direct
     * consumers in reach, and the feature rule below covers black-box integration tests.
     */
    static final int MAX_REVERSE_HOPS = 2;

    /**
     * Black-box context tests never import the services they exercise over HTTP, so the graph cannot
     * see them. A changed main file selects every such test of its own feature package.
     */
    static final Pattern CONTEXT_TEST_MARKER = Pattern.compile(
        "@SpringBootTest\\b|@DataJpaTest\\b|@AutoConfigureMockMvc\\b|\\bextends\\s+Abstract\\w+");

    static boolean matchesAny(List<Pattern> patterns, String path) {
        return patterns.stream().anyMatch(p -> p.matcher(path).matches());
    }

    /** {@code **} spans directories (and {@code &#42;&#42;/} may match zero), {@code *} stays in one segment. */
    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doubleStar) {
                    boolean slashFollows = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                    regex.append(slashFollows ? "(?:.*/)?" : ".*");
                    i += slashFollows ? 2 : 1;
                } else {
                    regex.append("[^/]*");
                }
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static List<Pattern> compileGlobs(String[] sources) {
        return java.util.Arrays.stream(sources).map(SelectorRules::glob).toList();
    }

    private static List<Pattern> compileRegexes(String[] sources) {
        return java.util.Arrays.stream(sources).map(s -> Pattern.compile(s, Pattern.DOTALL)).toList();
    }
}
