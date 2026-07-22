package com.beautica.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture guard: exactly ONE production class may scan a Caffeine keyset, and it is
 * {@link MasterCachePrefixEvictor}.
 *
 * <p><b>Why an architecture test and not just a behavioural one.</b> The original defect was not one
 * wrong line — it was the SAME wrong line copy-pasted into five services. Each copy tested
 * {@code k instanceof SimpleKey}, which an explicit-{@code key} {@code @Cacheable} never produces, so
 * all five evictions silently removed nothing. Behavioural tests catch a broken copy only where
 * someone thought to write one; this catches the copy itself, at the moment it appears, everywhere.
 *
 * <p>This is not hypothetical. During the very fix that consolidated those five call sites, one of
 * them — {@code BookingService#doEvictRevenueDashboard} — had its javadoc updated but its BODY left
 * on the old predicate. Every behavioural test still passed, because the one test touching
 * {@code revenue-dashboard} used a {@code ConcurrentMapCache}, where the evictor takes its coarse
 * {@code clear()} fallback and any predicate "works". This test is what makes that class of miss
 * impossible: a keyset scan outside the evictor fails it immediately, regardless of whether anyone
 * remembered to cover that cache.
 *
 * <p>Source-level rather than reflective because the thing being banned is a code shape, not a
 * runtime behaviour — there is no bean to interrogate for "does this method inline a keyset scan".
 */
@DisplayName("Architecture — Caffeine keyset scans live only in MasterCachePrefixEvictor")
class CacheKeyScanOwnershipTest {

    private static final Path MAIN_SOURCE_ROOT = Paths.get("src/main/java");

    /**
     * Classes permitted to contain a keyset scan.
     *
     * <p>{@code MasterCachePrefixEvictor} is the owner: every cache keyed by a SpEL inline list
     * ({@code key = "{#masterId, …}"}) must be evicted through it.
     *
     * <p>{@code ReviewEventListener} is a deliberate, verified exception. {@code reviews-by-master}
     * and {@code reviews-by-salon} are keyed by STRING CONCATENATION, not an inline list —
     * {@code key = "'master:' + #masterId + ':sort:' + #sort + ':page:' + …"} in
     * {@code ReviewService} — so their runtime keys really are {@code String}s and the listener's
     * {@code k instanceof String s && s.startsWith("master:" + id + ":")} predicate is correct.
     * Routing it through the shared evictor would be wrong: that matches on a {@code List}'s first
     * element and would never match a String key — the mirror image of the original bug.
     *
     * <p>The exemption is contingent on that key format. If {@code ReviewService}'s {@code @Cacheable}
     * key ever becomes an inline list, this entry must be removed and the listener migrated to the
     * shared evictor. Do not add entries here without checking the corresponding {@code @Cacheable}
     * key's real runtime type first — that check is the entire point of this test.
     */
    private static final List<String> SCAN_OWNERS = List.of(
            "MasterCachePrefixEvictor.java",
            "ReviewEventListener.java");

    /** Code shapes that indicate a hand-rolled cache-key scan. */
    private static final List<String> SCAN_MARKERS = List.of(
            "asMap().keySet()",
            "getNativeCache()");

    @Test
    @DisplayName("no production class outside the evictor scans a native cache keyset")
    void should_confineKeysetScansToEvictor_when_mainSourcesScanned() throws IOException {
        assertThat(MAIN_SOURCE_ROOT)
                .as("test must run from the module root or it silently scans nothing")
                .exists();

        try (Stream<Path> sources = Files.walk(MAIN_SOURCE_ROOT)) {
            List<String> offenders = sources
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !SCAN_OWNERS.contains(p.getFileName().toString()))
                    .filter(CacheKeyScanOwnershipTest::containsKeysetScan)
                    .map(MAIN_SOURCE_ROOT::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();

            assertThat(offenders)
                    .as("""
                        These classes hand-roll a cache-key scan instead of delegating to \
                        MasterCachePrefixEvictor#evictByKeyPrefixNow. That is how five evictions \
                        ended up silently matching nothing: every copy tested `instanceof \
                        SimpleKey`, which an explicit-`key` @Cacheable never produces. Call the \
                        shared evictor — do not re-copy the predicate.""")
                    .isEmpty();
        }
    }

    /**
     * Ignores comment lines, so the explanatory javadoc the five former call sites now carry (which
     * legitimately mentions {@code getNativeCache} and the old predicate) does not read as a
     * violation. Only executable code counts.
     */
    private static boolean containsKeysetScan(Path javaFile) {
        try {
            return Files.readAllLines(javaFile).stream()
                    .map(String::trim)
                    .filter(line -> !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"))
                    .anyMatch(line -> SCAN_MARKERS.stream().anyMatch(line::contains));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + javaFile, e);
        }
    }
}
