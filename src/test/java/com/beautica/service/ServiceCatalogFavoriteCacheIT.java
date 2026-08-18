package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.MasterServiceResponse;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 253 (32.2) — proves, against a REAL Postgres and a REAL Caffeine {@code masterServices}
 * cache, that the per-client {@code isFavorite} flag added in Phase 252 can never reach a second
 * client or an anonymous guest, and is never itself written into the cache.
 *
 * <h2>The leak this class exists to catch</h2>
 * The bug is a one-line refactor that looks like a cleanup: moving the favourite decoration
 * INSIDE {@code ServiceCatalogService.getMasterServices} so the controller stays thin. That would
 * store client A's wish list under the shared key {@code masterId} and serve it, for up to the
 * 10-minute TTL, to every other client and every anonymous guest. Every assertion below is written
 * so that exact mutation turns it RED — a test that only checks "client A sees their own hearts"
 * would stay green through the leak and is worthless here.
 *
 * <p>Mirrors the cache-IT precedent ({@code ClientPassportCacheIT}, {@code FavoriteListProjectionTest}):
 * real {@link CacheManager}, real HTTP round trip via {@link TestRestTemplate}, assertions directly
 * on cache contents in addition to the served bytes.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /masters/{masterId}/services — Phase 32.2 favourite cache isolation (full HTTP + real Postgres + real cache)")
class ServiceCatalogFavoriteCacheIT extends AbstractIntegrationTest {

    private static final String MASTER_SERVICES_CACHE = "masterServices";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    /**
     * Clears {@code masterServices} explicitly before every test (D3) so ordering between tests
     * can never mask a leak — a neighbouring test's warm entry must never be what a later test's
     * "first read" observes. {@code AbstractIntegrationTest.cleanDb()} already clears every cache
     * AFTER each test; this is the belt-and-braces BEFORE, matching the phase doc's explicit
     * instruction and staying correct even if that base-class behaviour ever changes.
     */
    @BeforeEach
    void clearMasterServicesCache() {
        Cache cache = cacheManager.getCache(MASTER_SERVICES_CACHE);
        assertThat(cache).as("masterServices must be a registered CacheConfig cache").isNotNull();
        cache.clear();
    }

    // ── D3 — the cache is genuinely on ──────────────────────────────────────────

    @Test
    @DisplayName("sanity: a GET populates the masterServices cache entry for that masterId — if this "
            + "fails, every isolation assertion below is vacuous")
    void should_cacheMasterServices_when_readTwice() throws Exception {
        UUID master = createIndependentMaster("cache-sanity-master@beautica.test");
        createMasterService(master);

        getMasterServices(master, null);

        assertThat(masterServicesCache().get(master))
                .as("masterServices must hold an entry for this masterId after one GET")
                .isNotNull();
    }

    // ── the four Phase 253 leak assertions ──────────────────────────────────────

    @Test
    @DisplayName("an authenticated CLIENT who favourited S1 sees S1.isFavorite=true, S2/S3=false")
    void should_flagOwnFavourites_when_clientReadsMasterServices() throws Exception {
        UUID master = createIndependentMaster("cache-flag-master@beautica.test");
        UUID s1 = createMasterService(master);
        UUID s2 = createMasterService(master);
        UUID s3 = createMasterService(master);
        UUID clientId = createClient("cache-flag-client-a@beautica.test");
        String tokenA = loginAndGetToken(clientId);
        favoriteService(tokenA, s1);

        List<MasterServiceResponse> asA = getMasterServices(master, tokenA);

        assertThat(byId(asA, s1).isFavorite()).isTrue();
        assertThat(byId(asA, s2).isFavorite()).isFalse();
        assertThat(byId(asA, s3).isFavorite()).isFalse();
    }

    @Test
    @DisplayName("MUTATION-RED: a second client reading the SAME warmed cache entry must see every "
            + "row false, never client A's true — a leaked cache serves S1=true here")
    void should_notLeakFavourites_when_secondClientReadsWarmedCache() throws Exception {
        UUID master = createIndependentMaster("cache-leak-master@beautica.test");
        UUID s1 = createMasterService(master);
        UUID s2 = createMasterService(master);
        UUID s3 = createMasterService(master);
        UUID clientAId = createClient("cache-leak-client-a@beautica.test");
        String tokenA = loginAndGetToken(clientAId);
        favoriteService(tokenA, s1);
        UUID clientBId = createClient("cache-leak-client-b@beautica.test");
        String tokenB = loginAndGetToken(clientBId);

        // Warm the cache as A first.
        getMasterServices(master, tokenA);
        // Immediately read as B — no cache clear in between.
        List<MasterServiceResponse> asB = getMasterServices(master, tokenB);

        assertThat(asB).as("B has favourited nothing — every row must be false")
                .allSatisfy(row -> assertThat(row.isFavorite()).isFalse());
    }

    @Test
    @DisplayName("MUTATION-RED: an anonymous caller reading the SAME warmed cache entry must see "
            + "isFavorite=null on every row — never true/false, even if guests were 'fixed' to false")
    void should_returnNullFlags_when_anonymousReadsWarmedCache() throws Exception {
        UUID master = createIndependentMaster("cache-anon-master@beautica.test");
        UUID s1 = createMasterService(master);
        createMasterService(master);
        UUID clientId = createClient("cache-anon-client@beautica.test");
        String token = loginAndGetToken(clientId);
        favoriteService(token, s1);

        // Warm the cache as the authenticated, favouriting client first.
        getMasterServices(master, token);
        // Immediately read anonymously — no cache clear in between.
        List<MasterServiceResponse> anonymous = getMasterServices(master, null);

        assertThat(anonymous)
                .as("an anonymous caller must see null on every row, regardless of who warmed the cache")
                .allSatisfy(row -> assertThat(row.isFavorite()).isNull());
    }

    @Test
    @DisplayName("MUTATION-RED, the load-bearing invariant: the RAW masterServices cache entry itself "
            + "holds isFavorite=null on every element, regardless of which client warmed it")
    void should_holdNullFlagsInCache_when_entryWarmedByFavouritingClient() throws Exception {
        UUID master = createIndependentMaster("cache-invariant-master@beautica.test");
        UUID s1 = createMasterService(master);
        createMasterService(master);
        UUID clientId = createClient("cache-invariant-client@beautica.test");
        String token = loginAndGetToken(clientId);
        favoriteService(token, s1);

        // Warm the entry as the favouriting client — the one caller most likely to leak.
        getMasterServices(master, token);

        @SuppressWarnings("unchecked")
        List<MasterServiceResponse> cached =
                (List<MasterServiceResponse>) masterServicesCache().get(master).get();
        assertThat(cached)
                .as("the CACHED shape must never carry a per-client flag, no matter who warmed it")
                .allSatisfy(row -> assertThat(row.isFavorite()).isNull());
    }

    // ── D5 — no @CacheEvict is needed, or ever correct, here ────────────────────

    /**
     * Pins the no-eviction property of ONLY the {@code isFavorite} field — NOT a general
     * "masterServices never needs eviction" policy. It holds here exclusively because
     * {@code isFavorite} is fully decorated POST-cache, per-request, by
     * {@link com.beautica.service.service.MasterServiceFavoriteDecorator} (see its class
     * javadoc) — the cached entry itself never carries a per-client value for that field, so
     * favouriting can never make it stale.
     *
     * <p>A future field that IS baked into the cached {@link MasterServiceResponse} shape at
     * cache-population time (i.e. computed inside {@code ServiceCatalogService.getMasterServices}
     * rather than decorated afterwards) would need its own {@code @CacheEvict}/{@code afterCommit}
     * wiring on whatever write can change it (anti-bug §F) — do NOT read this test as license to
     * skip that for such a field.
     */
    @Test
    @DisplayName("favouriting a service does NOT evict masterServices — pins the isFavorite FIELD's "
            + "no-evict property (it is decorated post-cache), NOT a general no-evict policy for the "
            + "masterServices cache as a whole; the cached entry survives unchanged, and the very next "
            + "read by that same client still reports isFavorite=true")
    void should_notEvictMasterServices_when_clientFavoritesAService() throws Exception {
        UUID master = createIndependentMaster("cache-noevict-master@beautica.test");
        UUID s1 = createMasterService(master);
        UUID clientId = createClient("cache-noevict-client@beautica.test");
        String token = loginAndGetToken(clientId);

        // Warm the cache BEFORE the favourite exists.
        getMasterServices(master, token);
        Object warmedEntry = masterServicesCache().get(master).get();

        favoriteService(token, s1);

        assertThat(masterServicesCache().get(master))
                .as("the entry must survive a favourite toggle unchanged — no eviction hook exists "
                        + "or should ever be added (Phase 252 D6)")
                .isNotNull();
        assertThat(masterServicesCache().get(master).get())
                .as("same object identity: nothing re-populated the entry")
                .isSameAs(warmedEntry);

        List<MasterServiceResponse> afterFavorite = getMasterServices(master, token);
        assertThat(byId(afterFavorite, s1).isFavorite())
                .as("the flag is fresh on every request BECAUSE it is decorated post-cache — no "
                        + "eviction was needed for this to be correct")
                .isTrue();
    }

    // ── one more caller shape — a non-CLIENT authenticated principal ────────────

    @Test
    @DisplayName("an authenticated SALON_OWNER sees isFavorite=null on every row and costs the "
            + "decorator zero favourite queries (covered at the unit level; here just the wire shape)")
    void should_returnNullFlags_when_providerReadsMasterServices() throws Exception {
        UUID master = createIndependentMaster("cache-provider-master@beautica.test");
        createMasterService(master);
        String ownerToken = createSalonOwnerAndGetToken("cache-provider-owner@beautica.test");

        List<MasterServiceResponse> asOwner = getMasterServices(master, ownerToken);

        assertThat(asOwner).allSatisfy(row -> assertThat(row.isFavorite()).isNull());
    }

    // ── HTTP plumbing ───────────────────────────────────────────────────────────

    private List<MasterServiceResponse> getMasterServices(UUID masterId, String tokenOrNull) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tokenOrNull != null) {
            headers.setBearerAuth(tokenOrNull);
        }
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/services", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(response.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data();
    }

    private void favoriteService(String token, UUID masterServiceId) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/favorites", HttpMethod.POST,
                new HttpEntity<>(
                        "{\"targetType\":\"SERVICE\",\"targetId\":\"" + masterServiceId + "\"}",
                        bearerHeaders(token)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static MasterServiceResponse byId(List<MasterServiceResponse> list, UUID id) {
        Optional<MasterServiceResponse> found = list.stream().filter(r -> r.id().equals(id)).findFirst();
        assertThat(found).as("row %s must be present in the response", id).isPresent();
        return found.get();
    }

    private Cache masterServicesCache() {
        Cache cache = cacheManager.getCache(MASTER_SERVICES_CACHE);
        assertThat(cache).as("masterServices must be registered").isNotNull();
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
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                UUID.randomUUID(), email, passwordEncoder.encode(TEST_PASSWORD));
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper
                .readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
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

    private UUID createIndependentMaster(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true)",
                userId, email);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createMasterService(UUID masterId) {
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, "
                        + "NOW(), NOW())",
                serviceDefId, ownerId, resolveUnusedServiceTypeId("INDEPENDENT_MASTER", ownerId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }
}
