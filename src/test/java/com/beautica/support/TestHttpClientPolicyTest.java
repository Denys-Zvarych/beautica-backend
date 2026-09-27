package com.beautica.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture guard: EVERY integration test drives its HTTP calls through the timeout-bounded,
 * zero-retry Apache HC5 factory built by {@link TestHttpClients}, and nothing else.
 *
 * <p><b>Why an architecture test and not a behavioural one.</b> The defect this closes was never
 * one wrong line — it was the same wrong line, {@code HttpClients.createDefault()}, independently
 * copied into 96 test classes. A default HC5 client has an INFINITE response timeout and DOES
 * retry, so a rate-limit 429 whose socket the server resets hung the whole suite for 27 minutes
 * instead of failing one case. Those copies sat in each subclass's own {@code @BeforeEach}, which
 * JUnit runs AFTER the superclass's, so every one of them silently threw the policy away. The
 * consolidation onto {@link com.beautica.AbstractIntegrationTest#installTimeoutBoundedHttpClient()}
 * removed all 96, and that class's javadoc claims the drift is now "unrepresentable" — but nothing
 * enforced it. Convention is not enforcement: the 97th copy would have been just as invisible as
 * the first 96, and just as green until CI hung.
 *
 * <p>Source-level rather than reflective, for the same reason
 * {@link com.beautica.common.cache.CacheKeyScanOwnershipTest} is: the thing being banned is a code
 * shape appearing in a class nobody remembered to write a test for. A behavioural assertion can
 * only interrogate classes it already knows about — which is precisely the set that would never
 * have drifted.
 *
 * <p>Runs as a plain unit test. It loads no Spring context and opens no socket.
 */
@DisplayName("Architecture — every integration test uses the timeout-bounded HTTP client")
class TestHttpClientPolicyTest {

    private static final Path TEST_SOURCE_ROOT = Paths.get("src/test/java");

    /** The one class allowed to build a request factory, and the base class that installs it. */
    private static final String FACTORY_OWNER = "TestHttpClients.java";
    private static final String BASE_CLASS_FILE = "AbstractIntegrationTest.java";
    private static final String BASE_CLASS_SIMPLE_NAME = "AbstractIntegrationTest";

    /**
     * This guard itself. It names every banned shape as a string literal, so it would otherwise be
     * its own first offender — the one exclusion, and it must stay a single named constant rather
     * than a pattern, so no other file can ever slip under it.
     */
    private static final String SELF = "TestHttpClientPolicyTest.java";

    /**
     * The complete ledger of test classes that hold a {@code TestRestTemplate} but CANNOT inherit
     * the base class's hook, and therefore call {@link TestHttpClients} themselves.
     *
     * <p>Every entry is a {@code @SpringBootTest} that declares its own context (its own
     * {@code @TestPropertySource} / {@code @MockBean} shape) and so cannot extend
     * {@link com.beautica.AbstractIntegrationTest}. They are all single-test concurrency races, and
     * they are the ONLY classes in the suite running HTTP without the base-class hook — note that
     * means they also run without {@code SlowTestExtension}'s 10 s per-test cap, so the client's
     * own 10 s response timeout is the only thing standing between a wedged socket and a hung
     * suite. That is what makes the exact-count assertion below load-bearing rather than
     * bookkeeping.
     *
     * <p><b>Adding a row here is a decision, not a formality.</b> Prefer extending
     * {@code AbstractIntegrationTest}; only a genuinely incompatible context earns a place on this
     * list, and whatever is added must call {@link TestHttpClients#timeoutBoundedRequestFactory()}
     * in a {@code @BeforeEach} — the test below checks the call, not just the import.
     */
    private static final Set<String> STANDALONE_CALLERS = Set.of(
            "PasswordResetConcurrencyTest.java",
            "VerifyEmailConcurrencyTest.java",
            "BookingConcurrencyTest.java",
            "GuestBookingConcurrencyIT.java",
            "GuestCancelConcurrencyIT.java");

    private static final Pattern CLASS_DECL = Pattern.compile(
            "\\b(?:class|interface)\\s+(\\w+)(?:<[^>]*>)?(?:\\s+extends\\s+(\\w+))?");

    @Test
    @DisplayName("no test source builds a bare HttpClients.createDefault() — the 27-minute-hang "
            + "shape that was copied into 96 classes")
    void should_findNoBareDefaultHttpClient_when_scanningEveryTestSource() {
        List<String> offenders = scan((file, line) ->
                line.contains("HttpClients.createDefault")
                        && !file.equals(FACTORY_OWNER)
                        && !file.equals(SELF));

        assertThat(offenders)
                .as("a default HC5 client has an INFINITE response timeout and retries; build the "
                        + "factory with TestHttpClients.timeoutBoundedRequestFactory() instead, or "
                        + "— better — extend AbstractIntegrationTest and inherit it. Offenders: %s",
                        offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("no test source re-installs its own request factory over the base class's — an "
            + "@BeforeEach override runs AFTER the superclass hook and silently discards it")
    void should_findNoSubclassRequestFactoryInstall_when_scanningEveryTestSource() {
        List<String> offenders = scan((file, line) ->
                line.contains("setRequestFactory")
                        && !file.equals(BASE_CLASS_FILE)
                        && !file.equals(SELF)
                        && !STANDALONE_CALLERS.contains(file));

        assertThat(offenders)
                .as("AbstractIntegrationTest installs the bounded factory before EVERY test; a "
                        + "subclass hook runs afterwards and wins, which is exactly how the policy "
                        + "was lost 96 times. Offenders: %s", offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("every test class holding a TestRestTemplate either inherits the base class hook "
            + "or calls TestHttpClients itself — no class runs HTTP on the framework default")
    void should_coverEveryRestTemplateHolder_when_resolvingTheClassHierarchy() {
        Map<String, String> superclassByClass = readClassHierarchy();

        List<String> uncovered = new ArrayList<>();
        for (SourceFile source : sources()) {
            if (source.fileName().equals(BASE_CLASS_FILE)
                    || source.fileName().equals(FACTORY_OWNER)
                    || source.fileName().equals(SELF)) {
                continue;
            }
            if (!source.text().contains("TestRestTemplate")) continue;
            // Fixture helpers (ServiceTestFixtures, SalonItFixtures, BookingTestFixtures) take a
            // TestRestTemplate as a CONSTRUCTOR parameter — they borrow the one the owning test
            // class was already handed, already configured, and declare no lifecycle of their own.
            // A class with neither @Test nor @SpringBootTest has no @BeforeEach JUnit would call,
            // so it is structurally incapable of installing or discarding a factory; scoping by
            // that rather than by a name list means a new helper needs no edit here, while a new
            // TEST class cannot slip through by being named like one.
            if (!source.text().contains("@Test") && !source.text().contains("@SpringBootTest")) {
                continue;
            }
            boolean inheritsHook = declaredClasses(source.text()).stream()
                    .anyMatch(c -> descendsFromBase(c, superclassByClass));
            // The CALL, never the import: an `import com.beautica.support.TestHttpClients;` left
            // behind after the invocation was deleted would otherwise keep this green — verified,
            // this exact substitution is what a falsification run produced.
            boolean callsHelperItself =
                    source.text().contains("TestHttpClients.timeoutBoundedRequestFactory()");
            if (!inheritsHook && !callsHelperItself) uncovered.add(source.fileName());
        }

        assertThat(uncovered)
                .as("these classes would run against SimpleClientHttpRequestFactory (which cannot "
                        + "even send PATCH) or an unbounded HC5 client: %s", uncovered)
                .isEmpty();
    }

    /**
     * The exact-count half of the ledger above. The previous test proves nobody is UNCOVERED; this
     * one proves the covered-by-hand set has not quietly grown — a new standalone
     * {@code @SpringBootTest} is a context that will not be cached with any other, and one that
     * escapes {@code SlowTestExtension} into the bargain, so it must be a deliberate entry here
     * rather than something that drifts in behind a green build.
     */
    @Test
    @DisplayName("exactly the five ledgered standalone classes call TestHttpClients directly, and "
            + "each really invokes the factory method")
    void should_matchTheLedger_when_listingStandaloneTestHttpClientsCallers() {
        Set<String> actual = new HashSet<>();
        for (SourceFile source : sources()) {
            if (source.fileName().equals(FACTORY_OWNER)
                    || source.fileName().equals(BASE_CLASS_FILE)
                    || source.fileName().equals(SELF)) {
                continue;
            }
            if (source.text().contains("TestHttpClients.timeoutBoundedRequestFactory()")) {
                actual.add(source.fileName());
            }
        }

        assertThat(actual)
                .as("extend AbstractIntegrationTest and inherit the hook unless the context truly "
                        + "forbids it; if it does, add the class to STANDALONE_CALLERS and read "
                        + "that field's javadoc first")
                .containsExactlyInAnyOrderElementsOf(STANDALONE_CALLERS);
    }

    /**
     * Pins the contract that let the {@code @AfterEach} copy of the install be deleted: the factory
     * is allocated PER TEST, never shared. A static singleton would hand a case a connection pool a
     * context-sharing sibling had already closed — the failure the deleted {@code @AfterAll
     * destroy()} hooks existed to paper over.
     */
    @Test
    @DisplayName("timeoutBoundedRequestFactory() hands back a fresh factory and a fresh pool on "
            + "every call — no test inherits a sibling's closed connections")
    void should_returnDistinctFactoryAndClient_when_calledTwice() {
        var first = TestHttpClients.timeoutBoundedRequestFactory();
        var second = TestHttpClients.timeoutBoundedRequestFactory();

        assertThat(first)
                .as("a shared static factory is destroyed by whichever class finishes first")
                .isNotSameAs(second);
        assertThat(first.getHttpClient())
                .as("distinct factories must not wrap the same connection pool either")
                .isNotSameAs(second.getHttpClient());
    }

    // ── scanning helpers ───────────────────────────────────────────────────────

    private record SourceFile(String fileName, String text) {}

    /**
     * Returns {@code <file>:<line-number>} for every line the predicate accepts, skipping comment
     * and javadoc lines — the banned shapes are named in prose in several class javadocs
     * (AbstractIntegrationTest, TestHttpClients, AbstractStaffBookingIT), and that documentation is
     * the point, not a violation.
     */
    private List<String> scan(java.util.function.BiPredicate<String, String> violates) {
        List<String> offenders = new ArrayList<>();
        for (SourceFile source : sources()) {
            String[] lines = source.text().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (violates.test(source.fileName(), trimmed)) {
                    offenders.add(source.fileName() + ":" + (i + 1));
                }
            }
        }
        return offenders;
    }

    private List<SourceFile> sources() {
        try (Stream<Path> paths = Files.walk(TEST_SOURCE_ROOT)) {
            List<SourceFile> out = new ArrayList<>();
            for (Path p : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                out.add(new SourceFile(p.getFileName().toString(), Files.readString(p)));
            }
            assertThat(out)
                    .as("the scan found no sources at all — check the working directory")
                    .isNotEmpty();
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Maps every declared test class to the simple name of its superclass, or {@code null}. */
    private Map<String, String> readClassHierarchy() {
        Map<String, String> byClass = new HashMap<>();
        for (SourceFile source : sources()) {
            Matcher m = CLASS_DECL.matcher(source.text());
            while (m.find()) byClass.put(m.group(1), m.group(2));
        }
        return byClass;
    }

    private List<String> declaredClasses(String text) {
        List<String> names = new ArrayList<>();
        Matcher m = CLASS_DECL.matcher(text);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    /** Walks up the recorded chain; cycle-safe and terminates on an unresolvable superclass. */
    private boolean descendsFromBase(String className, Map<String, String> superclassByClass) {
        Set<String> seen = new HashSet<>();
        String current = className;
        while (current != null && seen.add(current)) {
            if (current.equals(BASE_CLASS_SIMPLE_NAME)) return true;
            current = superclassByClass.get(current);
        }
        return false;
    }
}
