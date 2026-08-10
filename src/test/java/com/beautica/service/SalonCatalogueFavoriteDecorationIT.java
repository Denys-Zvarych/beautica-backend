package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Salon-service-favourites track, Phase C — proves, against a REAL Postgres and a REAL
 * Caffeine {@code salon-service-catalog} cache, that the per-client {@code isFavorite} flag
 * decorated onto {@code GET /salons/{salonId}/services} can never reach a second client or an
 * anonymous guest, and is never itself written into the cache.
 *
 * <h2>The leak this class exists to catch</h2>
 * The bug is a one-line refactor that looks like a cleanup: moving the favourite decoration
 * INSIDE {@code ServiceCatalogService.getSalonServiceCatalog} so the controller stays thin. That
 * would store client A's wish list under the shared key {@code salonId} and serve it, for up to
 * the cache TTL, to every other client and every anonymous guest. Every assertion below is
 * written so that exact mutation turns it RED — a test that only checks "client A sees their own
 * hearts" would stay green through the leak and is worthless here.
 *
 * <p>Mirrors the sibling MASTER-arm precedent {@code ServiceCatalogFavoriteCacheIT} exactly —
 * real {@link CacheManager}, real HTTP round trip via {@link TestRestTemplate}, assertions
 * directly on cache contents in addition to the served bytes.
 *
 * <h2>Why a real schedule is seeded</h2>
 * Unlike {@code GET /masters/{id}/services} (a bare active-flag listing),
 * {@code getSalonServiceCatalog} additionally gates on the Phase 23.x free-slot bookability
 * check — a service only appears if at least one performing master has a genuine free future
 * slot. {@link #seedUsableSchedule} gives the salon master a wide-open weekly window so every
 * seeded service clears that gate; without it every test here would see an empty catalogue and
 * every assertion would be vacuously true.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /salons/{salonId}/services — salon-service-favourites cache isolation (full HTTP + real Postgres + real cache)")
class SalonCatalogueFavoriteDecorationIT extends AbstractIntegrationTest {

    private static final String SALON_CATALOG_CACHE = "salon-service-catalog";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    /**
     * Clears {@code salon-service-catalog} explicitly before every test (mirrors
     * {@code ServiceCatalogFavoriteCacheIT}'s D3 note) so ordering between tests can never mask
     * a leak — a neighbouring test's warm entry must never be what a later test's "first read"
     * observes.
     */
    @BeforeEach
    void clearSalonCatalogCache() {
        Cache cache = cacheManager.getCache(SALON_CATALOG_CACHE);
        assertThat(cache).as("salon-service-catalog must be a registered CacheConfig cache").isNotNull();
        cache.clear();
    }

    // ── sanity: the cache is genuinely on ───────────────────────────────────────

    @Test
    @DisplayName("sanity: a GET populates the salon-service-catalog cache entry for that salonId — "
            + "if this fails, every isolation assertion below is vacuous")
    void should_cacheSalonCatalog_when_readTwice() throws Exception {
        UUID salonId = bookableSalonWithOneService();

        getSalonServices(salonId, null);

        assertThat(salonCatalogCache().get(salonId))
                .as("salon-service-catalog must hold an entry for this salonId after one GET")
                .isNotNull();
    }

    // ── the four leak assertions, mirroring ServiceCatalogFavoriteCacheIT ───────

    @Test
    @DisplayName("anonymous caller sees isFavorite=null on every row")
    void should_returnNullFlags_when_anonymousReadsSalonCatalog() throws Exception {
        UUID salonId = bookableSalonWithOneService();

        List<ServiceDefinitionResponse> anonymous = getSalonServices(salonId, null);

        assertThat(anonymous)
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.isFavorite()).isNull());
    }

    @Test
    @DisplayName("a non-CLIENT authenticated caller (SALON_OWNER) sees isFavorite=null on every row")
    void should_returnNullFlags_when_providerReadsSalonCatalog() throws Exception {
        UUID salonId = bookableSalonWithOneService();
        String ownerToken = createSalonOwnerAndGetToken("catalog-provider-owner@beautica.test");

        List<ServiceDefinitionResponse> asOwner = getSalonServices(salonId, ownerToken);

        assertThat(asOwner).isNotEmpty()
                .allSatisfy(row -> assertThat(row.isFavorite()).isNull());
    }

    @Test
    @DisplayName("an authenticated CLIENT who favourited S1 sees S1.isFavorite=true, S2=false")
    void should_flagOwnFavourites_when_clientReadsSalonCatalog() throws Exception {
        UUID ownerId = createSalonOwner("catalog-flag-owner@beautica.test");
        UUID salonId = createSalon(ownerId, "Flag Salon");
        UUID masterId = createSalonMaster(salonId, "catalog-flag-master@beautica.test");
        seedUsableSchedule(masterId);
        UUID s1 = createSalonService(salonId, "S1");
        assignServiceToMaster(s1, masterId);
        UUID s2 = createSalonService(salonId, "S2");
        assignServiceToMaster(s2, masterId);
        UUID clientId = createClient("catalog-flag-client-a@beautica.test");
        String tokenA = loginAndGetToken(clientId);
        favoriteSalonService(tokenA, s1);

        List<ServiceDefinitionResponse> asA = getSalonServices(salonId, tokenA);

        assertThat(byId(asA, s1).isFavorite()).isTrue();
        assertThat(byId(asA, s2).isFavorite()).isFalse();
    }

    @Test
    @DisplayName("MUTATION-RED: a second client reading the SAME warmed cache entry must see every "
            + "row false, never client A's true — a leaked cache serves S1=true here")
    void should_notLeakFavourites_when_secondClientReadsWarmedCache() throws Exception {
        UUID ownerId = createSalonOwner("catalog-leak-owner@beautica.test");
        UUID salonId = createSalon(ownerId, "Leak Salon");
        UUID masterId = createSalonMaster(salonId, "catalog-leak-master@beautica.test");
        seedUsableSchedule(masterId);
        UUID s1 = createSalonService(salonId, "S1");
        assignServiceToMaster(s1, masterId);
        UUID clientAId = createClient("catalog-leak-client-a@beautica.test");
        String tokenA = loginAndGetToken(clientAId);
        favoriteSalonService(tokenA, s1);
        UUID clientBId = createClient("catalog-leak-client-b@beautica.test");
        String tokenB = loginAndGetToken(clientBId);

        // Warm the cache as A first.
        getSalonServices(salonId, tokenA);
        // Immediately read as B — no cache clear in between.
        List<ServiceDefinitionResponse> asB = getSalonServices(salonId, tokenB);

        assertThat(asB).as("B has favourited nothing — every row must be false")
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.isFavorite()).isFalse());
    }

    @Test
    @DisplayName("MUTATION-RED: an anonymous caller reading the SAME warmed cache entry must see "
            + "isFavorite=null on every row — never true/false, even if guests were 'fixed' to false")
    void should_returnNullFlags_when_anonymousReadsWarmedCache() throws Exception {
        UUID ownerId = createSalonOwner("catalog-anon-owner@beautica.test");
        UUID salonId = createSalon(ownerId, "Anon Salon");
        UUID masterId = createSalonMaster(salonId, "catalog-anon-master@beautica.test");
        seedUsableSchedule(masterId);
        UUID s1 = createSalonService(salonId, "S1");
        assignServiceToMaster(s1, masterId);
        UUID clientId = createClient("catalog-anon-client@beautica.test");
        String token = loginAndGetToken(clientId);
        favoriteSalonService(token, s1);

        // Warm the cache as the authenticated, favouriting client first.
        getSalonServices(salonId, token);
        // Immediately read anonymously — no cache clear in between.
        List<ServiceDefinitionResponse> anonymous = getSalonServices(salonId, null);

        assertThat(anonymous)
                .as("an anonymous caller must see null on every row, regardless of who warmed the cache")
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.isFavorite()).isNull());
    }

    @Test
    @DisplayName("MUTATION-RED, the load-bearing invariant: the RAW salon-service-catalog cache entry "
            + "itself holds isFavorite=null on every element, regardless of which client warmed it")
    void should_holdNullFlagsInCache_when_entryWarmedByFavouritingClient() throws Exception {
        UUID ownerId = createSalonOwner("catalog-invariant-owner@beautica.test");
        UUID salonId = createSalon(ownerId, "Invariant Salon");
        UUID masterId = createSalonMaster(salonId, "catalog-invariant-master@beautica.test");
        seedUsableSchedule(masterId);
        UUID s1 = createSalonService(salonId, "S1");
        assignServiceToMaster(s1, masterId);
        UUID clientId = createClient("catalog-invariant-client@beautica.test");
        String token = loginAndGetToken(clientId);
        favoriteSalonService(token, s1);

        // Warm the entry as the favouriting client — the one caller most likely to leak.
        getSalonServices(salonId, token);

        Object cachedEntry = salonCatalogCache().get(salonId).get();
        @SuppressWarnings("unchecked")
        var wrapper = (SalonServiceCatalogResponse) cachedEntry;
        assertThat(wrapper.categories())
                .isNotEmpty()
                .flatExtracting(com.beautica.service.dto.SalonServiceCategoryGroup::services)
                .as("the CACHED shape must never carry a per-client flag, no matter who warmed it")
                .allSatisfy(row -> assertThat(row.isFavorite()).isNull());
    }

    // ── HTTP plumbing ───────────────────────────────────────────────────────────

    private List<ServiceDefinitionResponse> getSalonServices(UUID salonId, String tokenOrNull) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tokenOrNull != null) {
            headers.setBearerAuth(tokenOrNull);
        }
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SalonServiceCatalogResponse catalog = objectMapper.readValue(response.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {}).data();
        return catalog.categories().stream()
                .flatMap(g -> g.services().stream())
                .toList();
    }

    private void favoriteSalonService(String token, UUID serviceDefId) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/favorites", HttpMethod.POST,
                new HttpEntity<>(
                        "{\"targetType\":\"SALON_SERVICE\",\"targetId\":\"" + serviceDefId + "\"}",
                        bearerHeaders(token)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static ServiceDefinitionResponse byId(List<ServiceDefinitionResponse> list, UUID id) {
        Optional<ServiceDefinitionResponse> found = list.stream().filter(r -> r.id().equals(id)).findFirst();
        assertThat(found).as("row %s must be present in the response", id).isPresent();
        return found.get();
    }

    private Cache salonCatalogCache() {
        Cache cache = cacheManager.getCache(SALON_CATALOG_CACHE);
        assertThat(cache).as("salon-service-catalog must be registered").isNotNull();
        return cache;
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String loginAndGetToken(UUID clientId) throws Exception {
        String email = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, clientId);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper
                .readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    private String createSalonOwnerAndGetToken(String email) throws Exception {
        createSalonOwner(email);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper
                .readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    /** Convenience: one salon, one active master with a usable schedule, one bookable service. */
    private UUID bookableSalonWithOneService() {
        UUID ownerId = createSalonOwner("catalog-sanity-owner-" + UUID.randomUUID() + "@beautica.test");
        UUID salonId = createSalon(ownerId, "Sanity Salon");
        UUID masterId = createSalonMaster(salonId, "catalog-sanity-master-" + UUID.randomUUID() + "@beautica.test");
        seedUsableSchedule(masterId);
        UUID serviceDefId = createSalonService(salonId, "Sanity Service");
        assignServiceToMaster(serviceDefId, masterId);
        return salonId;
    }

    // ── seed helpers ────────────────────────────────────────────────────────────

    private UUID createClient(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD));
        return id;
    }

    private UUID createSalonOwner(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        return userId;
    }

    private UUID createSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'Kyiv', true, NOW(), NOW())",
                salonId, ownerId, name);
        return salonId;
    }

    private UUID createSalonMaster(UUID salonId, String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }

    private UUID createSalonService(UUID salonId, String name) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, category, service_type_id, base_duration_minutes, "
                        + "base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, ?, 'MANICURE', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, name, resolveUnusedServiceTypeId("SALON", salonId));
        return serviceDefId;
    }

    private void assignServiceToMaster(UUID serviceDefId, UUID masterId) {
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterId, serviceDefId);
    }

    // ── Phase 23.x free-slot gate fixtures (schedule) ───────────────────────────

    private LocalDate kyivToday() {
        return LocalDate.now(KYIV);
    }

    private UUID insertWeeklySchedule(UUID masterId, LocalDate validFrom, LocalDate validTo) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, validFrom, validTo);
        return scheduleId;
    }

    private void insertInterval(UUID scheduleId, int isoDow, LocalTime start, LocalTime end) {
        jdbcTemplate.update(
                "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheduleId, isoDow, start, end);
    }

    /**
     * Open-ended weekly template with a 09:00-17:00 interval on today's ISO weekday — many free
     * future slots, so every service this master performs clears the free-slot bookability gate
     * {@code getSalonServiceCatalog} applies (mirrors {@code SalonPublicProfileIntegrationTest
     * #seedUsableSchedule}).
     */
    private void seedUsableSchedule(UUID masterId) {
        UUID scheduleId = insertWeeklySchedule(masterId, kyivToday(), null);
        insertInterval(scheduleId, kyivToday().getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(17, 0));
    }
}
