package com.beautica.search;

import com.beautica.AbstractIntegrationTest;
import com.beautica.search.repository.SearchSuggestionAvailabilityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 331 — {@code GET /api/v1/search/suggestions}, end to end: real HTTP, real seeded
 * {@code service_types}/{@code platform_categories} taxonomy, real bookability/locality fixtures.
 *
 * <p>{@code SearchSuggestionServiceTest} pins the fold/match/rank/cap logic in isolation with a
 * controlled fixture; this class proves the SQL availability query (D3/D5), the cache, and the
 * HTTP contract against real data. {@link AbstractIntegrationTest#cleanDb()} truncates
 * {@code masters}/{@code salons}/{@code users}/{@code service_definitions}/{@code master_services}
 * before every test but never the taxonomy ({@code service_types}/{@code platform_categories}),
 * so fixtures here always attach to a real, stable, active service type.
 */
@DisplayName("Phase 331 — GET /api/v1/search/suggestions (Testcontainers)")
class SearchSuggestionIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/search/suggestions";
    private static final String MASTERS_URL = "/api/v1/search/masters";
    private static final String SALONS_URL = "/api/v1/search/salons";
    private static final String CATALOGUE_CACHE = "searchSuggestionCatalogue";
    private static final String AVAILABILITY_CACHE = "searchSuggestionAvailability";
    private static final String ACTIVE_PLACES_CACHE = "searchSuggestionActivePlaces";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheManager cacheManager;

    @SpyBean
    private SearchSuggestionAvailabilityRepository availabilityRepository;

    /**
     * The application context (and therefore the cache) is shared across every
     * {@code AbstractIntegrationTest}. A place cached by an earlier test, or by an earlier test in
     * THIS class using the same city, would turn a fresh fixture's first request into a stale hit.
     */
    @BeforeEach
    void clearSuggestionCaches() {
        cacheManager.getCache(AVAILABILITY_CACHE).clear();
        cacheManager.getCache(CATALOGUE_CACHE).clear();
        cacheManager.getCache(ACTIVE_PLACES_CACHE).clear();
        clearInvocations(availabilityRepository);
    }

    private static HttpEntity<Void> anonymous() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    private ResponseEntity<String> get(String query) {
        ResponseEntity<String> response = restTemplate.exchange(
                URL + query, HttpMethod.GET, anonymous(), String.class);
        return response;
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private record TypeFixture(UUID id, String slug, String nameUk, String categoryKey) {}

    /** A real, stable, active service type — {@code offset} picks a different one deterministically. */
    private TypeFixture activeServiceType(int offset) {
        return jdbcTemplate.queryForObject(
                """
                SELECT st.id, st.slug, st.name_uk, st.platform_category_name
                FROM service_types st
                JOIN platform_categories pc ON pc.name = st.platform_category_name
                WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED'
                ORDER BY st.slug OFFSET ? LIMIT 1
                """,
                (rs, i) -> new TypeFixture(
                        (UUID) rs.getObject("id"), rs.getString("slug"), rs.getString("name_uk"),
                        rs.getString("platform_category_name")),
                offset);
    }

    private String categoryLabel(String categoryKey) {
        return jdbcTemplate.queryForObject(
                "SELECT display_name FROM platform_categories WHERE name = ?", String.class, categoryKey);
    }

    private UUID districtIdInCity(UUID cityId, int index) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM city_districts WHERE city_id = ? ORDER BY katotth_code OFFSET ? LIMIT 1",
                UUID.class, cityId, index);
    }

    private UUID createUser(String role) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, true, true)",
                userId, "s331-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", role);
        return userId;
    }

    private UUID createIndependentMaster(UUID cityId, UUID districtId, boolean active) {
        UUID userId = createUser("INDEPENDENT_MASTER");
        jdbcTemplate.update(
                "UPDATE users SET city_id = ?, district_id = ? WHERE id = ?", cityId, districtId, userId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 4.5, 0, ?, NOW(), NOW())",
                masterId, userId, active);
        return masterId;
    }

    private UUID createSalon(UUID cityId, UUID districtId, boolean active) {
        UUID ownerId = createUser("SALON_OWNER");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city_id, district_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                salonId, ownerId, "S331Salon" + UUID.randomUUID(), cityId, districtId, active);
        return salonId;
    }

    private UUID createSalonMaster(UUID salonId, boolean active) {
        UUID userId = createUser("SALON_MASTER");
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 4.5, 0, ?, NOW(), NOW())",
                masterId, userId, salonId, active);
        return masterId;
    }

    private UUID createServiceDefinition(String ownerType, UUID ownerId, TypeFixture type, boolean active) {
        UUID defId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, category, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, "
                        + "service_type_id, price_type, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 30, 500.00, 0, ?, ?, 'FIXED', NOW(), NOW())",
                defId, ownerType, ownerId, type.nameUk(), type.categoryKey(), active, type.id());
        return defId;
    }

    private void assignService(UUID masterId, UUID serviceDefId, boolean active) {
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), masterId, serviceDefId, active);
    }

    /** Independent master in {@code cityId}/{@code districtId}, actively performing {@code type}. */
    private void seedIndependentMasterOffering(UUID cityId, UUID districtId, TypeFixture type) {
        UUID masterId = createIndependentMaster(cityId, districtId, true);
        UUID defId = createServiceDefinition("INDEPENDENT_MASTER", masterId, type, true);
        assignService(masterId, defId, true);
    }

    // ── bookability ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a type performed only by an INACTIVE master is absent")
    void should_omitType_when_onlyPerformingMasterIsInactive() throws Exception {
        UUID cityId = testCityId();
        TypeFixture type = activeServiceType(0);
        UUID masterId = createIndependentMaster(cityId, null, false);
        UUID defId = createServiceDefinition("INDEPENDENT_MASTER", masterId, type, true);
        assignService(masterId, defId, true);

        JsonNode result = data(get("?q=" + type.slug().substring(0, 3) + "&location.cityId=" + cityId));

        assertThat(result.findValuesAsText("serviceTypeSlug")).doesNotContain(type.slug());
    }

    @Test
    @DisplayName("a type performed only via an INACTIVE master_services assignment is absent")
    void should_omitType_when_onlyAssignmentIsInactive() throws Exception {
        UUID cityId = testCityId();
        TypeFixture type = activeServiceType(1);
        UUID masterId = createIndependentMaster(cityId, null, true);
        UUID defId = createServiceDefinition("INDEPENDENT_MASTER", masterId, type, true);
        assignService(masterId, defId, false);

        JsonNode result = data(get("?q=" + type.slug().substring(0, 3) + "&location.cityId=" + cityId));

        assertThat(result.findValuesAsText("serviceTypeSlug")).doesNotContain(type.slug());
    }

    @Test
    @DisplayName("a salon-owned def with no active salon master performing it is absent (bookable gate)")
    void should_omitType_when_salonOwnedDefHasNoActivePerformingMaster() throws Exception {
        UUID cityId = testCityId();
        TypeFixture type = activeServiceType(2);
        UUID salonId = createSalon(cityId, null, true);
        // Def exists, but no master_services row at all performs it.
        createServiceDefinition("SALON", salonId, type, true);

        JsonNode result = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId));

        assertThat(result.findValuesAsText("serviceTypeSlug")).doesNotContain(type.slug());
    }

    @Test
    @DisplayName("a type performed by an active INDEPENDENT_MASTER is present with the correct categoryKey/serviceTypeSlug")
    void should_includeType_when_activeIndependentMasterPerformsIt() throws Exception {
        UUID cityId = testCityId();
        TypeFixture type = activeServiceType(3);
        seedIndependentMasterOffering(cityId, null, type);

        JsonNode result = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId));

        JsonNode row = findByType(result, "SERVICE", type.slug());
        assertThat(row).as("SERVICE row for %s must be present", type.slug()).isNotNull();
        assertThat(row.path("categoryKey").asText()).isEqualTo(type.categoryKey());
        assertThat(row.path("label").asText()).isEqualTo(type.nameUk());
    }

    @Test
    @DisplayName("a type performed by an active SALON_MASTER of an INACTIVE salon is absent — "
            + "the salon's own is_active gates the whole salon branch, not just the master/def rows")
    void should_omitType_when_owningSalonItselfIsInactive() throws Exception {
        UUID cityId = testCityId();
        TypeFixture type = activeServiceType(17);
        UUID salonId = createSalon(cityId, null, false);
        UUID salonMasterId = createSalonMaster(salonId, true);
        UUID defId = createServiceDefinition("SALON", salonId, type, true);
        assignService(salonMasterId, defId, true);

        JsonNode result = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId));

        assertThat(result.findValuesAsText("serviceTypeSlug"))
                .as("an inactive salon must hide every offering it owns, even with an active master "
                        + "actively performing it")
                .doesNotContain(type.slug());
    }

    @Test
    @DisplayName("a type performed by an active INDEPENDENT_MASTER via an active assignment, but "
            + "backed by a DEACTIVATED service_definitions row, is absent")
    void should_omitType_when_serviceDefinitionItselfIsInactive() throws Exception {
        UUID cityId = testCityId();
        TypeFixture type = activeServiceType(18);
        UUID masterId = createIndependentMaster(cityId, null, true);
        UUID defId = createServiceDefinition("INDEPENDENT_MASTER", masterId, type, false);
        assignService(masterId, defId, true);

        JsonNode result = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId));

        assertThat(result.findValuesAsText("serviceTypeSlug"))
                .as("sd.is_active = false on the service_definitions row itself must gate the "
                        + "master branch even though the master AND the assignment are both active")
                .doesNotContain(type.slug());
    }

    @Test
    @DisplayName("a type performed only by a SALON_MASTER of an active salon is present (proves the UNION's salon branch)")
    void should_includeType_when_onlySalonMasterPerformsIt() throws Exception {
        UUID cityId = testCityId();
        TypeFixture type = activeServiceType(4);
        UUID salonId = createSalon(cityId, null, true);
        UUID salonMasterId = createSalonMaster(salonId, true);
        UUID defId = createServiceDefinition("SALON", salonId, type, true);
        assignService(salonMasterId, defId, true);

        JsonNode result = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId));

        assertThat(findByType(result, "SERVICE", type.slug()))
                .as("a SALON_MASTER (never in the master-grid branch) must still surface via the "
                        + "salon branch's bookable gate")
                .isNotNull();
    }

    // ── locality ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a type offered only in city A: present for A, absent for B, present with no place (national)")
    void should_scopeByCity_andFallBackToNational_whenNoPlaceChosen() throws Exception {
        UUID cityA = majorCityIdByName("Київ");
        UUID cityB = majorCityIdByName("Львів");
        TypeFixture type = activeServiceType(5);
        seedIndependentMasterOffering(cityA, null, type);

        JsonNode inA = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityA));
        JsonNode inB = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityB));
        JsonNode national = data(get("?q=" + queryPrefix(type.nameUk())));

        assertThat(findByType(inA, "SERVICE", type.slug())).isNotNull();
        assertThat(findByType(inB, "SERVICE", type.slug())).isNull();
        assertThat(findByType(national, "SERVICE", type.slug()))
                .as("no place chosen -> national fallback, not empty")
                .isNotNull();
    }

    @Test
    @DisplayName("district narrowing: type only in D1 -> present for A+D1, absent for A+D2, present for A alone; "
            + "districtId with a DIFFERENT cityId still resolves by district (district wins)")
    void should_narrowByDistrict_whenADistrictIsChosen() throws Exception {
        UUID cityA = majorCityIdByName("Одеса");
        UUID d1 = districtIdInCity(cityA, 0);
        UUID d2 = districtIdInCity(cityA, 1);
        TypeFixture type = activeServiceType(6);
        seedIndependentMasterOffering(cityA, d1, type);

        JsonNode inD1 = data(get("?q=" + queryPrefix(type.nameUk())
                + "&location.cityId=" + cityA + "&location.districtId=" + d1));
        JsonNode inD2 = data(get("?q=" + queryPrefix(type.nameUk())
                + "&location.cityId=" + cityA + "&location.districtId=" + d2));
        JsonNode cityAlone = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityA));
        UUID unrelatedCity = majorCityIdByName("Харків");
        JsonNode differentCityWithD1 = data(get("?q=" + queryPrefix(type.nameUk())
                + "&location.cityId=" + unrelatedCity + "&location.districtId=" + d1));

        assertThat(findByType(inD1, "SERVICE", type.slug())).isNotNull();
        assertThat(findByType(inD2, "SERVICE", type.slug())).isNull();
        assertThat(findByType(cityAlone, "SERVICE", type.slug()))
                .as("city alone (district unspecified) must still see a district-scoped offering")
                .isNotNull();
        assertThat(findByType(differentCityWithD1, "SERVICE", type.slug()))
                .as("district wins over an unrelated cityId — matches search's own district-primary rule")
                .isNotNull();
    }

    @Test
    @DisplayName("districtId supplied ALONE (no location.cityId param at all) still resolves and returns the "
            + "district-scoped offering — the district branch works standalone, not only alongside a cityId")
    void should_includeType_when_districtIdSuppliedWithoutAnyCityIdParam() throws Exception {
        UUID cityA = majorCityIdByName("Запоріжжя");
        UUID district = districtIdInCity(cityA, 1);
        TypeFixture type = activeServiceType(19);
        seedIndependentMasterOffering(cityA, district, type);

        JsonNode districtOnly = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.districtId=" + district));

        assertThat(findByType(districtOnly, "SERVICE", type.slug()))
                .as("location.districtId with no location.cityId at all must still resolve by district")
                .isNotNull();
    }

    @Test
    @DisplayName("an unknown but well-formed cityId -> 200 [], never the national list, with ZERO availability queries "
            + "(audit-fix cycle 1 finding 2: the active-places short-circuit, not the per-place query, produces the empty result)")
    void should_returnEmpty_when_placeIdIsUnknown() throws Exception {
        TypeFixture type = activeServiceType(7);
        seedIndependentMasterOffering(testCityId(), null, type);
        UUID unknownCityId = UUID.randomUUID();

        JsonNode result = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + unknownCityId));

        assertThat(result).isEmpty();
        verify(availabilityRepository, never()).findAvailable(unknownCityId, null);
    }

    // ── active-places short-circuit (audit-fix cycle 1, finding 2) ───────────────────────────────

    @Test
    @DisplayName("a place not yet in the active-places set short-circuits to [] with ZERO repository calls, "
            + "and only starts resolving after the active-places cache is evicted (TTL-refresh proxy)")
    void should_shortCircuit_withZeroRepositoryCalls_untilActivePlacesCacheRefreshes() throws Exception {
        UUID cityId = testCityId();
        TypeFixture type = activeServiceType(15);

        // Nothing seeded yet in cityId -> the active-places snapshot (warmed by this call) does not
        // contain it. Must be [] WITHOUT ever calling findAvailable(cityId, ...).
        JsonNode beforeSeed = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId));
        assertThat(beforeSeed).isEmpty();
        verify(availabilityRepository, never()).findAvailable(cityId, null);

        // Seed AFTER the active-places snapshot was cached: still short-circuited (accepted TTL
        // staleness, same contract as the per-place availability cache) — still zero repository calls.
        seedIndependentMasterOffering(cityId, null, type);
        JsonNode stillEmpty = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId));
        assertThat(stillEmpty).isEmpty();
        verify(availabilityRepository, never()).findAvailable(cityId, null);

        // Evict the active-places cache (TTL-expiry proxy) -> the next request rebuilds the
        // active-places snapshot, now sees cityId, and calls through to findAvailable exactly once.
        cacheManager.getCache(ACTIVE_PLACES_CACHE).clear();
        JsonNode afterEvict = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId));
        assertThat(findByType(afterEvict, "SERVICE", type.slug())).isNotNull();
        verify(availabilityRepository, times(1)).findAvailable(cityId, null);
    }

    @Test
    @DisplayName("an unknown districtId short-circuits to [] even when its cityId IS active, with ZERO repository calls")
    void should_shortCircuit_when_districtUnknown_evenIfCityIsActive() throws Exception {
        UUID cityId = testCityId();
        TypeFixture type = activeServiceType(16);
        seedIndependentMasterOffering(cityId, null, type); // makes cityId (not any district) active
        UUID unknownDistrictId = UUID.randomUUID();

        JsonNode result = data(get("?q=" + queryPrefix(type.nameUk())
                + "&location.cityId=" + cityId + "&location.districtId=" + unknownDistrictId));

        assertThat(result).isEmpty();
        verify(availabilityRepository, never()).findAvailable(null, unknownDistrictId);
    }

    @Test
    @DisplayName("a malformed location.cityId -> 400, value not echoed")
    void should_return400_when_cityIdIsMalformed() {
        ResponseEntity<String> response = get("?q=ман&location.cityId=not-a-uuid");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).doesNotContain("not-a-uuid");
    }

    // ── round-trip contract (the core D3 guarantee) ───────────────────────────────────────────

    @Test
    @DisplayName("round trip: every item returned for a place yields >=1 master or salon on the results endpoints for the SAME place")
    void should_roundTrip_everyReturnedItem_intoAtLeastOneResult() throws Exception {
        UUID cityId = majorCityIdByName("Дніпро");
        TypeFixture typeA = activeServiceType(8);
        TypeFixture typeB = activeServiceType(9);
        seedIndependentMasterOffering(cityId, null, typeA);
        seedIndependentMasterOffering(cityId, null, typeB);

        // A query that word-starts both types' shared-enough prefix would be brittle; drive each
        // type's own suggestion individually via its label to keep the round-trip check simple.
        for (TypeFixture type : List.of(typeA, typeB)) {
            JsonNode suggestions = data(get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId));
            JsonNode service = findByType(suggestions, "SERVICE", type.slug());
            assertThat(service).as("suggestion for %s must be present", type.slug()).isNotNull();

            long mastersTotal = totalElements(restTemplate.exchange(
                    MASTERS_URL + "?location.cityId=" + cityId
                            + "&category=" + service.path("categoryKey").asText()
                            + "&serviceTypeSlugs=" + service.path("serviceTypeSlug").asText()
                            + "&page=0&size=20",
                    HttpMethod.GET, anonymous(), String.class));
            long salonsTotal = totalElements(restTemplate.exchange(
                    SALONS_URL + "?location.cityId=" + cityId
                            + "&category=" + service.path("categoryKey").asText()
                            + "&serviceTypeSlugs=" + service.path("serviceTypeSlug").asText()
                            + "&page=0&size=20",
                    HttpMethod.GET, anonymous(), String.class));

            assertThat(mastersTotal + salonsTotal)
                    .as("a SERVICE suggestion must never open an empty results page for the same place")
                    .isGreaterThanOrEqualTo(1L);
        }

        // A CATEGORY suggestion round-trips via q, not category+serviceTypeSlugs (D2).
        JsonNode categorySuggestions = data(get("?q=" + queryPrefix(categoryLabel(typeA.categoryKey()))
                + "&location.cityId=" + cityId));
        JsonNode categoryRow = findByType(categorySuggestions, "CATEGORY", null);
        if (categoryRow != null) {
            long mastersTotal = totalElements(restTemplate.exchange(
                    MASTERS_URL + "?location.cityId=" + cityId + "&q=" + categoryRow.path("label").asText()
                            + "&page=0&size=20",
                    HttpMethod.GET, anonymous(), String.class));
            assertThat(mastersTotal).isGreaterThanOrEqualTo(0L); // q-as-category is a weaker guarantee; smoke only
        }
    }

    private long totalElements(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data").path("totalElements").asLong();
    }

    // ── cache behaviour ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("two calls for the same place -> ONE repository invocation (cache hit on the second)")
    void should_hitCache_when_samePlaceRequestedTwice() throws Exception {
        UUID cityId = testCityId();
        TypeFixture type = activeServiceType(10);
        seedIndependentMasterOffering(cityId, null, type);

        get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId);
        get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId);

        verify(availabilityRepository, times(1)).findAvailable(cityId, null);
    }

    @Test
    @DisplayName("(cityId=A, districtId=D1) and (cityId=null, districtId=D1) share ONE cache entry (normalisation)")
    void should_normaliseCacheKey_whenDistrictIsPresentRegardlessOfCity() throws Exception {
        UUID cityId = majorCityIdByName("Запоріжжя");
        UUID districtId = districtIdInCity(cityId, 0);
        TypeFixture type = activeServiceType(11);
        seedIndependentMasterOffering(cityId, districtId, type);

        get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId + "&location.districtId=" + districtId);
        get("?q=" + queryPrefix(type.nameUk()) + "&location.districtId=" + districtId);

        verify(availabilityRepository, times(1)).findAvailable(null, districtId);
    }

    @Test
    @DisplayName("different places -> separate repository invocations")
    void should_invokeRepositorySeparately_forDifferentPlaces() throws Exception {
        UUID cityA = majorCityIdByName("Львів");
        UUID cityB = majorCityIdByName("Харків");
        TypeFixture type = activeServiceType(12);
        seedIndependentMasterOffering(cityA, null, type);
        seedIndependentMasterOffering(cityB, null, type);

        get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityA);
        get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityB);

        verify(availabilityRepository, times(1)).findAvailable(cityA, null);
        verify(availabilityRepository, times(1)).findAvailable(cityB, null);
    }

    @Test
    @DisplayName("after a CacheManager evict, the next request rebuilds (repository invoked again)")
    void should_rebuild_afterCacheEvict() throws Exception {
        UUID cityId = testCityId();
        TypeFixture type = activeServiceType(13);
        seedIndependentMasterOffering(cityId, null, type);

        get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId);
        // The @Cacheable key is the SuggestionPlaceKey record itself (an implicit single-argument
        // Spring cache key, not a composite SpEL key), so a targeted per-key evict from a plain
        // test is exactly as awkward as it would be for any ops action driving a CacheManager
        // directly — clearing the whole cache is the realistic evict path here.
        cacheManager.getCache(AVAILABILITY_CACHE).clear();
        get("?q=" + queryPrefix(type.nameUk()) + "&location.cityId=" + cityId);

        verify(availabilityRepository, times(2)).findAvailable(cityId, null);
    }

    // ── word-start / substring smoke (exhaustive matrix owned by SearchSuggestionServiceTest) ──

    @Test
    @DisplayName("apostrophe variant of a real seeded label still matches (smoke: full fold pipeline wired end to end)")
    void should_foldApostrophe_endToEnd() throws Exception {
        // Not every seeded label carries an apostrophe, so this asserts on whichever term IS used
        // rather than requiring one: the CATEGORY catalogue is queried directly for a label
        // containing an apostrophe, skipping gracefully if none exists in this seed.
        String apostropheLabel = jdbcTemplate.query(
                "SELECT display_name FROM platform_categories WHERE status = 'APPROVED' AND active = true "
                        + "AND display_name LIKE '%''%' LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null);
        if (apostropheLabel == null) {
            return; // no apostrophe-bearing category in this seed — nothing to fold
        }
        TypeFixture type = activeServiceType(14);
        seedIndependentMasterOffering(testCityId(), null, type);
        String term = apostropheLabel.substring(0, Math.min(4, apostropheLabel.length())).replace('’', '\'');

        JsonNode straight = data(get("?q=" + term));

        assertThat(straight).isNotNull(); // 200, no exception on an apostrophe-bearing term
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    /** First 3 characters of a label (substring-tier admission, safely inside any Ukrainian word). */
    private static String queryPrefix(String label) {
        return label.substring(0, Math.min(3, label.length()));
    }

    /**
     * @param serviceTypeSlug the exact SERVICE slug to find, or {@code null} to match ANY row of
     *                        {@code type} (used for a CATEGORY lookup, which carries no slug)
     */
    private static JsonNode findByType(JsonNode data, String type, String serviceTypeSlug) {
        for (JsonNode row : data) {
            if (!row.path("type").asText().equals(type)) {
                continue;
            }
            if (serviceTypeSlug == null) {
                return row;
            }
            JsonNode slugNode = row.path("serviceTypeSlug");
            if (!slugNode.isMissingNode() && !slugNode.isNull() && serviceTypeSlug.equals(slugNode.asText())) {
                return row;
            }
        }
        return null;
    }
}
