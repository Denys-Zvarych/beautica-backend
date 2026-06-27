package com.beautica.search;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack integration coverage for {@code GET /api/v1/search/{masters,salons}}.
 *
 * <p>Drives the real {@code SearchService} → native SQL → Postgres pipeline so
 * the {@code GROUP BY ... HAVING} count contract is exercised end-to-end. The
 * carryover scenarios (active-flag exclusions, HAVING-aware count) are the
 * regression net flagged by Phase 6.3 QA in {@code docs/backlog.md}.</p>
 *
 * <p><b>Why no auth helpers:</b> the endpoints under test are
 * {@code permitAll()} per {@code SecurityConfig}. Tests issue raw GETs without
 * bearer tokens — that's the feature. The single {@code _withNoAuth_} test
 * documents that coverage explicitly.</p>
 */
@DisplayName("Search — full-flow integration")
class SearchIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SearchIntegrationTest.class);
    private static final String MASTERS_URL = "/api/v1/search/masters";
    private static final String SALONS_URL = "/api/v1/search/salons";

    // ── Phase 20.1–20.3 — per-service filter fixtures ────────────────────────
    // Two real, Flyway-seeded (V75/V81) platform service-type slugs with
    // distinctive, collision-free Ukrainian display names. The hybrid match
    // resolves a slug to (service_type_id, name_uk); the name branch wraps name_uk
    // in %…% so a service_definition whose NAME contains it matches even when its
    // service_type_id FK is NULL (the legacy-recovery branch).
    private static final String SLUG_A = "hair-treatment-keratin";   // name_uk "Кератин"
    private static final String NAME_UK_A = "Кератин";
    private static final String SLUG_B = "injection-mesotherapy";    // name_uk "Мезотерапія"

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheManager cacheManager;

    /**
     * Wires the Apache HttpClient factory before the first request in each
     * test. Kept as an explicit method (called once per test) rather than a
     * {@code @BeforeEach} hook to avoid swapping the factory between
     * unrelated tests in the same JVM-shared application context. The factory
     * itself is reusable; only the call ordering matters.
     */
    private void ensureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ── Master search — happy paths ──────────────────────────────────────────

    @Test
    @DisplayName("GET /search/masters — finds masters by city when masters exist in city")
    void should_findMastersByCity_when_mastersExistInCity() throws Exception {
        ensureHttpClient();
        seedMasterWithCity("Київ", "4.50");
        seedMasterWithCity("Київ", "4.20");
        seedMasterWithCity("Київ", "3.80");
        seedMasterWithCity("Харків", "4.90");

        log.debug("Act: GET {}?city=Київ — must return 3 masters", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("data").path("data").size())
                .as("only Київ masters should be returned")
                .isEqualTo(3);
        assertThat(root.path("data").path("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("GET /search/masters — filters by category when master has service in category")
    void should_filterByCategory_when_masterHasServiceInCategory() throws Exception {
        ensureHttpClient();
        UUID m1 = seedMaster("Київ", "4.00");
        seedServiceWithCategory(m1, m1, "MANICURE", new BigDecimal("250.00"), true, true);

        UUID m2 = seedMaster("Київ", "4.00");
        seedServiceWithCategory(m2, m2, "HAIRCUT", new BigDecimal("400.00"), true, true);

        log.debug("Act: GET {}?category=MANICURE — must return 1 master", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?category=MANICURE&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(1);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(m1.toString());
    }

    @Test
    @DisplayName("GET /search/masters — filters by price range using HAVING aggregate")
    void should_filterByPriceRange_when_minAndMaxPriceSet() throws Exception {
        ensureHttpClient();
        seedMasterWithService("Київ", "4.00", new BigDecimal("150.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("300.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("600.00"));

        log.debug("Act: GET {}?minPrice=200&maxPrice=500 — only 300 in range", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?minPrice=200&maxPrice=500&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(1);
        assertThat(data.path("totalElements").asLong())
                .as("HAVING-aware count must match the filtered page size")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("GET /search/masters — filters by minimum rating")
    void should_filterByMinRating_when_ratingSet() throws Exception {
        ensureHttpClient();
        seedMasterWithCity("Київ", "3.50");
        seedMasterWithCity("Київ", "4.00");
        seedMasterWithCity("Київ", "4.80");

        log.debug("Act: GET {}?minRating=4.0 — must return 2 masters", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?minRating=4.0&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("GET /search/masters — totalElements correct when many masters match a filter and page is small")
    void should_returnCorrectTotalElements_when_manyMastersMatchFilter() throws Exception {
        ensureHttpClient();
        for (int i = 0; i < 7; i++) {
            seedMasterWithCity("Київ", "4.00");
        }

        log.debug("Act: GET {}?city=Київ&size=3 — must return 3 in content, totalElements=7", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=3", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(3);
        assertThat(data.path("totalElements").asLong()).isEqualTo(7L);
        assertThat(data.path("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("GET /search/masters — empty content when city has no masters")
    void should_returnEmptyList_when_noCityMatch() throws Exception {
        ensureHttpClient();
        seedMasterWithCity("Київ", "4.00");

        log.debug("Act: GET {}?city=Одеса — must return 0 masters", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Одеса") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").isArray()).isTrue();
        assertThat(data.path("data").size()).isEqualTo(0);
        assertThat(data.path("totalElements").asLong()).isEqualTo(0L);
    }

    // ── Public / no-auth coverage ────────────────────────────────────────────

    @Test
    @DisplayName("GET /search/{masters,salons} — both endpoints return 200 without auth")
    void should_return200WithNoAuth_when_searchEndpointsAccessed() {
        ensureHttpClient();

        ResponseEntity<String> mastersResponse = restTemplate.exchange(
                MASTERS_URL + "?page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);
        ResponseEntity<String> salonsResponse = restTemplate.exchange(
                SALONS_URL + "?page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(mastersResponse.getStatusCode())
                .as("masters search must be reachable without bearer token")
                .isEqualTo(HttpStatus.OK);
        assertThat(salonsResponse.getStatusCode())
                .as("salons search must be reachable without bearer token")
                .isEqualTo(HttpStatus.OK);
    }

    // ── Carryover from Phase 6.3 (deferred IT coverage) ──────────────────────

    @Test
    @DisplayName("GET /search/masters — excludes masters with masters.is_active=false")
    void should_excludeInactiveMasters_when_searchPerformed() throws Exception {
        ensureHttpClient();
        UUID activeId = seedMasterWithCity("Київ", "4.00");
        UUID inactiveId = seedMasterWithCity("Київ", "4.00");
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", inactiveId);

        log.debug("Act: GET {}?city=Київ — inactive master must NOT appear", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("masterId").asText()).isEqualTo(activeId.toString());
    }

    @Test
    @DisplayName("GET /search/masters — excludes masters whose user has users.is_active=false")
    void should_excludeInactiveUsers_when_searchPerformed() throws Exception {
        ensureHttpClient();
        UUID active = seedMasterWithCity("Київ", "4.00");
        UUID inactive = seedMasterWithCity("Київ", "4.00");
        UUID inactiveUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, inactive);
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", inactiveUserId);

        log.debug("Act: GET {}?city=Київ — master with inactive user must NOT appear", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("masterId").asText()).isEqualTo(active.toString());
    }

    @Test
    @DisplayName("GET /search/masters — price aggregate ignores master_services rows where is_active=false")
    void should_excludeInactiveMasterServices_when_priceFiltered() throws Exception {
        ensureHttpClient();
        UUID master = seedMaster("Київ", "4.00");

        // Active row: price 600 (above filter ceiling)
        seedServiceWithCategory(master, master, "MANICURE", new BigDecimal("600.00"), true, true);
        // Inactive row: price 250 (would be in range if it counted)
        seedServiceWithCategory(master, master, "MANICURE", new BigDecimal("250.00"), true, false);

        log.debug("Act: GET {}?minPrice=200&maxPrice=300 — inactive master_services must NOT contribute to MIN aggregate", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?minPrice=200&maxPrice=300&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size())
                .as("master with only an inactive 250.00 service must not appear in [200,300] price filter")
                .isEqualTo(0);
        assertThat(data.path("totalElements").asLong()).isEqualTo(0L);
    }

    @Test
    @DisplayName("GET /search/masters — totalElements matches HAVING-filtered count, not pre-HAVING DISTINCT")
    void should_returnCorrectTotalElements_with_priceHavingFilter() throws Exception {
        ensureHttpClient();
        // 1 master in [200,500], 2 outside — naive COUNT(DISTINCT m.id) without HAVING would
        // return 3 and produce phantom pages. Real count must be 1.
        seedMasterWithService("Київ", "4.00", new BigDecimal("100.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("350.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("700.00"));

        log.debug("Act: GET {}?minPrice=200&maxPrice=500 — totalElements must be 1, not 3", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?minPrice=200&maxPrice=500&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(1);
        assertThat(data.path("totalElements").asLong())
                .as("HAVING-aware count must reflect actual filtered rows")
                .isEqualTo(1L);
    }

    // ── Phase 6.5 — dynamic SQL branches ─────────────────────────────────────

    @Test
    @DisplayName("GET /search/masters — no category/price filter returns null minEffectivePrice (JOIN elided)")
    void should_returnNullMinEffectivePrice_when_noServiceJoinNeeded() throws Exception {
        ensureHttpClient();
        UUID masterId = seedMasterWithService("Київ", "4.50", new BigDecimal("250.00"));

        log.debug("Act: GET {}?city=Київ — no category/price filter, JOIN should be elided", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("masterId").asText()).isEqualTo(masterId.toString());
        // PERF-M2: min_effective_price is a pre-computed column always projected from masters.
        // The seeder's UPDATE refreshes it to 250.00, so the value is non-null even when the
        // service-join is elided (no category/price filter). The old "JOIN elided → NULL"
        // behaviour no longer applies since the column is always SELECTed from the masters row.
        assertThat(new BigDecimal(rows.get(0).path("minEffectivePrice").asText()))
                .as("min_effective_price is pre-computed and always projected — seedServiceWithCategory sets 250.00")
                .isEqualByComparingTo(new BigDecimal("250.00"));
    }

    @Test
    @DisplayName("GET /search/masters — category filter forces JOIN and returns aggregated minEffectivePrice")
    void should_returnAggregatedMinEffectivePrice_when_categoryFilterSet() throws Exception {
        ensureHttpClient();
        UUID master = seedMaster("Київ", "4.20");
        seedServiceWithCategory(master, master, "MANICURE", new BigDecimal("180.00"), true, true);
        seedServiceWithCategory(master, master, "MANICURE", new BigDecimal("320.00"), true, true);

        log.debug("Act: GET {}?category=MANICURE — JOIN must be active, MIN must return 180.00", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?category=MANICURE&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(new BigDecimal(rows.get(0).path("minEffectivePrice").asText()))
                .as("MIN(COALESCE(price_override, base_price)) over the two active services")
                .isEqualByComparingTo(new BigDecimal("180.00"));
    }

    @Test
    @DisplayName("GET /search/masters — flat count branch returns totalElements without HAVING when no price filter")
    void should_useFlatCountBranch_when_noPriceFilterApplied() throws Exception {
        ensureHttpClient();
        for (int i = 0; i < 5; i++) {
            seedMasterWithCity("Київ", "4.00");
        }

        log.debug("Act: GET {}?city=Київ&size=2 — flat count branch — totalElements must equal 5", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=2", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(2);
        assertThat(data.path("totalElements").asLong())
                .as("flat COUNT(DISTINCT m.id) must count every active master in Київ")
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("GET /search/masters — wrapped-subquery count branch honours HAVING price filter")
    void should_useSubqueryCountBranch_when_priceFilterApplied() throws Exception {
        ensureHttpClient();
        // Five candidates; price filter should retain exactly two.
        seedMasterWithService("Київ", "4.00", new BigDecimal("100.00"));   // below
        seedMasterWithService("Київ", "4.00", new BigDecimal("250.00"));   // in range
        seedMasterWithService("Київ", "4.00", new BigDecimal("400.00"));   // in range
        seedMasterWithService("Київ", "4.00", new BigDecimal("700.00"));   // above
        seedMasterWithService("Київ", "4.00", new BigDecimal("900.00"));   // above

        log.debug("Act: GET {}?minPrice=200&maxPrice=500 — subquery count branch — totalElements must equal 2", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?minPrice=200&maxPrice=500&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(2);
        assertThat(data.path("totalElements").asLong())
                .as("subquery wrapper must respect HAVING — exactly 2 masters fall inside [200,500]")
                .isEqualTo(2L);
    }

    // ── Phase 6.5 — caching ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /search/masters — first 5 pages populate the search:masters cache")
    void should_populateCache_when_firstPagesQueried() {
        ensureHttpClient();
        Cache cache = cacheManager.getCache("search:masters");
        assertThat(cache).as("search:masters cache must be registered").isNotNull();
        cache.clear();

        seedMasterWithCity("Київ", "4.00");

        log.debug("Act: GET {}?city=Київ — expect cache to be populated after the call", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        long cachedEntries = countNativeEntries(cache);
        assertThat(cachedEntries)
                .as("first call must populate exactly one entry in search:masters")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("GET /search/masters — page index >= 5 skips the cache (cold-path)")
    void should_skipCache_when_pageIndexIsAtOrAboveFive() {
        ensureHttpClient();
        seedMasterWithCity("Київ", "4.00");

        Cache cache = cacheManager.getCache("search:masters");
        assertThat(cache).isNotNull();
        cache.clear();
        long before = countNativeEntries(cache);

        // Page 5 (0-indexed) — the @Cacheable condition is `pageNumber < 5`, so
        // this call must bypass the cache. With size=1 and 1 seeded master, the
        // service returns an empty content list but the underlying query still
        // runs.
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=5&size=1", HttpMethod.GET,
                anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        long after = countNativeEntries(cache);
        assertThat(after)
                .as("page=5 is above the cache window; no entry must be written")
                .isEqualTo(before);
    }

    /**
     * Caffeine cache size probe — {@link Cache#getNativeCache()} surfaces the
     * underlying Caffeine cache, which exposes an estimated entry count. The
     * estimate is exact in single-threaded test code immediately after a
     * synchronous call.
     */
    private long countNativeEntries(Cache cache) {
        Object native_ = cache.getNativeCache();
        if (native_ instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine) {
            return caffeine.estimatedSize();
        }
        throw new IllegalStateException("Expected Caffeine cache, got " + native_.getClass());
    }

    // ── Phase 6.5 QA — combined filters, salons, missing services, cache keys ─

    @Test
    @DisplayName("GET /search/masters — combines all filters and returns only the matching master")
    void should_combineAllFiltersAndReturnOnlyMatchingMaster_when_fullFilterRequest() throws Exception {
        ensureHttpClient();
        // m1: matches every predicate
        UUID m1 = seedMasterWithServiceCategoryAndPrice("Київ", "4.50", "MANICURE", new BigDecimal("180.00"));
        // m2: out of price range — fails minPrice
        seedMasterWithServiceCategoryAndPrice("Київ", "4.50", "MANICURE", new BigDecimal("50.00"));
        // m3: wrong category
        seedMasterWithServiceCategoryAndPrice("Київ", "4.50", "HAIRCUT", new BigDecimal("200.00"));
        // m4: rating below threshold
        seedMasterWithServiceCategoryAndPrice("Київ", "3.00", "MANICURE", new BigDecimal("180.00"));

        log.debug("Act: GET {} with full filter set — must return only m1", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&category=MANICURE&minPrice=100.00&maxPrice=300.00&minRating=4.0"
                        + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong())
                .as("only the fully-conforming master must be counted")
                .isEqualTo(1L);
        assertThat(page.path("data").size()).isEqualTo(1);
        assertThat(page.path("data").get(0).path("masterId").asText()).isEqualTo(m1.toString());
    }

    @Test
    @DisplayName("GET /search/masters — second identical call hits cache without growing entry count")
    void should_returnCachedResultWithoutHittingDb_when_secondIdenticalCall() throws Exception {
        ensureHttpClient();
        Cache cache = cacheManager.getCache("search:masters");
        assertThat(cache).as("search:masters cache must be registered").isNotNull();
        cache.clear();

        seedMasterWithCity("Київ", "4.00");

        String url = MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20";

        // First call: cache miss → populates entry
        ResponseEntity<String> first = restTemplate.exchange(url, HttpMethod.GET, anonymous(), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countNativeEntries(cache))
                .as("first call must populate exactly one cache entry")
                .isEqualTo(1L);

        // Second identical call: cache hit → no new entry, identical body
        ResponseEntity<String> second = restTemplate.exchange(url, HttpMethod.GET, anonymous(), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countNativeEntries(cache))
                .as("second identical call must reuse the same cache entry")
                .isEqualTo(1L);

        // Bodies must be byte-identical when cache is hit
        assertThat(objectMapper.readTree(second.getBody()))
                .as("cached body must match the original")
                .isEqualTo(objectMapper.readTree(first.getBody()));
    }

    @Test
    @DisplayName("GET /search/masters — distinct cache keys produced when requests differ by a single filter")
    void should_useDistinctCacheKeys_when_requestsDifferByOnlyOneFilter() {
        ensureHttpClient();
        Cache cache = cacheManager.getCache("search:masters");
        assertThat(cache).as("search:masters cache must be registered").isNotNull();
        cache.clear();

        seedMasterWithCity("Київ", "4.00");
        seedMasterWithCity("Львів", "4.00");

        restTemplate.exchange(MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);
        restTemplate.exchange(MASTERS_URL + "?location.cityId=" + cityIdByName("Львів") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(countNativeEntries(cache))
                .as("each distinct filter combo must occupy its own cache entry")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("GET /search/masters — master without master_services rows surfaces with null minEffectivePrice when no filter applied")
    void should_returnMasterWithNullPrice_when_masterHasNoMasterServicesRows_andNoFilter() throws Exception {
        ensureHttpClient();
        UUID masterId = seedMasterWithoutServices("Київ", "4.00");

        log.debug("Act: GET {}?city=Київ — JOIN must be elided, master visible with null price", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(page.path("data").get(0).path("masterId").asText()).isEqualTo(masterId.toString());
        assertThat(page.path("data").get(0).path("minEffectivePrice").isNull())
                .as("no service-join branch → minEffectivePrice must project NULL")
                .isTrue();
    }

    @Test
    @DisplayName("GET /search/masters — master without services excluded when category filter forces the JOIN")
    void should_excludeMasterWithoutServices_when_categoryFilterApplied() throws Exception {
        ensureHttpClient();
        seedMasterWithoutServices("Київ", "4.00");

        log.debug("Act: GET {}?city=Київ&category=MANICURE — JOIN active, master without services must drop", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&category=MANICURE&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong())
                .as("category filter forces JOIN to master_services — master without rows must not appear")
                .isZero();
        assertThat(page.path("data").size()).isZero();
    }

    // ── Phase 6.5 QA — salon search IT parity ────────────────────────────────

    @Test
    @DisplayName("GET /search/salons — finds salons by city when salons exist in city")
    void should_findSalonsByCity_when_salonsExistInCity() throws Exception {
        ensureHttpClient();
        seedActiveSalon("Київ", "Region-A");
        seedActiveSalon("Львів", "Region-B");

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(page.path("data").get(0).path("cityLabel").asText()).isEqualTo("Київ");
    }

    @Test
    @DisplayName("GET /search/salons — empty page when no salons match city")
    void should_returnEmptyList_when_noSalonsMatchCity() throws Exception {
        ensureHttpClient();
        seedActiveSalon("Київ", null);

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Одеса") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody())
                .path("data").path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("GET /search/salons — first 5 pages populate the search:salons cache")
    void should_populateSalonCache_when_firstPagesQueried() {
        ensureHttpClient();
        Cache cache = cacheManager.getCache("search:salons");
        assertThat(cache).as("search:salons cache must be registered").isNotNull();
        cache.clear();

        seedActiveSalon("Київ", null);

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(countNativeEntries(cache))
                .as("first salon search call must populate exactly one entry")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("GET /search/salons — page index >= 5 skips the search:salons cache")
    void should_skipSalonCache_when_pageIndexIsAtOrAboveFive() {
        ensureHttpClient();
        Cache cache = cacheManager.getCache("search:salons");
        assertThat(cache).as("search:salons cache must be registered").isNotNull();
        cache.clear();

        seedActiveSalon("Київ", null);

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=5&size=1", HttpMethod.GET,
                anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(countNativeEntries(cache))
                .as("page=5 is above the cache window — no entry must be written")
                .isZero();
    }

    // ── Phase 10.5 — SALON_ADMIN exclusion (Q6 verify-not-present + Q9 cross-role) ─

    @Test
    @DisplayName("GET /search/masters — a SALON_ADMIN account never appears in master results AND is not counted (no pagination leak)")
    void should_excludeSalonAdmin_fromBothResultsAndTotalElements() throws Exception {
        ensureHttpClient();
        UUID legitMaster = seedMasterWithCity("Київ", "4.40");
        UUID adminMasterId = seedSalonAdminMaster("Київ", "4.95");

        log.debug("Act: GET {}?location.cityId=Київ — SALON_ADMIN must be absent from data AND from totalElements", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size())
                .as("only the non-admin master is discoverable")
                .isEqualTo(1);
        assertThat(data.path("data").get(0).path("masterId").asText())
                .isEqualTo(legitMaster.toString());
        // Q6: assert the admin id is NOT present, not merely that size==1.
        for (JsonNode row : data.path("data")) {
            assertThat(row.path("masterId").asText())
                    .as("the SALON_ADMIN master row must never surface")
                    .isNotEqualTo(adminMasterId.toString());
        }
        // Q9: the predicate must live in the COUNT path too — a leaked admin in
        // totalElements would let an anonymous caller infer admin existence via
        // phantom pages even when the row itself is filtered from the content.
        assertThat(data.path("totalElements").asLong())
                .as("count path excludes SALON_ADMIN — no pagination existence-oracle")
                .isEqualTo(1L);
    }

    // ── Phase 10.5 — district-primary FK filter (read side, end-to-end) ──────

    @Test
    @DisplayName("GET /search/masters — district-primary: ?location.districtId returns only masters in that district, not the rest of the city")
    void should_filterMastersByDistrict_when_districtIdSupplied() throws Exception {
        ensureHttpClient();
        UUID districtA = districtIdInCity("Київ", 0);
        UUID districtB = districtIdInCity("Київ", 1);
        UUID inDistrictA = seedIndependentMasterInDistrict("Київ", districtA, "4.30");
        seedIndependentMasterInDistrict("Київ", districtB, "4.70");

        log.debug("Act: GET {}?location.cityId=Київ&location.districtId=A — district wins, only district-A master", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&location.districtId=" + districtA + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size())
                .as("district-primary: a supplied districtId wins over the city")
                .isEqualTo(1);
        assertThat(data.path("data").get(0).path("masterId").asText())
                .isEqualTo(inDistrictA.toString());
        assertThat(data.path("totalElements").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("GET /search/masters — districted city WITHOUT a districtId widens to city-level (both district masters returned)")
    void should_widenToCityLevel_when_districtedCityHasNoDistrictId() throws Exception {
        ensureHttpClient();
        UUID districtA = districtIdInCity("Київ", 0);
        UUID districtB = districtIdInCity("Київ", 1);
        seedIndependentMasterInDistrict("Київ", districtA, "4.30");
        seedIndependentMasterInDistrict("Київ", districtB, "4.70");

        log.debug("Act: GET {}?location.cityId=Київ (no districtId) — read side widens to whole city", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("a districted city without a district widens to city-level on the read side (write-side is Phase 10.6)")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("GET /search/salons — district-primary: ?location.districtId filters salons by district, not the whole city")
    void should_filterSalonsByDistrict_when_districtIdSupplied() throws Exception {
        ensureHttpClient();
        UUID districtA = districtIdInCity("Київ", 0);
        UUID districtB = districtIdInCity("Київ", 1);
        seedSalonInDistrict("Київ", districtA, "Salon District A");
        seedSalonInDistrict("Київ", districtB, "Salon District B");

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&location.districtId=" + districtA + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong())
                .as("salon search honours the district-primary FK filter")
                .isEqualTo(1L);
        assertThat(page.path("data").get(0).path("name").asText()).isEqualTo("Salon District A");
        assertThat(page.path("data").get(0).path("districtLabel").asText())
                .as("resolved districtLabel is the taxonomy name_uk — no extra round-trip")
                .isNotBlank();
    }

    // ── Phase 10.5 — SALON_MASTER salon-link locality (multi-salon 2.11–2.14) ─

    @Test
    @DisplayName("GET /search/masters — Phase 19.7: an employed SALON_MASTER is NEVER returned (reachable only via the salon page); an INDEPENDENT_MASTER in the same district IS returned")
    void should_excludeSalonMasterButReturnIndependent_when_searchedByDistrict() throws Exception {
        ensureHttpClient();
        UUID district = districtIdInCity("Київ", 0);

        // An employed SALON_MASTER whose salon sits in the searched district.
        // Pre-19.7 the salon-link COALESCE locality made this master discoverable
        // here; Phase 19.7 (decision 7) restricts /search/masters to
        // INDEPENDENT_MASTER only, so this row must be absent from data AND count.
        UUID salonMasterId = seedSalonMasterWithSalonDistrictAndUserDistrict(
                "Київ", district, district, "4.95");
        // An independent master in the same district — still discoverable.
        UUID independentId = seedIndependentMasterInDistrict("Київ", district, "4.10");

        log.debug("Act: GET {} by district — SALON_MASTER must be excluded (salon-page only), INDEPENDENT_MASTER returned", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&location.districtId=" + district + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("Phase 19.7: only the INDEPENDENT_MASTER is counted — SALON_MASTER excluded from the count path (no pagination existence-oracle)")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText())
                .as("the single returned row is the independent master")
                .isEqualTo(independentId.toString());
        // Explicit Q6 verify-not-present: the SALON_MASTER id must never surface.
        for (JsonNode row : data.path("data")) {
            assertThat(row.path("masterId").asText())
                    .as("the employed SALON_MASTER must never appear in /search/masters")
                    .isNotEqualTo(salonMasterId.toString());
        }
    }

    @Test
    @DisplayName("GET /search/masters — an INDEPENDENT_MASTER (no salon) is discovered via its own user-row district (COALESCE falls through)")
    void should_resolveIndependentMasterLocalityViaUserRow_when_searchedByDistrict() throws Exception {
        ensureHttpClient();
        UUID district = districtIdInCity("Київ", 0);
        UUID masterId = seedIndependentMasterInDistrict("Київ", district, "4.10");

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&location.districtId=" + district + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("no salon link → COALESCE falls through to the user row's district")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(masterId.toString());
    }

    // ── Phase 10.5 — resolved labels (no round-trip; null districtLabel for non-districted) ─

    @Test
    @DisplayName("GET /search/masters — district master carries resolved cityLabel AND districtLabel name_uk in one response")
    void should_stampBothLabels_when_masterIsInADistrict() throws Exception {
        ensureHttpClient();
        UUID district = districtIdInCity("Київ", 0);
        seedIndependentMasterInDistrict("Київ", district, "4.80");

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&location.districtId=" + district + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode row = objectMapper.readTree(response.getBody()).path("data").path("data").get(0);
        assertThat(row.path("cityLabel").asText())
                .as("cityLabel resolved from taxonomy name_uk with no extra client round-trip")
                .isEqualTo("Київ");
        assertThat(row.path("districtLabel").isNull())
                .as("districted master → districtLabel is the taxonomy name_uk, not null")
                .isFalse();
        assertThat(row.path("districtLabel").asText()).isNotBlank();
        // §I — internal FK UUIDs are not exposed on this permitAll endpoint.
        assertThat(row.path("cityId").isMissingNode())
                .as("internal city UUID must not leak on the public search DTO")
                .isTrue();
        assertThat(row.path("districtId").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("GET /search/salons — non-districted salon has resolved cityLabel and a null districtLabel")
    void should_returnNullDistrictLabel_when_salonCityIsNotDistricted() throws Exception {
        ensureHttpClient();
        seedActiveSalon("Львів", null);

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Львів") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode row = objectMapper.readTree(response.getBody()).path("data").path("data").get(0);
        assertThat(row.path("cityLabel").asText()).isEqualTo("Львів");
        assertThat(row.path("districtLabel").isNull())
                .as("salon with no district FK → districtLabel surfaces as JSON null")
                .isTrue();
    }

    // ── Phase 19.7 — master role scope (decision 7) ──────────────────────────

    @Test
    @DisplayName("GET /search/masters — Phase 19.7: SALON_MASTER absent, INDEPENDENT_MASTER present with its minEffectivePrice")
    void should_returnOnlyIndependentMaster_when_salonMasterAlsoInCity() throws Exception {
        ensureHttpClient();
        // One employed SALON_MASTER and one INDEPENDENT_MASTER in the same city.
        UUID salonMasterId = seedEmployedSalonMaster("Київ", "4.90");
        UUID independentId = seedMaster("Київ", "4.10");
        seedServiceWithCategory(independentId, independentId, "MANICURE",
                new BigDecimal("250.00"), true, true);

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("only the INDEPENDENT_MASTER is discoverable; the SALON_MASTER is reachable only via the salon page")
                .isEqualTo(1L);
        JsonNode row = data.path("data").get(0);
        assertThat(row.path("masterId").asText())
                .as("the single returned master is the independent one")
                .isEqualTo(independentId.toString());
        assertThat(new BigDecimal(row.path("minEffectivePrice").asText()))
                .as("the independent master surfaces with its pre-computed minEffectivePrice")
                .isEqualByComparingTo(new BigDecimal("250.00"));
        for (JsonNode r : data.path("data")) {
            assertThat(r.path("masterId").asText())
                    .as("the SALON_MASTER id must never surface in /search/masters")
                    .isNotEqualTo(salonMasterId.toString());
        }
    }

    // ── Phase 19.7 — salon price range (decision 5) ───────────────────────────

    @Test
    @DisplayName("GET /search/salons — priceMin = lowest base_price, priceMax = highest (price_max for RANGE, base_price for FIXED) across the salon's masters' active services")
    void should_returnSalonPriceRange_acrossFixedAndRangeServices() throws Exception {
        ensureHttpClient();
        UUID salonId = seedActiveSalon("Київ", null);
        UUID masterId = seedSalonMasterFor(salonId, "Київ", "4.00");
        // FIXED 200 (floor & ceiling 200); RANGE 150..600 (floor 150, ceiling 600).
        seedSalonServiceForMaster(masterId, salonId, "MANICURE", "FIXED",
                new BigDecimal("200.00"), null, true, true);
        seedSalonServiceForMaster(masterId, salonId, "HAIRCUT", "RANGE",
                new BigDecimal("150.00"), new BigDecimal("600.00"), true, true);

        JsonNode row = singleSalonRow("Київ", null);
        assertThat(new BigDecimal(row.path("priceMin").asText()))
                .as("priceMin = MIN(base_price) across active services = 150.00")
                .isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(new BigDecimal(row.path("priceMax").asText()))
                .as("priceMax = MAX(price_max for RANGE, base_price for FIXED) = 600.00")
                .isEqualByComparingTo(new BigDecimal("600.00"));
    }

    @Test
    @DisplayName("GET /search/salons — category filter scopes the price range to matching services only")
    void should_scopeSalonPriceRange_toCategoryFilter() throws Exception {
        ensureHttpClient();
        UUID salonId = seedActiveSalon("Київ", null);
        UUID masterId = seedSalonMasterFor(salonId, "Київ", "4.00");
        seedSalonServiceForMaster(masterId, salonId, "MANICURE", "FIXED",
                new BigDecimal("200.00"), null, true, true);
        seedSalonServiceForMaster(masterId, salonId, "HAIRCUT", "RANGE",
                new BigDecimal("150.00"), new BigDecimal("600.00"), true, true);

        // Scope to MANICURE only — the HAIRCUT 150..600 service must not count.
        JsonNode row = singleSalonRow("Київ", "MANICURE");
        assertThat(new BigDecimal(row.path("priceMin").asText()))
                .as("category-scoped priceMin = the MANICURE FIXED floor 200.00")
                .isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(new BigDecimal(row.path("priceMax").asText()))
                .as("category-scoped priceMax = the MANICURE FIXED ceiling 200.00")
                .isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    @DisplayName("GET /search/salons — priceMin AND priceMax both null when the salon has no active priced services")
    void should_returnNullPriceRange_when_salonHasNoActiveServices() throws Exception {
        ensureHttpClient();
        UUID salonId = seedActiveSalon("Київ", null);
        UUID masterId = seedSalonMasterFor(salonId, "Київ", "4.00");
        // Only an INACTIVE service exists — it must not contribute to the band.
        // Salon-owned def is itself inactive (is_active = false) → it must not
        // contribute to the salon's price band / serviceNames. (master_services
        // is no longer consulted for salon search; the def's own is_active is the
        // gate — see SalonRepository salon row-source fix.)
        seedSalonServiceForMaster(masterId, salonId, "MANICURE", "FIXED",
                new BigDecimal("300.00"), null, false, false);

        JsonNode row = singleSalonRow("Київ", null);
        assertThat(row.path("priceMin").isNull())
                .as("no active priced service → priceMin is JSON null")
                .isTrue();
        assertThat(row.path("priceMax").isNull())
                .as("no active priced service → priceMax is JSON null")
                .isTrue();
    }

    @Test
    @DisplayName("GET /search/salons — priceMin == priceMax (equal values, not collapsed) when a single FIXED service prices the salon")
    void should_returnEqualPriceMinMax_when_singleFixedService() throws Exception {
        ensureHttpClient();
        UUID salonId = seedActiveSalon("Київ", null);
        UUID masterId = seedSalonMasterFor(salonId, "Київ", "4.00");
        seedSalonServiceForMaster(masterId, salonId, "MANICURE", "FIXED",
                new BigDecimal("350.00"), null, true, true);

        JsonNode row = singleSalonRow("Київ", null);
        // The backend returns both values; it does NOT collapse equal endpoints
        // (that is a mobile-rendering concern). Assert both equal 350.00.
        assertThat(new BigDecimal(row.path("priceMin").asText()))
                .isEqualByComparingTo(new BigDecimal("350.00"));
        assertThat(new BigDecimal(row.path("priceMax").asText()))
                .as("priceMax equals priceMin for a lone FIXED service — both present, not collapsed")
                .isEqualByComparingTo(new BigDecimal("350.00"));
    }

    // ── Phase 19.7 — salon serviceNames (never null, DISTINCT, capped at 3) ───

    @Test
    @DisplayName("GET /search/salons — serviceNames carries the salon's distinct active service names, capped at 3")
    void should_populateSalonServiceNames_distinctAndCapped() throws Exception {
        ensureHttpClient();
        UUID salonId = seedActiveSalon("Київ", null);
        UUID masterId = seedSalonMasterFor(salonId, "Київ", "4.00");
        // Five distinct names + one duplicate → 5 distinct, 6 rows; cap is 3.
        seedNamedSalonServiceForMaster(masterId, salonId, "Манікюр", "MANICURE", new BigDecimal("200.00"));
        seedNamedSalonServiceForMaster(masterId, salonId, "Педикюр", "PEDICURE", new BigDecimal("300.00"));
        seedNamedSalonServiceForMaster(masterId, salonId, "Стрижка", "HAIRCUT", new BigDecimal("400.00"));
        seedNamedSalonServiceForMaster(masterId, salonId, "Фарбування", "HAIRCUT", new BigDecimal("500.00"));
        seedNamedSalonServiceForMaster(masterId, salonId, "Брови", "BROWS", new BigDecimal("150.00"));
        seedNamedSalonServiceForMaster(masterId, salonId, "Манікюр", "MANICURE", new BigDecimal("210.00")); // dup name

        JsonNode row = singleSalonRow("Київ", null);
        JsonNode names = row.path("serviceNames");
        assertThat(names.isArray())
                .as("salon serviceNames must be a JSON array")
                .isTrue();
        java.util.List<String> values = new java.util.ArrayList<>();
        names.forEach(n -> values.add(n.asText()));
        assertThat(values)
                .as("salon serviceNames is capped at SERVICE_NAME_CAP=3")
                .hasSize(3);
        assertThat(values)
                .as("salon serviceNames is DISTINCT — no duplicate display string")
                .doesNotHaveDuplicates();
        assertThat(values)
                .as("each surfaced name is one of the salon's seeded distinct service names")
                .isSubsetOf("Брови", "Манікюр", "Педикюр", "Стрижка", "Фарбування");
    }

    @Test
    @DisplayName("GET /search/salons — serviceNames is an empty array (never null) for a salon with no active priced services")
    void should_returnEmptySalonServiceNames_when_salonHasNoActiveServices() throws Exception {
        ensureHttpClient();
        UUID salonId = seedActiveSalon("Київ", null);
        UUID masterId = seedSalonMasterFor(salonId, "Київ", "4.00");
        // Only an INACTIVE service — it must not contribute to serviceNames.
        // Salon-owned def is itself inactive (is_active = false) → it must not
        // contribute to the salon's price band / serviceNames. (master_services
        // is no longer consulted for salon search; the def's own is_active is the
        // gate — see SalonRepository salon row-source fix.)
        seedSalonServiceForMaster(masterId, salonId, "MANICURE", "FIXED",
                new BigDecimal("300.00"), null, false, false);

        JsonNode row = singleSalonRow("Київ", null);
        assertThat(row.path("serviceNames").isArray())
                .as("service-less salon → empty array")
                .isTrue();
        assertThat(row.path("serviceNames").size())
                .as("service-less salon → serviceNames is [] (never null)")
                .isZero();
    }

    /**
     * Seeds an active, FIXED-priced SALON-owned service definition with an
     * explicit (custom) {@code name} plus an active {@code master_services} link
     * for the given salon master. Used by the salon serviceNames test where the
     * surfaced names must be deterministic.
     */
    private void seedNamedSalonServiceForMaster(UUID masterId, UUID salonId, String serviceName,
                                                String category, BigDecimal basePrice) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                        "(id, owner_type, owner_id, name, category, base_duration_minutes, base_price, price_type, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'SALON', ?, ?, ?, 60, ?, 'FIXED', 0, true, NOW(), NOW())",
                serviceDefId, salonId, serviceName, category, basePrice);

        UUID msId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, true, NOW(), NOW())",
                msId, masterId, serviceDefId);
    }

    /**
     * Fetches the single salon row for a city (optionally category-scoped) and
     * returns its JSON node. Asserts exactly one salon is returned so the price
     * assertions are unambiguous.
     */
    private JsonNode singleSalonRow(String city, String category) throws Exception {
        String url = SALONS_URL + "?location.cityId=" + cityIdByName(city)
                + (category == null ? "" : "&category=" + category)
                + "&page=0&size=20";
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("exactly one salon expected for the price-range assertion")
                .isEqualTo(1L);
        return data.path("data").get(0);
    }

    /**
     * Seeds an employed SALON_MASTER (+ masters row) attached to an existing
     * {@code salonId} in the given city. Used by the salon-price-range tests:
     * the salon aggregation joins the master's active services via
     * {@code mm.salon.id = s.id}, so the master must carry {@code salon_id}.
     */
    private UUID seedSalonMasterFor(UUID salonId, String city, String avgRating) {
        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, city, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?, ?, true, true)",
                masterUserId, "price-master-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", salonId, city);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?::numeric, 1, true, NOW(), NOW())",
                masterId, masterUserId, salonId, avgRating);
        return masterId;
    }

    /**
     * Seeds a SALON-owned service definition (FIXED or RANGE) plus a
     * {@code master_services} link for the given master. Honours the V67
     * {@code chk_service_def_price_mode} CHECK: FIXED → {@code price_max} NULL;
     * RANGE → {@code price_max} >= {@code base_price}.
     *
     * @param priceType "FIXED" or "RANGE"
     * @param basePrice the floor (FIXED amount / RANGE minimum)
     * @param priceMax  the RANGE ceiling, or {@code null} for FIXED
     * @param defActive service_definitions.is_active
     * @param msActive  master_services.is_active
     */
    private void seedSalonServiceForMaster(UUID masterId, UUID salonId, String category,
                                           String priceType, BigDecimal basePrice,
                                           BigDecimal priceMax, boolean defActive, boolean msActive) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                        "(id, owner_type, owner_id, name, category, base_duration_minutes, base_price, price_type, price_max, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'SALON', ?, ?, ?, 60, ?, ?, ?, 0, ?, NOW(), NOW())",
                serviceDefId, salonId, "Priced Service " + serviceDefId, category,
                basePrice, priceType, priceMax, defActive);

        UUID msId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, NOW(), NOW())",
                msId, masterId, serviceDefId, msActive);
    }

    // ── Phase — name / service-name search, serviceNames, sort, salon price ──

    @Test
    @DisplayName("GET /search/masters — ?q matches the master's last name case-insensitively")
    void should_matchMasterByName_caseInsensitively_when_qSupplied() throws Exception {
        ensureHttpClient();
        UUID match = seedNamedIndependentMaster("Київ", "4.50", "Olena", "Kovalenko");
        seedNamedIndependentMaster("Київ", "4.50", "Ivan", "Petrenko");

        // Lower-case query must still hit "Kovalenko" (ILIKE, case-insensitive).
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?q=kovalenko&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(match.toString());
    }

    @Test
    @DisplayName("GET /search/masters — ?q matches a master via the (custom) service-definition name")
    void should_matchMasterByServiceName_when_qSupplied() throws Exception {
        ensureHttpClient();
        UUID match = seedNamedIndependentMaster("Київ", "4.50", "Anna", "Koval");
        seedNamedServiceForMaster(match, "Французький манікюр", "MANICURE", new BigDecimal("300.00"));
        seedNamedIndependentMaster("Київ", "4.50", "Boris", "Tkach");

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?q=французький&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(match.toString());
    }

    @Test
    @DisplayName("GET /search/masters — serviceNames carries the custom service-definition names (custom-over-default automatic)")
    void should_populateServiceNames_withCustomNames() throws Exception {
        ensureHttpClient();
        UUID master = seedNamedIndependentMaster("Київ", "4.50", "Olena", "Master");
        seedNamedServiceForMaster(master, "Авторський манікюр", "MANICURE", new BigDecimal("250.00"));
        seedNamedServiceForMaster(master, "Педикюр SPA", "PEDICURE", new BigDecimal("350.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode row = objectMapper.readTree(response.getBody()).path("data").path("data").get(0);
        JsonNode names = row.path("serviceNames");
        assertThat(names.isArray()).isTrue();
        java.util.List<String> values = new java.util.ArrayList<>();
        names.forEach(n -> values.add(n.asText()));
        assertThat(values).containsExactlyInAnyOrder("Авторський манікюр", "Педикюр SPA");
    }

    @Test
    @DisplayName("GET /search/masters — serviceNames is an empty array (not null) for a master with no active services")
    void should_returnEmptyServiceNames_when_masterHasNoServices() throws Exception {
        ensureHttpClient();
        seedNamedIndependentMaster("Київ", "4.50", "Solo", "Master");

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode row = objectMapper.readTree(response.getBody()).path("data").path("data").get(0);
        assertThat(row.path("serviceNames").isArray()).isTrue();
        assertThat(row.path("serviceNames").size())
                .as("service-less master → empty array, never null")
                .isZero();
    }

    // ── Phase 19.7 regression — category-scoped serviceNames preview ──────────

    @Test
    @DisplayName("GET /search/masters?category=EYELASH — serviceNames lists ONLY the filtered-category names (no foreign-category leak)")
    void should_scopeServiceNamesToFilteredCategory_when_masterHasMultiCategoryServices() throws Exception {
        ensureHttpClient();
        // ONE master carrying services in TWO categories: 2 in EYELASH, 1 in MAKEUP.
        UUID master = seedNamedIndependentMaster("Київ", "4.50", "Lash", "Master");
        seedNamedServiceForMaster(master, "Ламінування вій №3", "EYELASH", new BigDecimal("400.00"));
        seedNamedServiceForMaster(master, "Ламінування вій №4", "EYELASH", new BigDecimal("450.00"));
        seedNamedServiceForMaster(master, "Макіяж №1", "MAKEUP", new BigDecimal("700.00"));

        // Search the EYELASH category in the seed's city — the WHERE EXISTS keeps the
        // master in (it has an EYELASH service), and the names preview must be scoped.
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&category=EYELASH&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("category filter keeps the master with an EYELASH service")
                .isEqualTo(1L);
        JsonNode row = data.path("data").get(0);
        assertThat(row.path("masterId").asText()).isEqualTo(master.toString());

        java.util.List<String> names = new java.util.ArrayList<>();
        row.path("serviceNames").forEach(n -> names.add(n.asText()));
        assertThat(names)
                .as("category-filtered card lists ONLY EYELASH names — exactly the two seeded")
                .containsExactlyInAnyOrder("Ламінування вій №3", "Ламінування вій №4");
        assertThat(names)
                .as("the MAKEUP name from another category must NOT leak into an EYELASH-filtered card")
                .doesNotContain("Макіяж №1");
    }

    @Test
    @DisplayName("GET /search/masters (no category) — serviceNames spans ALL categories (no-filter branch stays catalogue-wide)")
    void should_returnAllCategoryServiceNames_when_noCategoryFilter() throws Exception {
        ensureHttpClient();
        // Same multi-category master; with NO category filter the preview is
        // catalogue-wide and capped at SERVICE_NAME_CAP=3 → all three names surface.
        UUID master = seedNamedIndependentMaster("Київ", "4.50", "Multi", "Master");
        seedNamedServiceForMaster(master, "Ламінування вій №3", "EYELASH", new BigDecimal("400.00"));
        seedNamedServiceForMaster(master, "Ламінування вій №4", "EYELASH", new BigDecimal("450.00"));
        seedNamedServiceForMaster(master, "Макіяж №1", "MAKEUP", new BigDecimal("700.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode row = objectMapper.readTree(response.getBody()).path("data").path("data").get(0);
        java.util.List<String> names = new java.util.ArrayList<>();
        row.path("serviceNames").forEach(n -> names.add(n.asText()));
        assertThat(names)
                .as("no-filter branch is catalogue-wide — all three names across both categories surface (cap=3)")
                .containsExactlyInAnyOrder("Ламінування вій №3", "Ламінування вій №4", "Макіяж №1");
    }

    @Test
    @DisplayName("GET /search/salons?category=EYELASH — serviceNames is category-scoped (salon-side parity guard)")
    void should_scopeSalonServiceNamesToFilteredCategory_when_salonHasMultiCategoryServices() throws Exception {
        ensureHttpClient();
        // ONE salon, ONE master carrying services in TWO categories: 2 EYELASH, 1 MAKEUP.
        UUID salonId = seedActiveSalon("Київ", null);
        UUID masterId = seedSalonMasterFor(salonId, "Київ", "4.00");
        seedNamedSalonServiceForMaster(masterId, salonId, "Ламінування вій №3", "EYELASH", new BigDecimal("400.00"));
        seedNamedSalonServiceForMaster(masterId, salonId, "Ламінування вій №4", "EYELASH", new BigDecimal("450.00"));
        seedNamedSalonServiceForMaster(masterId, salonId, "Макіяж №1", "MAKEUP", new BigDecimal("700.00"));

        JsonNode row = singleSalonRow("Київ", "EYELASH");
        java.util.List<String> names = new java.util.ArrayList<>();
        row.path("serviceNames").forEach(n -> names.add(n.asText()));
        assertThat(names)
                .as("salon category-filtered card lists ONLY EYELASH names — exactly the two seeded")
                .containsExactlyInAnyOrder("Ламінування вій №3", "Ламінування вій №4");
        assertThat(names)
                .as("the MAKEUP name from another category must NOT leak into an EYELASH-filtered salon card")
                .doesNotContain("Макіяж №1");
    }

    // ── Phase 19.x — master priceMax (FIXED vs RANGE discriminator) ───────────

    @Test
    @DisplayName("GET /search/masters — priceMax == minEffectivePrice for a single FIXED-price master")
    void should_returnPriceMaxEqualToMin_forSingleFixedPriceMaster() throws Exception {
        ensureHttpClient();
        UUID master = seedNamedIndependentMaster("Київ", "4.50", "Fixed", "Master");
        // A single FIXED service at 300.00 → both floor and ceiling are 300.00.
        seedNamedServiceForMaster(master, "Манікюр класичний", "MANICURE", new BigDecimal("300.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode row = objectMapper.readTree(response.getBody()).path("data").path("data").get(0);
        assertThat(new BigDecimal(row.path("minEffectivePrice").asText()))
                .as("FIXED master: minEffectivePrice = 300.00")
                .isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(new BigDecimal(row.path("priceMax").asText()))
                .as("FIXED master: priceMax collapses to minEffectivePrice (MAX(base_price) = 300.00)")
                .isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    @DisplayName("GET /search/masters — priceMax > minEffectivePrice for a master carrying a RANGE service")
    void should_returnPriceMaxAboveMin_forRangeMaster() throws Exception {
        ensureHttpClient();
        UUID master = seedNamedIndependentMaster("Київ", "4.50", "Range", "Master");
        // A RANGE service 200..650 → floor 200.00, ceiling 650.00 (a genuine range).
        seedRangeServiceForMaster(master, "Авторський манікюр", "MANICURE",
                new BigDecimal("200.00"), new BigDecimal("650.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode row = objectMapper.readTree(response.getBody()).path("data").path("data").get(0);
        BigDecimal min = new BigDecimal(row.path("minEffectivePrice").asText());
        BigDecimal max = new BigDecimal(row.path("priceMax").asText());
        assertThat(min)
                .as("RANGE master: minEffectivePrice = the floor 200.00")
                .isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(max)
                .as("RANGE master: priceMax = the ceiling 650.00")
                .isEqualByComparingTo(new BigDecimal("650.00"));
        assertThat(max)
                .as("RANGE master: priceMax must strictly exceed minEffectivePrice")
                .isGreaterThan(min);
    }

    @Test
    @DisplayName("GET /search/masters — ?sort=PRICE_ASC orders by ascending minEffectivePrice")
    void should_orderByPriceAscending_when_sortPriceAsc() throws Exception {
        ensureHttpClient();
        seedMasterWithService("Київ", "4.00", new BigDecimal("500.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("100.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("300.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&sort=PRICE_ASC&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(new BigDecimal(rows.get(0).path("minEffectivePrice").asText()))
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(new BigDecimal(rows.get(2).path("minEffectivePrice").asText()))
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("GET /search/masters — min_effective_price NULL masters are excluded by a price filter")
    void should_excludeNullPriceMasters_when_priceFilterApplied() throws Exception {
        ensureHttpClient();
        seedNamedIndependentMaster("Київ", "4.00", "No", "Price");   // no services → NULL price
        seedMasterWithService("Київ", "4.00", new BigDecimal("250.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?minPrice=100&maxPrice=400&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("NULL min_effective_price fails the range predicate naturally")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("GET /search/salons — ?minPrice/?maxPrice narrows to salons whose price band overlaps the requested band")
    void should_narrowSalonsByPriceBand_when_priceFilterApplied() throws Exception {
        ensureHttpClient();
        // Salon A: band 150..200 (overlaps [250,400]? no)
        UUID salonA = seedActiveSalon("Київ", null);
        UUID masterA = seedSalonMasterFor(salonA, "Київ", "4.00");
        seedSalonServiceForMaster(masterA, salonA, "MANICURE", "FIXED",
                new BigDecimal("150.00"), null, true, true);
        // Salon B: band 300..300 (overlaps [250,400]? yes)
        UUID salonB = seedActiveSalon("Київ", null);
        UUID masterB = seedSalonMasterFor(salonB, "Київ", "4.00");
        seedSalonServiceForMaster(masterB, salonB, "MANICURE", "FIXED",
                new BigDecimal("300.00"), null, true, true);

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&minPrice=250&maxPrice=400&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("only salon B's 300 band overlaps [250,400]")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("salonId").asText()).isEqualTo(salonB.toString());
    }

    @Test
    @DisplayName("GET /search/salons — a salon with no active priced service is excluded once a price bound is supplied")
    void should_excludeUnpricedSalon_when_priceFilterApplied() throws Exception {
        ensureHttpClient();
        seedActiveSalon("Київ", null);   // no master / no services → NULL band

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ") + "&minPrice=100&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("NULL price band fails the overlap predicate once a bound is set")
                .isZero();
    }

    @Test
    @DisplayName("GET /search/salons — ?q matches the salon name case-insensitively")
    void should_matchSalonByName_caseInsensitively_when_qSupplied() throws Exception {
        ensureHttpClient();
        UUID glow = seedNamedSalon("Київ", "Glow Studio");
        seedNamedSalon("Київ", "Shine Bar");

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?q=glow&page=0&size=20", HttpMethod.GET, anonymous(), String.class);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(data.path("data").get(0).path("salonId").asText()).isEqualTo(glow.toString());
    }

    // ── Regression — salon ?q matches an OFFERED service name (not just s.name) ─
    //
    // Bug: the salon `q` predicate was `s.name ILIKE :q` only. A salon whose
    // business name did NOT contain the typed term, but which offered a service
    // named with that term, was never returned. The fix widened the predicate to
    // `s.name ILIKE :q OR EXISTS(<active master_services → masters →
    // service_definitions WHERE sdq.name ILIKE :q>)`. These tests fail against
    // the pre-fix name-only predicate and pass on the widened query.

    @Test
    @DisplayName("GET /search/salons — regression: ?q matches a salon via an OFFERED service-definition name even when the salon NAME does not contain the term (pre-fix name-only predicate returned 0)")
    void should_matchSalonByServiceName_when_salonNameDoesNotContainTerm() throws Exception {
        ensureHttpClient();
        // Salon name has NO "balayage"; its offered service does. Pre-fix this
        // salon was invisible for q=balayage (s.name ILIKE only).
        UUID match = seedNamedSalon("Київ", "Downtown Studio");
        UUID matchMaster = seedSalonMasterFor(match, "Київ", "4.00");
        seedNamedSalonServiceForMaster(matchMaster, match, "Balayage Coloring",
                "HAIRCUT", new BigDecimal("900.00"));
        // Control: a salon that matches neither by name nor by service name.
        UUID other = seedNamedSalon("Київ", "Plain Salon");
        UUID otherMaster = seedSalonMasterFor(other, "Київ", "4.00");
        seedNamedSalonServiceForMaster(otherMaster, other, "Classic Haircut",
                "HAIRCUT", new BigDecimal("300.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?q=balayage&page=0&size=20", HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("a salon offering a service named like the query is discoverable "
                        + "even though its business name does not contain the term — "
                        + "the widened q EXISTS(service-name) predicate")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("salonId").asText()).isEqualTo(match.toString());
    }

    @Test
    @DisplayName("GET /search/salons — ?q matches a salon's offered service name case-insensitively and partially (Cyrillic: service 'Манікюр', q='манік')")
    void should_matchSalonByServiceName_caseInsensitivePartialCyrillic_when_qSupplied() throws Exception {
        ensureHttpClient();
        UUID match = seedNamedSalon("Київ", "Beauty Hub");
        UUID matchMaster = seedSalonMasterFor(match, "Київ", "4.00");
        seedNamedSalonServiceForMaster(matchMaster, match, "Манікюр",
                "MANICURE", new BigDecimal("250.00"));
        seedNamedSalon("Київ", "Shine Bar");   // no matching service, no matching name

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?q=манік&page=0&size=20", HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("lower-case partial Cyrillic 'манік' must hit the offered service "
                        + "'Манікюр' via ILIKE (case-insensitive, partial, Ukrainian)")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("salonId").asText()).isEqualTo(match.toString());
    }

    @Test
    @DisplayName("GET /search/salons — ?q matches a salon via its ACTIVE salon-owned service_definition regardless of master/master_services state; an inactive def never surfaces (def is_active is the sole gate)")
    void should_matchSalonByServiceName_onActiveDefOnly_ignoringMasterLayers() throws Exception {
        ensureHttpClient();
        // Salon search resolves a salon's catalog through its owner-scoped
        // service_definitions (owner_type='SALON'); master / master_services are
        // NOT consulted. The sole gate is the def's own is_active:
        //   • active def + inactive master + inactive link → MUST surface
        //   • inactive def + active master + active link    → MUST NOT surface
        UUID active = seedSalonWithNamedService("Київ", "Active Def Salon", "Microblading",
                false, false, true);    // def active (master & link inactive — irrelevant)
        seedSalonWithNamedService("Київ", "Inactive Def Salon", "Microblading",
                true, true, false);     // def inactive → excluded

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?q=microblading&page=0&size=20", HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("only the salon with an ACTIVE owner-scoped service_definition "
                        + "surfaces via ?q; master/master_services activity is irrelevant")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("salonId").asText())
                .as("the surfaced salon is the active-def one")
                .isEqualTo(active.toString());
    }

    // ── Item A — server-side location filtering proof ────────────────────────

    @Test
    @DisplayName("GET /search/masters — location.cityId narrows masters to that city and excludes others (server-side filter proof)")
    void should_narrowMastersByCity_andExcludeOtherCities() throws Exception {
        ensureHttpClient();
        UUID inKyiv = seedNamedIndependentMaster("Київ", "4.50", "Kyiv", "Master");
        seedNamedIndependentMaster("Львів", "4.90", "Lviv", "Master");

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("server-side city FK filter narrows to Київ only")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(inKyiv.toString());
    }

    // ── QA additions — name-search behavioural gaps (Cyrillic, first-name, literal escape) ─

    @Test
    @DisplayName("GET /search/masters — ?q matches the master's FIRST name case-insensitively (Cyrillic фолд: q=олена matches 'Олена')")
    void should_matchMasterByFirstName_caseInsensitively_cyrillic_when_qSupplied() throws Exception {
        ensureHttpClient();
        UUID match = seedNamedIndependentMaster("Київ", "4.50", "Олена", "Коваленко");
        seedNamedIndependentMaster("Київ", "4.50", "Іван", "Петренко");

        // Lower-case Cyrillic query must still hit the capitalised first name
        // "Олена" — proves ILIKE case-folds Ukrainian letters, not just ASCII.
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?q=олена&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("Cyrillic ILIKE must case-fold: q=олена matches first name 'Олена'")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(match.toString());
    }

    @Test
    @DisplayName("GET /search/masters — a literal '%' in ?q is escaped (matches a literal percent, NOT used as a wildcard)")
    void should_treatPercentInQ_asLiteral_notWildcard() throws Exception {
        ensureHttpClient();
        // One master whose last name literally contains '%', one that does not.
        UUID literal = seedNamedIndependentMaster("Київ", "4.50", "Anna", "50%off");
        seedNamedIndependentMaster("Київ", "4.50", "Boris", "Plainname");

        // q="%off" — if '%' were treated as a SQL wildcard, the pattern would be
        // %%off% and match anything ending in "off"; escaped, it matches only the
        // literal "%off" substring, so exactly the one master is returned.
        // Build a concrete URI (not a String template) so RestTemplate does not
        // re-encode the already-encoded '%25' into '%2525' (double-encoding would
        // deliver a literal "%25off" to the server and match nothing).
        ResponseEntity<String> response = restTemplate.exchange(
                rawUri(MASTERS_URL + "?q=" + enc("%off") + "&page=0&size=20"),
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("escaped '%' matches a literal percent — only the '50%off' master, not every '...off'")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(literal.toString());
    }

    @Test
    @DisplayName("GET /search/masters — a literal '_' in ?q is escaped (single-underscore is not the any-char wildcard)")
    void should_treatUnderscoreInQ_asLiteral_notWildcard() throws Exception {
        ensureHttpClient();
        UUID literal = seedNamedIndependentMaster("Київ", "4.50", "Anna", "a_b");
        // "axb" would be matched by an UNescaped '_' wildcard (a<any>b) — it must NOT match.
        seedNamedIndependentMaster("Київ", "4.50", "Boris", "axb");

        ResponseEntity<String> response = restTemplate.exchange(
                rawUri(MASTERS_URL + "?q=" + enc("a_b") + "&page=0&size=20"),
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("escaped '_' matches a literal underscore — 'a_b' only, never 'axb' via wildcard")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(literal.toString());
    }

    @Test
    @DisplayName("GET /search/salons — a literal '%' in ?q is escaped against the salon name (not a wildcard)")
    void should_treatPercentInSalonQ_asLiteral_notWildcard() throws Exception {
        ensureHttpClient();
        UUID literal = seedNamedSalon("Київ", "Glow 50%");
        seedNamedSalon("Київ", "Shine Bar 50 off");

        ResponseEntity<String> response = restTemplate.exchange(
                rawUri(SALONS_URL + "?q=" + enc("50%") + "&page=0&size=20"),
                HttpMethod.GET, anonymous(), String.class);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("salon ?q '%' is a literal — only 'Glow 50%' matches")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("salonId").asText()).isEqualTo(literal.toString());
    }

    // ── QA additions — serviceNames DISTINCT + cap-at-3 ──────────────────────

    @Test
    @DisplayName("GET /search/masters — serviceNames is DISTINCT and capped at 3 even when the master has 5 distinct active services")
    void should_capServiceNamesAtThree_andDeduplicate_when_masterHasManyServices() throws Exception {
        ensureHttpClient();
        UUID master = seedNamedIndependentMaster("Київ", "4.50", "Many", "Services");
        // Five DISTINCT names + one duplicate of the first → 5 distinct, 6 rows.
        seedNamedServiceForMaster(master, "Манікюр класичний", "MANICURE", new BigDecimal("200.00"));
        seedNamedServiceForMaster(master, "Педикюр SPA", "PEDICURE", new BigDecimal("300.00"));
        seedNamedServiceForMaster(master, "Стрижка", "HAIRCUT", new BigDecimal("400.00"));
        seedNamedServiceForMaster(master, "Фарбування", "HAIRCUT", new BigDecimal("500.00"));
        seedNamedServiceForMaster(master, "Брови", "BROWS", new BigDecimal("150.00"));
        seedNamedServiceForMaster(master, "Манікюр класичний", "MANICURE", new BigDecimal("210.00")); // duplicate name

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode row = objectMapper.readTree(response.getBody()).path("data").path("data").get(0);
        JsonNode names = row.path("serviceNames");
        assertThat(names.isArray()).isTrue();
        java.util.List<String> values = new java.util.ArrayList<>();
        names.forEach(n -> values.add(n.asText()));
        // Cap: at most 3 names surface. DISTINCT: no value repeats. Membership:
        // every surfaced name is one of the seeded distinct names (ordering is a
        // deterministic array_agg(... ORDER BY sd.name) slice, but we assert the
        // observable contract — size<=3 + distinct + subset — which survives the
        // post-LIMIT-lateral perf refactor).
        assertThat(values)
                .as("serviceNames is capped at SERVICE_NAME_CAP=3")
                .hasSize(3);
        assertThat(values)
                .as("serviceNames is DISTINCT — no duplicate display string")
                .doesNotHaveDuplicates();
        assertThat(values)
                .as("each surfaced name is one of the master's seeded distinct service names")
                .isSubsetOf("Брови", "Манікюр класичний", "Педикюр SPA", "Стрижка", "Фарбування");
    }

    // ── QA additions — master sort ordering for every enum value ─────────────

    @Test
    @DisplayName("GET /search/masters — ?sort=PRICE_DESC orders by descending minEffectivePrice")
    void should_orderByPriceDescending_when_sortPriceDesc() throws Exception {
        ensureHttpClient();
        seedMasterWithService("Київ", "4.00", new BigDecimal("100.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("500.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("300.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&sort=PRICE_DESC&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(new BigDecimal(rows.get(0).path("minEffectivePrice").asText()))
                .as("PRICE_DESC: highest price first")
                .isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(new BigDecimal(rows.get(2).path("minEffectivePrice").asText()))
                .as("PRICE_DESC: lowest price last")
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("GET /search/masters — ?sort=REVIEWS_DESC orders by descending review_count")
    void should_orderByReviewsDescending_when_sortReviewsDesc() throws Exception {
        ensureHttpClient();
        seedMasterWithReviewCount("Київ", "4.00", 2);
        seedMasterWithReviewCount("Київ", "4.00", 50);
        seedMasterWithReviewCount("Київ", "4.00", 17);

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&sort=REVIEWS_DESC&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.get(0).path("reviewCount").asInt())
                .as("REVIEWS_DESC: most-reviewed first")
                .isEqualTo(50);
        assertThat(rows.get(2).path("reviewCount").asInt())
                .as("REVIEWS_DESC: least-reviewed last")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("GET /search/masters — default sort (no ?sort) orders by descending avg_rating")
    void should_orderByRatingDescending_when_sortOmitted() throws Exception {
        ensureHttpClient();
        seedMasterWithCity("Київ", "3.80");
        seedMasterWithCity("Київ", "4.90");
        seedMasterWithCity("Київ", "4.20");

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.get(0).path("avgRating").asDouble())
                .as("RATING_DESC default: highest rating first")
                .isEqualTo(4.90);
        assertThat(rows.get(2).path("avgRating").asDouble())
                .as("RATING_DESC default: lowest rating last")
                .isEqualTo(3.80);
    }

    // ── QA additions — salon sort (PRICE_ASC / PRICE_DESC by band) ───────────

    @Test
    @DisplayName("GET /search/salons — ?sort=PRICE_ASC orders salons by ascending priceMin band floor")
    void should_orderSalonsByPriceAscending_when_sortPriceAsc() throws Exception {
        ensureHttpClient();
        UUID cheap = seedSalonWithFixedService("Київ", new BigDecimal("100.00"));
        UUID mid = seedSalonWithFixedService("Київ", new BigDecimal("300.00"));
        UUID dear = seedSalonWithFixedService("Київ", new BigDecimal("500.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ") + "&sort=PRICE_ASC&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.size()).isEqualTo(3);
        assertThat(rows.get(0).path("salonId").asText())
                .as("PRICE_ASC: cheapest band floor first")
                .isEqualTo(cheap.toString());
        assertThat(rows.get(2).path("salonId").asText())
                .as("PRICE_ASC: dearest band floor last")
                .isEqualTo(dear.toString());
        // mid sits between — pin it so a stable, total ordering is proven.
        assertThat(rows.get(1).path("salonId").asText()).isEqualTo(mid.toString());
    }

    @Test
    @DisplayName("GET /search/salons — ?sort=PRICE_DESC orders salons by descending priceMax band ceiling")
    void should_orderSalonsByPriceDescending_when_sortPriceDesc() throws Exception {
        ensureHttpClient();
        UUID cheap = seedSalonWithFixedService("Київ", new BigDecimal("100.00"));
        UUID dear = seedSalonWithFixedService("Київ", new BigDecimal("500.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ") + "&sort=PRICE_DESC&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.get(0).path("salonId").asText())
                .as("PRICE_DESC: dearest band ceiling first")
                .isEqualTo(dear.toString());
        assertThat(rows.get(rows.size() - 1).path("salonId").asText())
                .as("PRICE_DESC: cheapest band ceiling last")
                .isEqualTo(cheap.toString());
    }

    // ── QA additions — short ?q normalize-to-null (coordinate with perf fix) ──

    @Test
    @DisplayName("GET /search/masters — a 1-2 char ?q normalises to null and returns the full location-scoped result set (not an ILIKE on the short term)")
    void should_normalizeShortQToNull_andReturnLocationScopedResults() throws Exception {
        ensureHttpClient();
        // Master in Київ whose name does NOT contain the short term "zz".
        UUID inKyiv = seedNamedIndependentMaster("Київ", "4.50", "Olena", "Kovalenko");

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&q=zz&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("short q is normalised to null → the city-scoped master is returned despite its name not containing 'zz'")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(inKyiv.toString());
    }

    // ── Phase 20.1 — master per-service filter (hybrid FK-or-name, AND) ──────

    @Test
    @DisplayName("GET /search/masters — FK-only match: a master whose service has service_type_id set (NAME does NOT contain the type name) is returned for that slug")
    void should_returnMaster_when_serviceMatchesByServiceTypeIdFk() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        // Match purely via the FK — the service name deliberately omits "Кератин".
        UUID fkMaster = seedNamedIndependentMaster("Київ", "4.50", "Fk", "Master");
        seedTypedServiceForMaster(fkMaster, "Догляд за волоссям", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);
        // Control: a master with a service that matches NEITHER the FK nor the name.
        // It WOULD be returned without the filter — proving the predicate narrows.
        UUID other = seedNamedIndependentMaster("Київ", "4.50", "Other", "Master");
        seedTypedServiceForMaster(other, "Манікюр класичний", "MANICURE",
                new BigDecimal("250.00"), null, true, true);

        JsonNode data = masterSearch("?serviceTypeSlugs=" + SLUG_A + "&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("FK branch matches: only the master whose service_type_id = keratin is returned")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(fkMaster.toString());
    }

    @Test
    @DisplayName("GET /search/masters — name-only match (legacy recovery): a master whose service has NULL service_type_id but a NAME matching name_uk is returned")
    void should_returnMaster_when_serviceMatchesByNameOnly_withNullFk() throws Exception {
        ensureHttpClient();
        // FK is NULL (legacy / single-create row) — recovery is via the name branch.
        UUID nameMaster = seedNamedIndependentMaster("Київ", "4.50", "Name", "Master");
        seedTypedServiceForMaster(nameMaster, NAME_UK_A + " преміум", "HAIRCUT",
                new BigDecimal("300.00"), null, true, true);
        UUID other = seedNamedIndependentMaster("Київ", "4.50", "Other", "Master");
        seedTypedServiceForMaster(other, "Педикюр SPA", "PEDICURE",
                new BigDecimal("250.00"), null, true, true);

        JsonNode data = masterSearch("?serviceTypeSlugs=" + SLUG_A + "&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("name branch recovers a NULL-FK row whose name contains the resolved name_uk")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(nameMaster.toString());
    }

    @Test
    @DisplayName("GET /search/masters — both branches: one master matched via FK, another via NAME for the same slug — BOTH returned")
    void should_returnBothMasters_when_oneMatchesByFk_andOneByName() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        UUID fkMaster = seedNamedIndependentMaster("Київ", "4.50", "Fk", "Master");
        seedTypedServiceForMaster(fkMaster, "Догляд за волоссям", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);   // FK only (no name match)
        UUID nameMaster = seedNamedIndependentMaster("Київ", "4.50", "Name", "Master");
        seedTypedServiceForMaster(nameMaster, NAME_UK_A + " класичний", "HAIRCUT",
                new BigDecimal("320.00"), null, true, true);      // name only (NULL FK)

        JsonNode data = masterSearch("?serviceTypeSlugs=" + SLUG_A + "&page=0&size=20");
        assertThat(data.path("totalElements").asLong()).isEqualTo(2L);
        assertThat(masterIds(data))
                .as("the hybrid OR matches FK rows AND legacy name rows for the same slug")
                .containsExactlyInAnyOrder(fkMaster.toString(), nameMaster.toString());
    }

    @Test
    @DisplayName("GET /search/masters — OR/union of two slugs: master offering BOTH returned AND master offering only one also returned")
    void should_returnMastersOfferingAnySlug_when_unionOfTwoSlugs() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        UUID typeIdB = serviceTypeIdBySlug(SLUG_B);
        // Master A offers BOTH service types.
        UUID both = seedNamedIndependentMaster("Київ", "4.50", "Both", "Master");
        seedTypedServiceForMaster(both, "Послуга A1", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);
        seedTypedServiceForMaster(both, "Послуга A2", "INJECTION",
                new BigDecimal("400.00"), typeIdB, true, true);
        // Master B offers only the first — under OR/union it is ALSO returned.
        UUID one = seedNamedIndependentMaster("Київ", "4.50", "One", "Master");
        seedTypedServiceForMaster(one, "Послуга B1", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);

        JsonNode data = masterSearch(
                "?serviceTypeSlugs=" + SLUG_A + "&serviceTypeSlugs=" + SLUG_B + "&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("OR/union semantics: a master matching ANY selected slug is returned (both the both-offering and the one-offering master)")
                .isEqualTo(2L);
        assertThat(masterIds(data))
                .as("both the master offering both slugs and the master offering only one are returned")
                .containsExactlyInAnyOrder(both.toString(), one.toString());
    }

    @Test
    @DisplayName("GET /search/masters — all-unknown: the only selected slug is unresolvable → explicit empty page (NOT unfiltered everything)")
    void should_returnEmpty_when_allSelectedSlugsUnresolvable() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        UUID master = seedNamedIndependentMaster("Київ", "4.50", "Real", "Master");
        seedTypedServiceForMaster(master, "Догляд за волоссям", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);

        // Pattern-valid but absent from the taxonomy. Under OR/union resolveServiceTypes
        // drops it; ALL slugs unknown → Optional.empty() → explicit empty page. It must
        // NOT fall through to the unfiltered "everything" path (the seeded master proves
        // an unfiltered query would return >= 1 row).
        JsonNode data = masterSearch("?serviceTypeSlugs=nonexistent-slug-xyz&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("every selected slug unknown → empty page, not a 500 and not unfiltered everything")
                .isZero();
        assertThat(data.path("data").size()).isZero();
    }

    @Test
    @DisplayName("GET /search/masters — mix valid + unknown: the unknown slug is dropped; masters offering the VALID slug are returned")
    void should_returnMastersOfferingValidSlug_when_mixOfValidAndUnknownSlugs() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        // Master offers the valid slug A — must survive once the unknown slug is dropped.
        UUID valid = seedNamedIndependentMaster("Київ", "4.50", "Valid", "Master");
        seedTypedServiceForMaster(valid, "Догляд за волоссям", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);
        // Control: matches NEITHER the valid slug FK nor its name → excluded (proves the
        // surviving slug still narrows; the unknown slug neither errors nor widens).
        UUID other = seedNamedIndependentMaster("Київ", "4.50", "Other", "Master");
        seedTypedServiceForMaster(other, "Манікюр класичний", "MANICURE",
                new BigDecimal("250.00"), null, true, true);

        JsonNode data = masterSearch("?serviceTypeSlugs=" + SLUG_A
                + "&serviceTypeSlugs=nonexistent-slug-xyz&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("unknown slug dropped; the valid-slug disjunction still matches the offering master only")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(valid.toString());
    }

    @Test
    @DisplayName("GET /search/masters — matchedServiceNames present (distinct, capped at 3) when filtering; EMPTY when no serviceTypeSlugs")
    void should_populateMatchedServiceNames_whenFiltering_andEmptyOtherwise() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        UUID master = seedNamedIndependentMaster("Київ", "4.50", "Matched", "Master");
        // Four DISTINCT matching service names (all via FK A) → cap to 3, distinct.
        seedTypedServiceForMaster(master, "Догляд Альфа", "HAIRCUT", new BigDecimal("300.00"), typeIdA, true, true);
        seedTypedServiceForMaster(master, "Догляд Бета", "HAIRCUT", new BigDecimal("310.00"), typeIdA, true, true);
        seedTypedServiceForMaster(master, "Догляд Гамма", "HAIRCUT", new BigDecimal("320.00"), typeIdA, true, true);
        seedTypedServiceForMaster(master, "Догляд Дельта", "HAIRCUT", new BigDecimal("330.00"), typeIdA, true, true);

        // Filtering → matchedServiceNames populated.
        JsonNode filtered = masterSearch("?serviceTypeSlugs=" + SLUG_A + "&page=0&size=20");
        JsonNode matched = filtered.path("data").get(0).path("matchedServiceNames");
        assertThat(matched.isArray()).as("matchedServiceNames is a JSON array").isTrue();
        java.util.List<String> matchedNames = new java.util.ArrayList<>();
        matched.forEach(n -> matchedNames.add(n.asText()));
        assertThat(matchedNames)
                .as("matchedServiceNames is capped at SERVICE_NAME_CAP=3 and distinct")
                .hasSize(3)
                .doesNotHaveDuplicates()
                .isSubsetOf("Догляд Альфа", "Догляд Бета", "Догляд Гамма", "Догляд Дельта");

        // No serviceTypeSlugs → matchedServiceNames empty (card falls back to serviceNames).
        JsonNode unfiltered = masterSearch("?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20");
        assertThat(unfiltered.path("data").get(0).path("matchedServiceNames").size())
                .as("matchedServiceNames is empty when no service filter is active")
                .isZero();
    }

    @Test
    @DisplayName("GET /search/masters — inactive exclusion: a master matched only via an inactive master_service or inactive service_definition is NOT returned")
    void should_excludeMaster_when_onlyInactiveLayerMatchesSlug() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        // Master 1: service_definition inactive (FK matches but def is_active = false).
        UUID inactiveDef = seedNamedIndependentMaster("Київ", "4.50", "InactiveDef", "Master");
        seedTypedServiceForMaster(inactiveDef, "Догляд за волоссям", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, false, true);
        // Master 2: master_services link inactive (FK matches but link is_active = false).
        UUID inactiveLink = seedNamedIndependentMaster("Київ", "4.50", "InactiveLink", "Master");
        seedTypedServiceForMaster(inactiveLink, "Догляд за волоссям", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, false);

        JsonNode data = masterSearch("?serviceTypeSlugs=" + SLUG_A + "&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("the per-slug EXISTS requires master_services.is_active AND service_definitions.is_active")
                .isZero();
    }

    @Test
    @DisplayName("GET /search/masters — composition: serviceTypeSlugs + category + price together narrow to the single fully-matching master")
    void should_narrowByServiceTypeSlugsCategoryAndPrice_when_combined() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        // m1 — matches every predicate.
        UUID m1 = seedNamedIndependentMaster("Київ", "4.50", "Full", "Match");
        seedTypedServiceForMaster(m1, "Догляд за волоссям", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);
        // m2 — right slug + price, WRONG category (MANICURE) → fails category EXISTS.
        UUID m2 = seedNamedIndependentMaster("Київ", "4.50", "Wrong", "Category");
        seedTypedServiceForMaster(m2, "Догляд інший", "MANICURE",
                new BigDecimal("300.00"), typeIdA, true, true);
        // m3 — right slug + category, price 50 BELOW the floor → fails price.
        UUID m3 = seedNamedIndependentMaster("Київ", "4.50", "Too", "Cheap");
        seedTypedServiceForMaster(m3, "Догляд дешевий", "HAIRCUT",
                new BigDecimal("50.00"), typeIdA, true, true);
        // m4 — right category + price, NO slug match (NULL FK, neutral name).
        UUID m4 = seedNamedIndependentMaster("Київ", "4.50", "No", "Slug");
        seedTypedServiceForMaster(m4, "Стрижка проста", "HAIRCUT",
                new BigDecimal("300.00"), null, true, true);

        JsonNode data = masterSearch("?serviceTypeSlugs=" + SLUG_A
                + "&category=HAIRCUT&minPrice=100.00&maxPrice=500.00&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("only the master matching slug AND category AND price survives all three predicates")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(m1.toString());
    }

    // ── Regression — per-slug scoped price band (FIXED vs RANGE) ─────────────
    //
    // Bug: search returned the master's WHOLE-CATALOGUE price band instead of the
    // band scoped to the active serviceTypeSlugs filter. A master with a FIXED 500
    // service on slug A and a RANGE 800..4000 service on slug B returned
    // minEffectivePrice=500 / priceMax=4000 for BOTH slug filters (and for A the
    // floor leaked the 500 FIXED while the ceiling leaked the 4000 RANGE).
    //
    // This test FAILS against the old whole-catalogue logic (slug A would project
    // priceMax=4000, slug B would project minEffectivePrice=500) and PASSES against
    // the SearchService fix, where the `sn` price lateral computes a slug-scoped
    // MIN + MAX and minEffectivePrice sources from sn.price_min when a filter is
    // active. No existing price test exercised a master holding BOTH a FIXED and a
    // RANGE service under DIFFERENT slugs, so the suite missed it.

    @Test
    @DisplayName("GET /search/masters?serviceTypeSlugs=… — regression: the displayed price band is scoped to the matched service-type — a master with a FIXED 500 on slug A and a RANGE 800..4000 on slug B shows 500/500 for A, 800/4000 for B, and the 500/4000 whole-catalogue band only when unfiltered")
    void should_scopeMasterPriceBand_toActiveServiceTypeSlug_acrossFixedAndRange() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        UUID typeIdB = serviceTypeIdBySlug(SLUG_B);
        UUID master = seedNamedIndependentMaster("Київ", "4.50", "Scoped", "Band");
        // Slug A: FIXED 500 (floor == ceiling == 500). Neutral name → FK-only match,
        // never the other slug's name_uk.
        seedTypedServiceForMaster(master, "Послуга Фікс A", "HAIRCUT",
                "FIXED", new BigDecimal("500.00"), null, typeIdA, true, true);
        // Slug B: RANGE 800..4000 (floor 800, ceiling 4000).
        seedTypedServiceForMaster(master, "Послуга Діапазон B", "INJECTION",
                "RANGE", new BigDecimal("800.00"), new BigDecimal("4000.00"), typeIdB, true, true);

        // ── slug A → the FIXED service's single price: 500 / 500 ─────────────
        JsonNode aRow = masterSearch("?serviceTypeSlugs=" + SLUG_A + "&page=0&size=20")
                .path("data").get(0);
        assertThat(new BigDecimal(aRow.path("minEffectivePrice").asText()))
                .as("slug A floor = the matched FIXED 500 (pre-fix this was the catalogue MIN, also 500 — but the ceiling leaked)")
                .isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(new BigDecimal(aRow.path("priceMax").asText()))
                .as("slug A ceiling = the matched FIXED 500, NOT the off-slug RANGE 4000 (the bug)")
                .isEqualByComparingTo(new BigDecimal("500.00"));

        // ── slug B → the RANGE service's band: 800 / 4000 ────────────────────
        JsonNode bRow = masterSearch("?serviceTypeSlugs=" + SLUG_B + "&page=0&size=20")
                .path("data").get(0);
        assertThat(new BigDecimal(bRow.path("minEffectivePrice").asText()))
                .as("slug B floor = the matched RANGE floor 800, NOT the off-slug FIXED 500 (the bug)")
                .isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(new BigDecimal(bRow.path("priceMax").asText()))
                .as("slug B ceiling = the matched RANGE ceiling 4000")
                .isEqualByComparingTo(new BigDecimal("4000.00"));

        // ── no filter → whole-catalogue band 500 / 4000 (preserved) ──────────
        JsonNode allRow = masterSearch("?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20")
                .path("data").get(0);
        assertThat(new BigDecimal(allRow.path("minEffectivePrice").asText()))
                .as("unfiltered floor = catalogue MIN(500, 800) = 500 (whole-catalogue behaviour preserved)")
                .isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(new BigDecimal(allRow.path("priceMax").asText()))
                .as("unfiltered ceiling = catalogue MAX(FIXED 500, RANGE 4000) = 4000")
                .isEqualByComparingTo(new BigDecimal("4000.00"));
    }

    // ── Phase 20.2/20.3 — salon per-service filter ───────────────────────────

    @Test
    @DisplayName("GET /search/salons — a salon whose active master offers a matching service (FK or name) is returned")
    void should_returnSalon_when_activeMasterOffersMatchingService() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        // Salon matched via FK (name omits "Кератин").
        UUID salonFk = seedActiveSalon("Київ", null);
        UUID masterFk = seedSalonMasterFor(salonFk, "Київ", "4.00");
        seedTypedSalonServiceForMaster(masterFk, salonFk, "Догляд за волоссям", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);
        // Salon matched via name only (NULL FK).
        UUID salonName = seedActiveSalon("Київ", null);
        UUID masterName = seedSalonMasterFor(salonName, "Київ", "4.00");
        seedTypedSalonServiceForMaster(masterName, salonName, NAME_UK_A + " Lux", "HAIRCUT",
                new BigDecimal("320.00"), null, true, true);
        // Control salon offering nothing matching — would appear without the filter.
        UUID other = seedActiveSalon("Київ", null);
        UUID otherMaster = seedSalonMasterFor(other, "Київ", "4.00");
        seedTypedSalonServiceForMaster(otherMaster, other, "Манікюр", "MANICURE",
                new BigDecimal("250.00"), null, true, true);

        JsonNode data = salonSearch("?serviceTypeSlugs=" + SLUG_A + "&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("salon filter matches via the master's service FK OR name; the control salon is excluded")
                .isEqualTo(2L);
        assertThat(salonIds(data)).containsExactlyInAnyOrder(salonFk.toString(), salonName.toString());
    }

    @Test
    @DisplayName("GET /search/salons — OR/union of two slugs at salon granularity: salon offering BOTH AND salon offering only one are both returned")
    void should_returnSalonsOfferingAnySlug_when_unionOfTwoSlugs() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        UUID typeIdB = serviceTypeIdBySlug(SLUG_B);
        UUID salonBoth = seedActiveSalon("Київ", null);
        UUID masterBoth = seedSalonMasterFor(salonBoth, "Київ", "4.00");
        seedTypedSalonServiceForMaster(masterBoth, salonBoth, "Послуга A1", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);
        seedTypedSalonServiceForMaster(masterBoth, salonBoth, "Послуга A2", "INJECTION",
                new BigDecimal("400.00"), typeIdB, true, true);
        UUID salonOne = seedActiveSalon("Київ", null);
        UUID masterOne = seedSalonMasterFor(salonOne, "Київ", "4.00");
        seedTypedSalonServiceForMaster(masterOne, salonOne, "Послуга B1", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);

        JsonNode data = salonSearch(
                "?serviceTypeSlugs=" + SLUG_A + "&serviceTypeSlugs=" + SLUG_B + "&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("OR/union at salon granularity: a salon offering a service for ANY selected slug is returned")
                .isEqualTo(2L);
        assertThat(salonIds(data))
                .as("both the salon offering both slugs and the salon offering only one are returned")
                .containsExactlyInAnyOrder(salonBoth.toString(), salonOne.toString());
    }

    @Test
    @DisplayName("GET /search/salons — mix valid + unknown: the unknown slug is dropped; salons offering the VALID slug are returned")
    void should_returnSalonsOfferingValidSlug_when_mixOfValidAndUnknownSlugs() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        // Salon whose active master offers the valid slug A — survives after the drop.
        UUID validSalon = seedActiveSalon("Київ", null);
        UUID validMaster = seedSalonMasterFor(validSalon, "Київ", "4.00");
        seedTypedSalonServiceForMaster(validMaster, validSalon, "Догляд за волоссям", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);
        // Control salon offering nothing matching the valid slug → excluded.
        UUID other = seedActiveSalon("Київ", null);
        UUID otherMaster = seedSalonMasterFor(other, "Київ", "4.00");
        seedTypedSalonServiceForMaster(otherMaster, other, "Манікюр", "MANICURE",
                new BigDecimal("250.00"), null, true, true);

        JsonNode data = salonSearch("?serviceTypeSlugs=" + SLUG_A
                + "&serviceTypeSlugs=nonexistent-slug-xyz&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("unknown slug dropped; the valid-slug disjunction still matches the offering salon only")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("salonId").asText()).isEqualTo(validSalon.toString());
    }

    @Test
    @DisplayName("GET /search/salons — all-unknown: the only selected slug is unresolvable → explicit empty page (NOT unfiltered everything)")
    void should_returnEmpty_when_allSelectedSalonSlugsUnresolvable() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        // A real, matching salon exists — an unfiltered query would return it, proving
        // the empty result is the explicit empty page, not "no rows seeded".
        UUID salon = seedActiveSalon("Київ", null);
        UUID master = seedSalonMasterFor(salon, "Київ", "4.00");
        seedTypedSalonServiceForMaster(master, salon, "Догляд за волоссям", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);

        JsonNode data = salonSearch("?serviceTypeSlugs=nonexistent-slug-xyz&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("every selected slug unknown → empty page, not unfiltered everything")
                .isZero();
        assertThat(data.path("data").size()).isZero();
    }

    @Test
    @DisplayName("GET /search/salons — per-slug EXISTS surfaces a salon via its ACTIVE salon-owned service_definition regardless of master/master_services state; an inactive def is excluded (def is_active is the sole gate)")
    void should_surfaceSalonBySlug_onActiveDefOnly_ignoringMasterLayers() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        // Active def, but master inactive + link inactive → MUST surface (salon
        // search reads owner-scoped defs directly; master/master_services are not
        // consulted — only the def's own is_active gates).
        UUID salonActiveDef = seedActiveSalon("Київ", null);
        UUID inactiveMaster = seedSalonMasterFor(salonActiveDef, "Київ", "4.00");
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", inactiveMaster);
        seedTypedSalonServiceForMaster(inactiveMaster, salonActiveDef, "Догляд за волоссям",
                "HAIRCUT", new BigDecimal("300.00"), typeIdA, true, false); // defActive=true, msActive=false
        // Inactive def, master active + link active → MUST NOT surface.
        UUID salonInactiveDef = seedActiveSalon("Київ", null);
        UUID activeMaster = seedSalonMasterFor(salonInactiveDef, "Київ", "4.00");
        seedTypedSalonServiceForMaster(activeMaster, salonInactiveDef, "Догляд за волоссям",
                "HAIRCUT", new BigDecimal("300.00"), typeIdA, false, true); // defActive=false

        JsonNode data = salonSearch("?serviceTypeSlugs=" + SLUG_A + "&page=0&size=20");
        assertThat(data.path("totalElements").asLong())
                .as("only the salon with an ACTIVE owner-scoped service_definition "
                        + "surfaces via the per-slug EXISTS; master/master_services "
                        + "activity is irrelevant")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("salonId").asText())
                .isEqualTo(salonActiveDef.toString());
    }

    @Test
    @DisplayName("GET /search/salons — matchedServiceNames is aggregated across the salon's masters when filtering, and empty when unfiltered")
    void should_aggregateSalonMatchedServiceNames_acrossMasters_whenFiltering() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        UUID salon = seedActiveSalon("Київ", null);
        UUID master1 = seedSalonMasterFor(salon, "Київ", "4.00");
        UUID master2 = seedSalonMasterFor(salon, "Київ", "4.00");
        seedTypedSalonServiceForMaster(master1, salon, "Догляд М1", "HAIRCUT",
                new BigDecimal("300.00"), typeIdA, true, true);
        seedTypedSalonServiceForMaster(master2, salon, "Догляд М2", "HAIRCUT",
                new BigDecimal("310.00"), typeIdA, true, true);

        JsonNode filtered = salonSearch("?serviceTypeSlugs=" + SLUG_A + "&page=0&size=20");
        assertThat(filtered.path("totalElements").asLong()).isEqualTo(1L);
        JsonNode matched = filtered.path("data").get(0).path("matchedServiceNames");
        java.util.List<String> matchedNames = new java.util.ArrayList<>();
        matched.forEach(n -> matchedNames.add(n.asText()));
        assertThat(matchedNames)
                .as("matched names span BOTH masters of the salon, distinct and capped at 3")
                .containsExactlyInAnyOrder("Догляд М1", "Догляд М2");

        JsonNode unfiltered = salonSearch("?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20");
        assertThat(unfiltered.path("data").get(0).path("matchedServiceNames").size())
                .as("matchedServiceNames is empty when no service filter is active")
                .isZero();
    }

    @Test
    @DisplayName("GET /search/salons?serviceTypeSlugs=… — regression (salon analog): the salon price band is scoped to the matched service-type — a salon owning a FIXED 500 on slug A and a RANGE 800..4000 on slug B shows priceMin/priceMax 500/500 for A and 800/4000 for B (pre-fix the slug-filtered band leaked the whole-catalogue 500/4000)")
    void should_scopeSalonPriceBand_toActiveServiceTypeSlug_acrossFixedAndRange() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        UUID typeIdB = serviceTypeIdBySlug(SLUG_B);
        // ONE salon owning two ACTIVE owner-scoped defs under two distinct slugs:
        // a FIXED 500 (slug A) and a RANGE 800..4000 (slug B). The appendSalonPrice
        // AggregateLateral got the same slug-scoping fix as the master path.
        UUID salon = seedActiveSalon("Київ", null);
        seedSalonOwnedServiceNoMaster(salon, "Салон Фікс A", "HAIRCUT",
                "FIXED", new BigDecimal("500.00"), null, typeIdA, true);
        seedSalonOwnedServiceNoMaster(salon, "Салон Діапазон B", "INJECTION",
                "RANGE", new BigDecimal("800.00"), new BigDecimal("4000.00"), typeIdB, true);

        // ── slug A → the FIXED service band: 500 / 500 ───────────────────────
        JsonNode aRow = salonSearch("?serviceTypeSlugs=" + SLUG_A + "&page=0&size=20")
                .path("data").get(0);
        assertThat(new BigDecimal(aRow.path("priceMin").asText()))
                .as("slug A salon floor = the matched FIXED 500")
                .isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(new BigDecimal(aRow.path("priceMax").asText()))
                .as("slug A salon ceiling = the matched FIXED 500, NOT the off-slug RANGE 4000 (the bug)")
                .isEqualByComparingTo(new BigDecimal("500.00"));

        // ── slug B → the RANGE service band: 800 / 4000 ──────────────────────
        JsonNode bRow = salonSearch("?serviceTypeSlugs=" + SLUG_B + "&page=0&size=20")
                .path("data").get(0);
        assertThat(new BigDecimal(bRow.path("priceMin").asText()))
                .as("slug B salon floor = the matched RANGE floor 800, NOT the off-slug FIXED 500 (the bug)")
                .isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(new BigDecimal(bRow.path("priceMax").asText()))
                .as("slug B salon ceiling = the matched RANGE ceiling 4000")
                .isEqualByComparingTo(new BigDecimal("4000.00"));
    }

    // ── Regression — salon CATEGORY-only filter excludes foreign-category salons ─
    //
    // Bug: the salon category predicate lived ONLY inside the price/name preview
    // LATERAL, never on the outer WHERE. Because that LATERAL is a LEFT JOIN it
    // never removes the salon row, so a salon offering NOTHING in the selected
    // category was still returned — as an empty-name / null-price card. The fix
    // adds a null-gated salon-level `AND (CAST(:category AS text) IS NULL OR
    // EXISTS(... AND sdc.category = :category))` to the outer WHERE of all six
    // salon projection overloads AND their countQueries. These tests fail against
    // the pre-fix query (the foreign-category salon leaks in / inflates the count)
    // and pass on the gated WHERE.

    @Test
    @DisplayName("GET /search/salons?category=HAIRCUT — regression: a salon whose ONLY active services are in a DIFFERENT category is EXCLUDED (pre-fix it leaked in as an empty-name card)")
    void should_excludeSalon_when_offersNothingInFilteredCategory() throws Exception {
        ensureHttpClient();
        // Salon A — an active master offering two ACTIVE services in category HAIRCUT.
        UUID salonA = seedActiveSalon("Київ", null);
        UUID masterA = seedSalonMasterFor(salonA, "Київ", "4.00");
        seedTypedSalonServiceForMaster(masterA, salonA, "Стрижка жіноча", "HAIRCUT",
                new BigDecimal("400.00"), null, true, true);
        seedTypedSalonServiceForMaster(masterA, salonA, "Фарбування волосся", "HAIRCUT",
                new BigDecimal("900.00"), null, true, true);
        // Salon B — an active master offering ONLY a service in category MANICURE.
        // Pre-fix this salon surfaced for ?category=HAIRCUT with a null price band
        // and empty serviceNames because the category lived only inside the
        // LEFT-JOIN LATERAL, never on the salon's outer WHERE.
        UUID salonB = seedActiveSalon("Київ", null);
        UUID masterB = seedSalonMasterFor(salonB, "Київ", "4.00");
        seedTypedSalonServiceForMaster(masterB, salonB, "Манікюр класичний", "MANICURE",
                new BigDecimal("250.00"), null, true, true);

        JsonNode data = salonSearch("?location.cityId=" + cityIdByName("Київ")
                + "&category=HAIRCUT&page=0&size=20");

        assertThat(data.path("totalElements").asLong())
                .as("only salon A offers a HAIRCUT service — salon B (MANICURE-only) must be excluded by the outer-WHERE EXISTS gate")
                .isEqualTo(1L);
        assertThat(salonIds(data))
                .as("the single returned salon is salon A; salon B never leaks in as an empty-name card")
                .containsExactly(salonA.toString());

        JsonNode row = data.path("data").get(0);
        java.util.List<String> names = new java.util.ArrayList<>();
        row.path("serviceNames").forEach(n -> names.add(n.asText()));
        assertThat(names)
                .as("the category-scoped preview lists ONLY salon A's HAIRCUT names — no foreign-category leak")
                .containsExactlyInAnyOrder("Стрижка жіноча", "Фарбування волосся");
    }

    @Test
    @DisplayName("GET /search/salons (no category) — null-gate no-op: BOTH salons returned regardless of which single category each offers")
    void should_returnBothSalons_when_noCategoryFilter() throws Exception {
        ensureHttpClient();
        // Same two salons as the exclusion test — one HAIRCUT-only, one MANICURE-only.
        UUID salonA = seedActiveSalon("Київ", null);
        UUID masterA = seedSalonMasterFor(salonA, "Київ", "4.00");
        seedTypedSalonServiceForMaster(masterA, salonA, "Стрижка жіноча", "HAIRCUT",
                new BigDecimal("400.00"), null, true, true);
        UUID salonB = seedActiveSalon("Київ", null);
        UUID masterB = seedSalonMasterFor(salonB, "Київ", "4.00");
        seedTypedSalonServiceForMaster(masterB, salonB, "Манікюр класичний", "MANICURE",
                new BigDecimal("250.00"), null, true, true);

        JsonNode data = salonSearch("?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20");

        assertThat(data.path("totalElements").asLong())
                .as("with no category the EXISTS gate is short-circuited by `:category IS NULL` — both salons returned (the null-gate no-op)")
                .isEqualTo(2L);
        assertThat(salonIds(data))
                .as("both the HAIRCUT salon and the MANICURE salon are present when no category narrows the result")
                .containsExactlyInAnyOrder(salonA.toString(), salonB.toString());
    }

    @Test
    @DisplayName("GET /search/salons?category=HAIRCUT&size=2 — countQuery parity: totalElements counts ONLY category-matching salons (the foreign-category salon is absent from the count too — no phantom page)")
    void should_matchCountQueryToFilteredRows_when_categoryFilterPaginated() throws Exception {
        ensureHttpClient();
        // Three salons each offering an active HAIRCUT service ...
        UUID s1 = seedSalonOfferingCategory("Київ", "HAIRCUT", "Стрижка 1");
        UUID s2 = seedSalonOfferingCategory("Київ", "HAIRCUT", "Стрижка 2");
        UUID s3 = seedSalonOfferingCategory("Київ", "HAIRCUT", "Стрижка 3");
        // ... and one offering ONLY a MANICURE service — it must be excluded from
        // BOTH the page content AND the countQuery. A pre-fix count (EXISTS gate
        // only in the data query) would return 4 and hand an anonymous caller a
        // phantom HAIRCUT page populated by the MANICURE salon.
        UUID manicureOnly = seedSalonOfferingCategory("Київ", "MANICURE", "Манікюр класичний");

        JsonNode data = salonSearch("?location.cityId=" + cityIdByName("Київ")
                + "&category=HAIRCUT&page=0&size=2");

        assertThat(data.path("totalElements").asLong())
                .as("countQuery EXISTS-gate parity: exactly the three HAIRCUT salons are counted, not four")
                .isEqualTo(3L);
        assertThat(data.path("totalPages").asInt())
                .as("3 matching rows / size 2 → 2 pages, no phantom page from the foreign-category salon")
                .isEqualTo(2);
        assertThat(data.path("data").size())
                .as("page 0 holds exactly size=2 rows")
                .isEqualTo(2);
        assertThat(salonIds(data))
                .as("every row on the HAIRCUT page is one of the three HAIRCUT salons")
                .isSubsetOf(s1.toString(), s2.toString(), s3.toString());
        assertThat(salonIds(data))
                .as("the MANICURE-only salon must never appear in the HAIRCUT page content")
                .doesNotContain(manicureOnly.toString());
    }

    // ── Regression — salon-owned def with NO master_services row surfaces ─────
    //
    // Bug (just fixed): salon search resolved each salon's offered services via
    // `master_services → masters → service_definitions` (the salon's MASTERS'
    // assignments). But a salon's catalog is `service_definitions WHERE
    // owner_type='SALON' AND owner_id = salonId` — created via
    // `POST /salons/{id}/services` — which are NOT necessarily assigned to any
    // master. A salon whose owned def has NO `master_services` row therefore
    // matched 0 under a category filter, returned null priceMin/priceMax, and
    // an empty serviceNames card. Fixed by switching the row-source of the 6
    // SalonRepository projection queries + the 5 SearchService salon laterals to
    // `service_definitions WHERE owner_type='SALON' AND owner_id = s.id AND
    // is_active = true` (no master_services hop).
    //
    // These tests PIN that row-source: they seed an ACTIVE salon-owned def with
    // NO `master_services` row whatsoever (no salon master) — the exact gap the
    // pre-existing helpers (seedSalonServiceForMaster / seedTypedSalonServiceForMaster /
    // seedSalonWithNamedService) could never express, because they ALWAYS insert
    // a master_services link alongside the def. They FAIL against the old
    // master_services-join logic (salon invisible / null band / empty names) and
    // PASS against the owner-scoped fix.

    @Test
    @DisplayName("GET /search/salons?category=NAIL_SERVICE — regression: a salon whose ACTIVE owned def has NO master_services row STILL surfaces with the owned def's price band + category-scoped names; a different-category owned salon is excluded (pins owner-scoped row-source, not the master_services join)")
    void should_surfaceSalonByOwnedDef_when_noMasterServicesRowExists() throws Exception {
        ensureHttpClient();
        // Salon A — an active salon with NO master at all. Two ACTIVE salon-owned
        // defs created directly (as POST /salons/{id}/services would): a
        // NAIL_SERVICE RANGE 150..600 and an off-category HAIRCUT FIXED 900.
        // NO master_services row exists for either → the pre-fix master_services
        // join finds nothing and the salon vanishes from a category search.
        UUID salonA = seedActiveSalon("Київ", null);
        seedSalonOwnedServiceNoMaster(salonA, "Манікюр гель-лак", "NAIL_SERVICE",
                "RANGE", new BigDecimal("150.00"), new BigDecimal("600.00"), null, true);
        seedSalonOwnedServiceNoMaster(salonA, "Стрижка жіноча", "HAIRCUT",
                "FIXED", new BigDecimal("900.00"), null, null, true);
        // Salon B — an active salon whose ONLY active owned def is in a DIFFERENT
        // category (HAIRCUT), also with NO master_services row. It must be
        // EXCLUDED under ?category=NAIL_SERVICE (negative guard).
        UUID salonB = seedActiveSalon("Київ", null);
        seedSalonOwnedServiceNoMaster(salonB, "Фарбування волосся", "HAIRCUT",
                "FIXED", new BigDecimal("700.00"), null, null, true);

        JsonNode data = salonSearch("?location.cityId=" + cityIdByName("Київ")
                + "&category=NAIL_SERVICE&page=0&size=20");

        // (1) The masterless salon-owned def surfaces — totalElements must NOT be 0.
        assertThat(data.path("totalElements").asLong())
                .as("salon A surfaces via its ACTIVE owner-scoped NAIL_SERVICE def despite having NO master_services row; pre-fix the master_services join returned 0")
                .isEqualTo(1L);
        assertThat(salonIds(data))
                .as("the single returned salon is salon A; salon B (HAIRCUT-only owned def) is excluded under category=NAIL_SERVICE")
                .containsExactly(salonA.toString());

        JsonNode row = data.path("data").get(0);
        // (2) Non-null priceMin/priceMax derived from the owned def's RANGE pricing,
        // category-scoped to NAIL_SERVICE (the off-category HAIRCUT 900 must not leak).
        assertThat(row.path("priceMin").isNull())
                .as("priceMin is derived from the owned def — never null when an active NAIL_SERVICE def exists")
                .isFalse();
        assertThat(new BigDecimal(row.path("priceMin").asText()))
                .as("category-scoped priceMin = the NAIL_SERVICE RANGE floor 150.00 (NOT the off-category HAIRCUT 900)")
                .isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(new BigDecimal(row.path("priceMax").asText()))
                .as("category-scoped priceMax = the NAIL_SERVICE RANGE ceiling 600.00")
                .isEqualByComparingTo(new BigDecimal("600.00"));

        // (3)/(4) serviceNames is non-empty and lists ONLY the NAIL_SERVICE name —
        // the off-category HAIRCUT owned def on the same salon never leaks in.
        java.util.List<String> names = new java.util.ArrayList<>();
        row.path("serviceNames").forEach(n -> names.add(n.asText()));
        assertThat(names)
                .as("serviceNames is non-empty and category-scoped to the owned NAIL_SERVICE def only — no different-category leak")
                .containsExactly("Манікюр гель-лак");
    }

    @Test
    @DisplayName("GET /search/salons?serviceTypeSlugs=… — regression: the per-slug EntityManager lateral surfaces a salon via its ACTIVE owned def carrying the slug's service_type_id even with NO master_services row; matchedServiceNames carries the owned def's name (pins owner-scoped row-source on the SearchService salon path)")
    void should_surfaceSalonBySlug_when_ownedDefHasNoMasterServicesRow() throws Exception {
        ensureHttpClient();
        UUID typeIdA = serviceTypeIdBySlug(SLUG_A);
        // Salon with an ACTIVE owned def carrying SLUG_A's service_type_id and NO
        // master_services row. The SearchService EntityManager builder's salon
        // per-slug EXISTS must read owner-scoped defs directly.
        UUID salonWithType = seedActiveSalon("Київ", null);
        seedSalonOwnedServiceNoMaster(salonWithType, "Кератинове відновлення", "HAIRCUT",
                "FIXED", new BigDecimal("800.00"), null, typeIdA, true);
        // A second salon whose owned def is type-less (legacy NULL service_type_id)
        // and NO master link → must NOT surface under the typed slug filter.
        UUID salonNoType = seedActiveSalon("Київ", null);
        seedSalonOwnedServiceNoMaster(salonNoType, "Звичайна стрижка", "HAIRCUT",
                "FIXED", new BigDecimal("300.00"), null, null, true);

        JsonNode data = salonSearch("?serviceTypeSlugs=" + SLUG_A + "&page=0&size=20");

        assertThat(data.path("totalElements").asLong())
                .as("the masterless salon surfaces via the owner-scoped per-slug EXISTS; pre-fix the master_services join returned 0")
                .isEqualTo(1L);
        assertThat(salonIds(data))
                .as("only the salon whose owned def carries SLUG_A's service_type_id is returned")
                .containsExactly(salonWithType.toString());

        java.util.List<String> matched = new java.util.ArrayList<>();
        data.path("data").get(0).path("matchedServiceNames").forEach(n -> matched.add(n.asText()));
        assertThat(matched)
                .as("matchedServiceNames carries the owned def's name even with no master_services row")
                .containsExactly("Кератинове відновлення");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Seeds an ACTIVE-or-inactive SALON-owned {@code service_definitions} row
     * (FIXED or RANGE) created directly under the salon's catalog — exactly as
     * {@code POST /salons/{id}/services} does — with <b>NO {@code master_services}
     * row at all</b>. This is the production shape that the pre-fix
     * master_services-join salon search could never see; it is the row-source
     * the fix pins. Distinct from {@link #seedSalonServiceForMaster} /
     * {@link #seedTypedSalonServiceForMaster}, which always attach a master link.
     *
     * <p>Honours the V67 {@code chk_service_def_price_mode} CHECK: FIXED →
     * {@code price_max} NULL; RANGE → {@code price_max} >= {@code base_price}.</p>
     *
     * @param serviceTypeId optional platform service-type FK (nullable for the
     *                      category-only / legacy path)
     */
    private void seedSalonOwnedServiceNoMaster(UUID salonId, String serviceName, String category,
                                               String priceType, BigDecimal basePrice,
                                               BigDecimal priceMax, UUID serviceTypeId,
                                               boolean defActive) {
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                        "(id, owner_type, owner_id, name, category, service_type_id, base_duration_minutes, base_price, price_type, price_max, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'SALON', ?, ?, ?, ?, 60, ?, ?, ?, 0, ?, NOW(), NOW())",
                UUID.randomUUID(), salonId, serviceName, category, serviceTypeId,
                basePrice, priceType, priceMax, defActive);
        // Deliberately NO master_services INSERT — that absence is the test fixture.
    }

    /** Issues an anonymous master search and returns the {@code data} (PageResponse) node. */
    private JsonNode masterSearch(String query) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + query, HttpMethod.GET, anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    /** Issues an anonymous salon search and returns the {@code data} (PageResponse) node. */
    private JsonNode salonSearch(String query) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + query, HttpMethod.GET, anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    /** Collects the {@code masterId} strings of a search PageResponse data node. */
    private static java.util.List<String> masterIds(JsonNode data) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        data.path("data").forEach(row -> ids.add(row.path("masterId").asText()));
        return ids;
    }

    /** Collects the {@code salonId} strings of a search PageResponse data node. */
    private static java.util.List<String> salonIds(JsonNode data) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        data.path("data").forEach(row -> ids.add(row.path("salonId").asText()));
        return ids;
    }

    /** Resolves a Flyway-seeded {@code service_types.id} by its globally-unique slug. */
    private UUID serviceTypeIdBySlug(String slug) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM service_types WHERE slug = ?", UUID.class, slug);
    }

    /**
     * Seeds an active (or inactive) INDEPENDENT_MASTER-owned service definition
     * with a controllable {@code service_type_id} (nullable — pass {@code null}
     * for the legacy/name-only path) plus a {@code master_services} link, then
     * refreshes {@code masters.min_effective_price} so per-service + price
     * composition tests see the pre-computed column.
     */
    private void seedTypedServiceForMaster(UUID masterId, String serviceName, String category,
                                           BigDecimal basePrice, UUID serviceTypeId,
                                           boolean defActive, boolean msActive) {
        // FIXED-only delegation (price_max NULL) — preserves the historical shape
        // that all category/slug-membership callers rely on.
        seedTypedServiceForMaster(masterId, serviceName, category, "FIXED",
                basePrice, null, serviceTypeId, defActive, msActive);
    }

    /**
     * RANGE-capable overload of {@link #seedTypedServiceForMaster}: seeds an
     * INDEPENDENT_MASTER-owned typed service definition with an explicit
     * {@code priceType} ("FIXED" or "RANGE") and {@code priceMax} ceiling, plus a
     * {@code master_services} link, then refreshes
     * {@code masters.min_effective_price}. Honours the V67
     * {@code chk_service_def_price_mode} CHECK: FIXED → {@code price_max} NULL;
     * RANGE → {@code price_max} >= {@code base_price}. Backs the per-slug
     * scoped-price regression where a master carries BOTH a FIXED service on one
     * service-type and a RANGE service on another.
     */
    private void seedTypedServiceForMaster(UUID masterId, String serviceName, String category,
                                           String priceType, BigDecimal basePrice, BigDecimal priceMax,
                                           UUID serviceTypeId, boolean defActive, boolean msActive) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                        "(id, owner_type, owner_id, name, category, service_type_id, base_duration_minutes, base_price, price_type, price_max, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'INDEPENDENT_MASTER', ?, ?, ?, ?, 60, ?, ?, ?, 0, ?, NOW(), NOW())",
                serviceDefId, masterId, serviceName, category, serviceTypeId,
                basePrice, priceType, priceMax, defActive);

        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), masterId, serviceDefId, msActive);

        jdbcTemplate.update(
                "UPDATE masters SET min_effective_price = (" +
                "  SELECT MIN(COALESCE(ms2.price_override, sd2.base_price))" +
                "  FROM master_services ms2" +
                "  JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id" +
                "  WHERE ms2.master_id = ? AND ms2.is_active = true AND sd2.is_active = true" +
                ") WHERE id = ?",
                masterId, masterId);
    }

    /**
     * Seeds a SALON-owned FIXED-priced service definition with a controllable
     * {@code service_type_id} (nullable) and {@code is_active} flags, plus a
     * {@code master_services} link for the given salon master. Backs the salon
     * per-service-filter tests (the salon EXISTS reaches active masters' active
     * services).
     */
    private void seedTypedSalonServiceForMaster(UUID masterId, UUID salonId, String serviceName,
                                                String category, BigDecimal basePrice, UUID serviceTypeId,
                                                boolean defActive, boolean msActive) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                        "(id, owner_type, owner_id, name, category, service_type_id, base_duration_minutes, base_price, price_type, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'SALON', ?, ?, ?, ?, 60, ?, 'FIXED', 0, ?, NOW(), NOW())",
                serviceDefId, salonId, serviceName, category, serviceTypeId, basePrice, defActive);

        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), masterId, serviceDefId, msActive);
    }

    /**
     * Seeds an active salon in {@code city} whose active SALON_MASTER offers a
     * single active FIXED service in {@code category} (service_type_id NULL —
     * category-only). Backs the salon category-filter regression tests where each
     * salon's discovery eligibility hinges solely on the category of its one
     * offered service.
     */
    private UUID seedSalonOfferingCategory(String city, String category, String serviceName) {
        UUID salonId = seedActiveSalon(city, null);
        UUID masterId = seedSalonMasterFor(salonId, city, "4.00");
        seedTypedSalonServiceForMaster(masterId, salonId, serviceName, category,
                new BigDecimal("300.00"), null, true, true);
        return salonId;
    }

    /**
     * Seeds an INDEPENDENT_MASTER with explicit first/last name on the user row
     * (the generic {@link #seedMaster} leaves names null, which the name-search
     * tests need populated). City FK lives on the user row.
     */
    private UUID seedNamedIndependentMaster(String city, String avgRating,
                                            String firstName, String lastName) {
        UUID cityId = cityIdByName(city);

        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, city, city_id, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', ?, ?, ?, ?, true, true)",
                userId, "named-master-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", firstName, lastName, city, cityId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, 'INDEPENDENT_MASTER', ?::numeric, 1, true, NOW(), NOW())",
                masterId, userId, avgRating);
        return masterId;
    }

    /**
     * Seeds an active, priced service definition with an explicit (custom)
     * {@code name} plus an active {@code master_services} link, then refreshes
     * {@code masters.min_effective_price}. Used by the service-name search and
     * {@code serviceNames}-population tests where the name must be deterministic.
     */
    private void seedNamedServiceForMaster(UUID masterId, String serviceName,
                                           String category, BigDecimal basePrice) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                        "(id, owner_type, owner_id, name, category, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'INDEPENDENT_MASTER', ?, ?, ?, 60, ?, 0, true, NOW(), NOW())",
                serviceDefId, masterId, serviceName, category, basePrice);

        UUID msId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, true, NOW(), NOW())",
                msId, masterId, serviceDefId);

        jdbcTemplate.update(
                "UPDATE masters SET min_effective_price = (" +
                "  SELECT MIN(COALESCE(ms2.price_override, sd2.base_price))" +
                "  FROM master_services ms2" +
                "  JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id" +
                "  WHERE ms2.master_id = ? AND ms2.is_active = true AND sd2.is_active = true" +
                ") WHERE id = ?",
                masterId, masterId);
    }

    /**
     * Seeds an active, priced RANGE service definition (floor {@code basePrice},
     * ceiling {@code priceMax}) owned by the independent master, plus an active
     * {@code master_services} link, then refreshes
     * {@code masters.min_effective_price}. Honours the V67
     * {@code chk_service_def_price_mode} CHECK: RANGE requires
     * {@code price_max >= base_price}. Used by the master priceMax discriminator
     * test where the ceiling must strictly exceed the floor.
     */
    private void seedRangeServiceForMaster(UUID masterId, String serviceName,
                                           String category, BigDecimal basePrice, BigDecimal priceMax) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                        "(id, owner_type, owner_id, name, category, base_duration_minutes, base_price, price_type, price_max, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'INDEPENDENT_MASTER', ?, ?, ?, 60, ?, 'RANGE', ?, 0, true, NOW(), NOW())",
                serviceDefId, masterId, serviceName, category, basePrice, priceMax);

        UUID msId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, true, NOW(), NOW())",
                msId, masterId, serviceDefId);

        jdbcTemplate.update(
                "UPDATE masters SET min_effective_price = (" +
                "  SELECT MIN(COALESCE(ms2.price_override, sd2.base_price))" +
                "  FROM master_services ms2" +
                "  JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id" +
                "  WHERE ms2.master_id = ? AND ms2.is_active = true AND sd2.is_active = true" +
                ") WHERE id = ?",
                masterId, masterId);
    }

    /** Seeds a SALON_OWNER + an active salon with an explicit name in the given city. */
    private UUID seedNamedSalon(String city, String name) {
        UUID cityId = cityIdByName(city);

        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "named-salon-owner-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, city_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, name, city, cityId);
        return salonId;
    }

    /**
     * Seeds an active, named salon in {@code city} plus one SALON_MASTER, one
     * named SALON-owned service definition and the {@code master_services} link
     * — with the three discovery {@code is_active} flags individually
     * controllable. Backs the salon service-name search active-only guard: the
     * widened {@code q} EXISTS clause requires the master, the link AND the
     * definition to all be active, so a term carried only by an inactive layer
     * must not surface the salon. The salon itself is always active (the term is
     * not in its name).
     */
    private UUID seedSalonWithNamedService(String city, String salonName, String serviceName,
                                           boolean masterActive, boolean msActive, boolean defActive) {
        UUID salonId = seedNamedSalon(city, salonName);

        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, city, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?, ?, true, true)",
                masterUserId, "svc-name-master-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", salonId, city);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', 4.0::numeric, 1, ?, NOW(), NOW())",
                masterId, masterUserId, salonId, masterActive);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                        "(id, owner_type, owner_id, name, category, base_duration_minutes, base_price, price_type, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'SALON', ?, ?, 'EYEBROW', 60, 300.00, 'FIXED', 0, ?, NOW(), NOW())",
                serviceDefId, salonId, serviceName, defActive);

        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), masterId, serviceDefId, msActive);
        return salonId;
    }

    /**
     * Resolves the Nth Flyway-seeded {@code city_districts.id} of a city by the
     * city's Ukrainian name (V53 KATOTTH seed). Київ has multiple urban
     * districts; the deterministic {@code ORDER BY katotth_code} makes index
     * {@code 0} and {@code 1} stable across runs. Reference data — never
     * deleted by {@code cleanDb()}.
     */
    private UUID districtIdInCity(String cityNameUk, int index) {
        return jdbcTemplate.queryForObject(
                "SELECT cd.id FROM city_districts cd " +
                        "JOIN cities c ON c.id = cd.city_id " +
                        "WHERE c.name_uk = ? ORDER BY cd.katotth_code OFFSET ? LIMIT 1",
                UUID.class, cityNameUk, index);
    }

    /**
     * Seeds a {@code SALON_ADMIN} user with a backing {@code masters} row in
     * the given city. A SALON_ADMIN must never surface in public master
     * discovery regardless of having a masters row — the Phase 19.7
     * {@code AND u.role = 'INDEPENDENT_MASTER'} equality predicate (data + count)
     * is what enforces it (it replaced the earlier {@code <> 'SALON_ADMIN'}
     * exclusion that still let SALON_MASTER leak in).
     */
    private UUID seedSalonAdminMaster(String city, String avgRating) {
        UUID cityId = cityIdByName(city);

        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "admin-owner-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, city_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "AdminSalon-" + salonId, city, cityId);

        UUID adminUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, city, city_id, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'SALON_ADMIN', ?, ?, ?, true, true)",
                adminUserId, "salon-admin-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", salonId, city, cityId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?::numeric, 1, true, NOW(), NOW())",
                masterId, adminUserId, salonId, avgRating);
        return masterId;
    }

    /**
     * Seeds a SALON_OWNER + a salon stamped with {@code salonDistrictId}, plus
     * an employed SALON_MASTER whose own user row carries
     * {@code userDistrictId} (may differ from the salon's, or be {@code null}).
     * Proves the salon link wins at query time and is never denormalised onto
     * the master/user row.
     */
    private UUID seedSalonMasterWithSalonDistrictAndUserDistrict(
            String city, UUID salonDistrictId, UUID userDistrictId, String avgRating) {
        UUID cityId = cityIdByName(city);

        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "dist-owner-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, city_id, district_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "DistSalon-" + salonId, city, cityId, salonDistrictId);

        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, city, city_id, district_id, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?, ?, ?, ?, true, true)",
                masterUserId, "dist-master-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", salonId, city, cityId, userDistrictId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?::numeric, 1, true, NOW(), NOW())",
                masterId, masterUserId, salonId, avgRating);
        return masterId;
    }

    /**
     * Seeds an INDEPENDENT_MASTER (no {@code salon_id}) whose own user row
     * carries the district FK. With no salon link the
     * {@code COALESCE(sal.district_id, u.district_id)} expression falls through
     * to the user row.
     */
    private UUID seedIndependentMasterInDistrict(String city, UUID districtId, String avgRating) {
        UUID cityId = cityIdByName(city);

        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, city, city_id, district_id, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', ?, ?, ?, true, true)",
                userId, "indep-master-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", city, cityId, districtId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, 'INDEPENDENT_MASTER', ?::numeric, 1, true, NOW(), NOW())",
                masterId, userId, avgRating);
        return masterId;
    }

    /** Seeds a SALON_OWNER + an active salon stamped with a district FK. */
    private UUID seedSalonInDistrict(String city, UUID districtId, String name) {
        UUID cityId = cityIdByName(city);

        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "dsalon-owner-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, city_id, district_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, name, city, cityId, districtId);
        return salonId;
    }

    /** Percent-encodes a query-parameter value (UTF-8). */
    private static String enc(String raw) {
        return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Builds a concrete {@link java.net.URI} from an already-encoded path+query
     * string so {@link TestRestTemplate} treats it as a final URI and does NOT
     * re-expand/re-encode it as a URI template (which would double-encode a
     * pre-encoded {@code %25} into {@code %2525}). Required by the literal-{@code %}
     * escaping tests where the query value carries percent-encoded metacharacters.
     *
     * <p>The URI is built absolute (against the random server port) because
     * {@link TestRestTemplate} only prepends its root URI for String templates,
     * not for a concrete {@link java.net.URI} argument.
     */
    private java.net.URI rawUri(String encodedPathAndQuery) {
        return java.net.URI.create("http://localhost:" + port + encodedPathAndQuery);
    }

    // Per-request unique source IP. The new AuthRateLimitFilter throttles
    // GET /api/v1/search/** at 40 req/60 s PER IP (searchBuckets). This class
    // fires 60+ search GETs which, from a single loopback IP, would exhaust that
    // bucket mid-suite — and Apache HC5's DefaultHttpRequestRetryStrategy HONORS
    // the 429 `Retry-After: 60` header, sleeping 60 s and timing the test out
    // (the SlowTestExtension 10 s ceiling). Stamping a fresh X-Forwarded-For per
    // request gives each call its own per-IP bucket (resolveClientIp reads the
    // rightmost, trusted-proxy entry), so the real throttle stays active in prod
    // while these high-volume same-host ITs never trip it. The dedicated
    // SearchGetRateLimitRegressionTest exercises the throttle itself.
    private static final java.util.concurrent.atomic.AtomicInteger IP_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private static HttpHeaders anonymousHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        // Unique synthetic client IP per request — 10.x.x.x, distinct each call.
        int n = IP_SEQ.incrementAndGet();
        headers.set("X-Forwarded-For",
                "10." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF));
        return headers;
    }

    private static org.springframework.http.HttpEntity<Void> anonymous() {
        return new org.springframework.http.HttpEntity<>(anonymousHeaders());
    }

    /**
     * Creates an {@code INDEPENDENT_MASTER} user (no salon) and a {@code masters}
     * row stamped with the city FK on the USER row — all rows active by default.
     * Returns the masters.id for downstream wiring.
     *
     * <p><b>Phase 19.7 (decision 7):</b> {@code /search/masters} returns
     * {@code INDEPENDENT_MASTER} only. The generic master-discovery scenarios in
     * this class (locality / category / price / rating / active-flag / dynamic
     * SQL / label resolution) are role-agnostic mechanics; they are seeded as
     * independent masters so they exercise the discovery pipeline rather than
     * being blanket-excluded by the new role predicate. An independent master's
     * discovery locality is its own user-row {@code city_id}
     * (the {@code COALESCE(sal.city_id, u.city_id)} salon branch is NULL — no
     * salon link). SALON_MASTER salon-link locality + the new exclusion are
     * covered by their own dedicated tests below.
     */
    private UUID seedMaster(String city, String avgRating) {
        UUID cityId = cityIdByName(city);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "search-master-" + UUID.randomUUID() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, city, city_id, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', ?, ?, true, true)",
                masterUserId, masterEmail, "$2a$04$placeholdervaluefortestonlydigest", city, cityId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, 'INDEPENDENT_MASTER', ?::numeric, 1, true, NOW(), NOW())",
                masterId, masterUserId, avgRating);

        return masterId;
    }

    /**
     * Seeds a SALON_OWNER + salon + an employed SALON_MASTER (+ masters row)
     * in the given city. Used by the dedicated Phase 19.7 exclusion test that
     * asserts a SALON_MASTER never surfaces in {@code /search/masters} (it is
     * reachable only via the salon page). The master's discovery locality
     * resolves through the salon link at query time.
     */
    private UUID seedEmployedSalonMaster(String city, String avgRating) {
        UUID cityId = cityIdByName(city);

        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "search-emp-owner-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, city_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "EmpSalon-" + salonId, city, cityId);

        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, city, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?, ?, true, true)",
                masterUserId, "search-emp-master-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", salonId, city);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?::numeric, 1, true, NOW(), NOW())",
                masterId, masterUserId, salonId, avgRating);

        return masterId;
    }

    /** Convenience: seeds a master without any services. */
    private UUID seedMasterWithCity(String city, String avgRating) {
        return seedMaster(city, avgRating);
    }

    /**
     * Seeds an INDEPENDENT_MASTER in the given city with an explicit
     * {@code review_count} (overriding the {@code seedMaster} default of 1) so
     * the {@code sort=REVIEWS_DESC} ordering test has a deterministic spread.
     */
    private UUID seedMasterWithReviewCount(String city, String avgRating, int reviewCount) {
        UUID masterId = seedMaster(city, avgRating);
        jdbcTemplate.update(
                "UPDATE masters SET review_count = ? WHERE id = ?", reviewCount, masterId);
        return masterId;
    }

    /**
     * Seeds an active salon in the given city priced by a single FIXED service
     * at {@code fixedPrice}, so its aggregate band collapses to
     * {@code priceMin == priceMax == fixedPrice}. Used by the salon sort tests
     * where each salon must carry a deterministic, distinct price band.
     */
    private UUID seedSalonWithFixedService(String city, BigDecimal fixedPrice) {
        UUID salonId = seedActiveSalon(city, null);
        UUID masterId = seedSalonMasterFor(salonId, city, "4.00");
        seedSalonServiceForMaster(masterId, salonId, "MANICURE", "FIXED",
                fixedPrice, null, true, true);
        return salonId;
    }

    /**
     * Seeds a master plus one active SALON-owned MANICURE service definition
     * priced at {@code basePrice}, with an active {@code master_services} row.
     */
    private UUID seedMasterWithService(String city, String avgRating, BigDecimal basePrice) {
        UUID masterId = seedMaster(city, avgRating);
        seedServiceWithCategory(masterId, masterId, "MANICURE", basePrice, true, true);
        return masterId;
    }

    /**
     * Seeds a master plus one active SALON-owned service definition with the
     * given category, priced at {@code basePrice}, and an active
     * {@code master_services} row. Combines the existing {@code seedMaster} +
     * {@code seedServiceWithCategory} idiom into a single call so the
     * multi-filter combination test can express each seed line as one statement.
     */
    private UUID seedMasterWithServiceCategoryAndPrice(String city, String avgRating,
                                                       String category, BigDecimal basePrice) {
        UUID masterId = seedMaster(city, avgRating);
        seedServiceWithCategory(masterId, masterId, category, basePrice, true, true);
        return masterId;
    }

    /**
     * Seeds a master with NO {@code master_services} rows. The underlying
     * {@code seedMaster} already creates an unattached master, so this is a
     * named alias that makes test intent explicit and protects against a
     * future refactor of {@code seedMaster} that starts auto-attaching services.
     */
    private UUID seedMasterWithoutServices(String city, String avgRating) {
        return seedMaster(city, avgRating);
    }

    /**
     * Seeds a SALON_OWNER + an active {@code salons} row in the given city/region.
     * Bypasses the master/user hierarchy because the salon search endpoint only
     * joins {@code salons}; no master or user rows are required.
     */
    private UUID seedActiveSalon(String city, String region) {
        UUID cityId = cityIdByName(city);

        UUID ownerId = UUID.randomUUID();
        String ownerEmail = "salon-owner-" + UUID.randomUUID() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, ownerEmail, "$2a$04$placeholdervaluefortestonlydigest");

        // Phase 10.5: salon discovery locality is its own city_id FK (a salon
        // has no salon-to-salon link to resolve through).
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, region, city_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId, city, region, cityId);
        return salonId;
    }

    /**
     * Resolves a Flyway-seeded {@code cities.id} by its canonical Ukrainian
     * name (V53 KATOTTH seed). The taxonomy is reference data — never deleted
     * by {@code cleanDb()} — so this lookup is stable across tests. Used to
     * drive the Phase 10.5 FK location filter ({@code ?location.cityId=})
     * and to stamp {@code salons.city_id} / wire the SALON_MASTER salon link.
     */
    private UUID cityIdByName(String nameUk) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = ? ORDER BY katotth_code LIMIT 1",
                UUID.class, nameUk);
    }

    /**
     * Seeds a service definition + master_services row, then refreshes
     * {@code masters.min_effective_price} so that PERF-M2 price-filter tests
     * see the pre-computed column value instead of NULL.
     *
     * <p>The UPDATE mirrors {@code MasterRepository.refreshMinEffectivePrice()}:
     * it recomputes the MIN(COALESCE(price_override, base_price)) over all
     * <em>active</em> master_services + service_definitions rows and writes it
     * back to the masters row. Without this, the directly-seeded test data
     * leaves {@code min_effective_price = NULL}, causing every price-filter
     * integration test to return 0 results.
     *
     * @param defActive   {@code service_definitions.is_active}
     * @param msActive    {@code master_services.is_active}
     */
    private void seedServiceWithCategory(UUID masterId, UUID ownerId, String category,
                                         BigDecimal basePrice, boolean defActive, boolean msActive) {
        // Phase 19.7: independent masters own their service definitions directly
        // (owner_type='INDEPENDENT_MASTER', owner_id = the masters.id). The
        // master-search query joins master_services on master_id regardless of
        // service ownership, so the owner_type does not affect discovery — only
        // the master_services link + the active flags + base_price do.
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                        "(id, owner_type, owner_id, name, category, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'INDEPENDENT_MASTER', ?, ?, ?, 60, ?, 0, ?, NOW(), NOW())",
                serviceDefId, ownerId, "Test Service " + serviceDefId, category, basePrice, defActive);

        UUID msId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, NOW(), NOW())",
                msId, masterId, serviceDefId, msActive);

        // PERF-M2: keep min_effective_price in sync — mirrors MasterRepository.refreshMinEffectivePrice()
        // so search price filters work on directly-seeded test data.
        jdbcTemplate.update(
                "UPDATE masters SET min_effective_price = (" +
                "  SELECT MIN(COALESCE(ms2.price_override, sd2.base_price))" +
                "  FROM master_services ms2" +
                "  JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id" +
                "  WHERE ms2.master_id = ? AND ms2.is_active = true AND sd2.is_active = true" +
                ") WHERE id = ?",
                masterId, masterId);
    }
}
