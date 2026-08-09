package com.beautica.search;

import com.beautica.AbstractIntegrationTest;
import com.beautica.search.dto.LocationFilter;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.SalonSearchRequest;
import com.beautica.search.service.SearchCacheNames;
import com.beautica.search.service.SearchService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filter-scoped total memo (perf follow-up) — {@link SearchCacheNames#MASTERS_TOTAL} /
 * {@link SearchCacheNames#SALONS_TOTAL}.
 *
 * <h3>What is being protected</h3>
 * An out-of-range page ({@code offset >= totalElements}) has no row to carry
 * {@code COUNT(*) OVER()}, so {@link SearchService} recovers the true total with a first-page
 * probe (see {@code SearchService#probeTotalForEmptyPage}). Pages {@code >= 5} are
 * unconditionally excluded from the {@code search:*:browse} / {@code search:*:q}
 * {@code @Cacheable} caches ({@code condition = "#pageable.pageNumber < 5"} — a deliberate
 * cache-pollution defence, NOT relaxed here), so every such page used to re-run the probe on
 * every hit. The total memo fixes that: it remembers {@code totalElements} per filter tuple
 * (page/size excluded from the key) so a repeat out-of-range hit against the SAME filter
 * short-circuits to an empty page with ZERO native-query statements.
 *
 * <h3>Architect decisions this suite pins (do not "fix" these)</h3>
 * <ul>
 *   <li>Keyset/cursor pagination is REJECTED as the general fix (mixed-direction, nullable
 *       leading sort keys disqualify it) — recorded in the backlog, not revisited here.</li>
 *   <li>The {@code pageNumber < 5} anti-pollution condition on the browse/query caches stays —
 *       {@link #should_remainUncached_when_masterPageIsFive()} is the lock-in test for that.</li>
 *   <li>The memo is used ONLY to prove {@code offset >= total} (an explicit empty page), never
 *       to serve or shape actual result rows — a stale memo can only produce a transiently-empty
 *       tail page, which self-heals within the cache's TTL, never wrong data.</li>
 * </ul>
 *
 * <p>Invokes the autowired (proxied) {@link SearchService} bean directly rather than through
 * the HTTP controller, mirroring {@code LocalityDiscoveryPerfHardeningTest} — this lets each
 * test choose an exact {@link PageRequest} without fighting the controller's result-window
 * clamp, while still exercising the real {@code @Cacheable} AOP advice.</p>
 */
@DisplayName("Search — filter-scoped total memo")
class SearchTotalMemoIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SearchService searchService;

    @Autowired
    private EntityManagerFactory emf;

    private Statistics statistics() {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    // ── 1. Statement-count assertion (load-bearing) ───────────────────────────

    @Test
    @DisplayName("an out-of-range master page against a MEMOIZED filter issues ZERO native queries")
    void should_issueZeroQueries_when_masterOutOfRangePageIsMemoized() {
        seedMasters("Київ", 3);
        MasterSearchRequest request = masterRequest("Київ");

        // Cold call: page 5 (>= 5, so the browse @Cacheable condition never applies — the method
        // body always executes) with offset (5*20=100) far past total=3. Runs the data query
        // (empty) + the empty-page probe, and warms the MASTERS_TOTAL memo as a side effect.
        Page<?> cold = searchService.searchMasters(request, PageRequest.of(5, 20));
        assertThat(cold.getTotalElements()).isEqualTo(3L);

        Statistics statistics = statistics();
        statistics.clear();

        Page<?> warm = searchService.searchMasters(request, PageRequest.of(5, 20));

        assertThat(warm.getContent()).isEmpty();
        assertThat(warm.getTotalElements())
                .as("the memoized total must still be served, not silently reset to 0")
                .isEqualTo(3L);
        assertThat(statistics.getQueryExecutionCount())
                .as("a memoized out-of-range page must short-circuit to ZERO native queries")
                .isZero();
        assertThat(statistics.getPrepareStatementCount())
                .as("a memoized out-of-range page must not prepare a single JDBC statement")
                .isZero();
    }

    @Test
    @DisplayName("an out-of-range salon page against a MEMOIZED filter issues ZERO native queries")
    void should_issueZeroQueries_when_salonOutOfRangePageIsMemoized() {
        seedSalons("Харків", 4);
        SalonSearchRequest request = salonRequest("Харків");

        Page<?> cold = searchService.searchSalons(request, PageRequest.of(5, 20));
        assertThat(cold.getTotalElements()).isEqualTo(4L);

        Statistics statistics = statistics();
        statistics.clear();

        Page<?> warm = searchService.searchSalons(request, PageRequest.of(5, 20));

        assertThat(warm.getContent()).isEmpty();
        assertThat(warm.getTotalElements()).isEqualTo(4L);
        assertThat(statistics.getQueryExecutionCount())
                .as("a memoized out-of-range salon page must short-circuit to ZERO native queries")
                .isZero();
    }

    // ── 2. First out-of-range hit still returns the correct, non-zero total ──

    @Test
    @DisplayName("the FIRST out-of-range salon page (cold memo) still returns the true totalElements, not 0")
    void should_returnTrueTotal_when_salonOutOfRangePageIsFirstHit() {
        seedSalons("Львів", 5);
        SalonSearchRequest request = salonRequest("Львів");

        Page<?> result = searchService.searchSalons(request, PageRequest.of(5, 20));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements())
                .as("a cold memo must still run the probe and report the TRUE total")
                .isEqualTo(5L);
    }

    // ── 3. In-range page is never short-circuited ────────────────────────────

    @Test
    @DisplayName("an in-range master page is never short-circuited to empty, even once the memo is warm")
    void should_returnRows_when_masterPageIsInRangeDespiteWarmMemo() {
        seedMasters("Одеса", 7);
        MasterSearchRequest request = masterRequest("Одеса");

        // Warm the memo with an out-of-range page first (total=7).
        Page<?> outOfRange = searchService.searchMasters(request, PageRequest.of(5, 20));
        assertThat(outOfRange.getTotalElements()).isEqualTo(7L);

        // page=0,size=5 -> offset=0 < 7: squarely in range.
        Page<?> inRange = searchService.searchMasters(request, PageRequest.of(0, 5));

        assertThat(inRange.getContent())
                .as("an in-range page must return real rows, never short-circuited by the total memo")
                .hasSize(5);
        assertThat(inRange.getTotalElements()).isEqualTo(7L);

        // page=1,size=3 -> offset=3 < 7: in range AND offset > 0, which is the boundary an
        // over-eager guard (short-circuiting on "offset > 0" instead of "offset >= total")
        // would wrongly treat as out-of-range. The offset=0 assertion above cannot catch that
        // defect shape at all, because such a guard is nested inside the SAME "offset > 0"
        // outer check the read is gated by — an offset=0 probe never even reaches it.
        Page<?> inRangeWithPositiveOffset = searchService.searchMasters(request, PageRequest.of(1, 3));

        assertThat(inRangeWithPositiveOffset.getContent())
                .as("an in-range page with a POSITIVE offset must also return real rows — this is "
                        + "the exact boundary an offset>0-only guard would wrongly short-circuit")
                .hasSize(3);
        assertThat(inRangeWithPositiveOffset.getTotalElements()).isEqualTo(7L);
    }

    // ── 4. Memo key discriminates filter tuples ───────────────────────────────

    @Test
    @DisplayName("two different filter tuples never share a memo entry")
    void should_notShareMemoEntry_when_filterTuplesDiffer() {
        seedMasters("Дніпро", 2);
        seedMasters("Вінниця", 9);

        MasterSearchRequest requestA = masterRequest("Дніпро");
        MasterSearchRequest requestB = masterRequest("Вінниця");

        // Warm A's memo (total=2) at an out-of-range page.
        Page<?> a = searchService.searchMasters(requestA, PageRequest.of(5, 20));
        assertThat(a.getTotalElements()).isEqualTo(2L);

        // B is a DIFFERENT filter tuple (different cityId) — it must NOT read A's memoized
        // total (2); it must run its own probe and report its own true total (9).
        Page<?> b = searchService.searchMasters(requestB, PageRequest.of(5, 20));
        assertThat(b.getTotalElements())
                .as("a different filter tuple must not collide with another tuple's memoized total")
                .isEqualTo(9L);
    }

    // ── 5. Memo key discriminates masters from salons ─────────────────────────

    @Test
    @DisplayName("the master and salon total memos never collide for an identical location filter")
    void should_notCollide_when_masterAndSalonShareLocationFilter() {
        seedMasters("Запоріжжя", 3);
        seedSalons("Запоріжжя", 8);

        MasterSearchRequest masterRequest = masterRequest("Запоріжжя");
        SalonSearchRequest salonRequest = salonRequest("Запоріжжя");

        Page<?> masters = searchService.searchMasters(masterRequest, PageRequest.of(5, 20));
        Page<?> salons = searchService.searchSalons(salonRequest, PageRequest.of(5, 20));

        assertThat(masters.getTotalElements()).isEqualTo(3L);
        assertThat(salons.getTotalElements())
                .as("the salon total must be its own (8), not the master total (3) leaking across "
                        + "the SearchCacheNames.MASTERS_TOTAL / SALONS_TOTAL split")
                .isEqualTo(8L);
    }

    // ── 6. Lock-in: page 5 remains uncached on the browse cache ───────────────

    @Test
    @DisplayName("LOCK-IN — page 5 stays uncached on the browse cache; do not relax pageNumber < 5")
    void should_remainUncached_when_masterPageIsFive() {
        seedMasters("Вінниця", 6);
        MasterSearchRequest request = masterRequest("Вінниця");

        // page=5,size=1 -> offset=5 < total(6): in range, returns the 6th row.
        Page<?> first = searchService.searchMasters(request, PageRequest.of(5, 1));
        assertThat(first.getContent()).hasSize(1);
        assertThat(first.getTotalElements()).isEqualTo(6L);

        // A 7th master lands AFTER the first call. If page 5 were (wrongly) cached by the browse
        // @Cacheable, the second call below would replay the STALE total (6) instead of
        // re-querying — silently "fixing" (i.e. breaking) the pageNumber < 5 anti-pollution
        // decision. The total memo itself does not interfere here: offset(5) < the memoized
        // total(6) from the first call, so the memo's own guard (offset >= total) never fires
        // and the method always falls through to the real query.
        seedMasters("Вінниця", 1);

        Page<?> second = searchService.searchMasters(request, PageRequest.of(5, 1));
        assertThat(second.getTotalElements())
                .as("page >= 5 must re-run the query on every call — it must never be served "
                        + "from search:masters:browse (anti-pollution decision, see SearchCacheNames)")
                .isEqualTo(7L);
    }

    // ── seed + request helpers ────────────────────────────────────────────────

    private MasterSearchRequest masterRequest(String cityName) {
        return new MasterSearchRequest(new LocationFilter(cityIdByName(cityName), null), null, null,
                null, null, null, null, null, null, null);
    }

    private SalonSearchRequest salonRequest(String cityName) {
        return new SalonSearchRequest(new LocationFilter(cityIdByName(cityName), null), null, null,
                null, null, null, null, null, null);
    }

    private UUID cityIdByName(String nameUk) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = ? ORDER BY katotth_code LIMIT 1",
                UUID.class, nameUk);
    }

    /**
     * Seeds {@code count} active {@code INDEPENDENT_MASTER}s in {@code city} — mirrors
     * {@code LocalityDiscoveryPerfHardeningTest.seedMaster}. All masters share the same
     * {@code avg_rating} (the default sort key) so the tie-break lands on {@code m.id}
     * deterministically; no test in this suite asserts row identity/order, only counts.
     */
    private void seedMasters(String city, int count) {
        UUID cityId = cityIdByName(city);
        for (int i = 0; i < count; i++) {
            UUID masterUserId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO users (id, email, password_hash, role, city, city_id, is_active, email_verified) "
                            + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', ?, ?, true, true)",
                    masterUserId, "memo-master-" + UUID.randomUUID() + "@beautica.test",
                    "$2a$04$placeholdervaluefortestonlydigest", city, cityId);

            UUID masterId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) "
                            + "VALUES (?, ?, 'INDEPENDENT_MASTER', 4.5, 1, true, NOW(), NOW())",
                    masterId, masterUserId);
        }
    }

    /**
     * Seeds {@code count} active salons in {@code city} — mirrors
     * {@code LocalityDiscoveryPerfHardeningTest.seedActiveSalon}, looped inline (a fresh
     * SALON_OWNER per salon, exactly like that helper).
     */
    private void seedSalons(String city, int count) {
        UUID cityId = cityIdByName(city);
        for (int i = 0; i < count; i++) {
            UUID ownerId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                            + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                    ownerId, "memo-salon-owner-" + UUID.randomUUID() + "@beautica.test",
                    "$2a$04$placeholdervaluefortestonlydigest");
            UUID salonId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO salons (id, owner_id, name, city, city_id, is_active, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, true, NOW(), NOW())",
                    salonId, ownerId, "MemoSalon-" + salonId, city, cityId);
        }
    }
}
