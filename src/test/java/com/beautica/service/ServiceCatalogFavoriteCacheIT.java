package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.entity.PriceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
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
    private static final String SALON_CATALOG_CACHE = "salon-service-catalog";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    /** Shared HTTP-fixture helpers (salon/master/service-type setup) — REUSE-FIRST, not re-derived. */
    private ServiceTestFixtures fixtures;

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

    /**
     * Phase 304 — same belt-and-braces reasoning as {@link #clearMasterServicesCache()} above,
     * applied to {@code salon-service-catalog}: a neighbouring test's warmed entry must never be
     * what a Phase 304 staleness case's "first read" observes.
     */
    @BeforeEach
    void clearSalonCatalogCache() {
        Cache cache = cacheManager.getCache(SALON_CATALOG_CACHE);
        assertThat(cache).as("salon-service-catalog must be a registered CacheConfig cache").isNotNull();
        cache.clear();
    }

    /**
     * Swaps in an HTTP client that supports {@code PATCH}/{@code DELETE} (the JDK default
     * {@code SimpleClientHttpRequestFactory} cannot send a body on those verbs) — the same setup
     * every sibling {@code service/} integration test uses (e.g. {@code BulkServiceSetupIntegrationTest},
     * {@code ServicesIntegrationTest}).
     */
    @BeforeEach
    void setUpFixtures() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
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

    // ── Phase 304 — salon catalogue cache eviction on master service writes ─────
    //
    // Full HTTP + real Postgres + real Caffeine `salon-service-catalog` cache, mirroring the
    // masterServices isolation tests above. No test here manually evicts the cache before its
    // "after write" GET (unlike BulkServiceSetupIntegrationTest's documented Phase-304 workaround)
    // — the very point is proving the production write path evicts on its own.

    @Test
    @DisplayName("Phase 304 case 1: bulk-creating a service for a salon master evicts that salon's "
            + "catalogue cache — the very next GET shows the new service, not the stale pre-write list")
    void should_showNewService_when_salonMasterBulkCreatesAfterCatalogueWasCached() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("cache-304-c1-owner@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 304 Case 1 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);

        // Populate the cache with the pre-write (empty) catalogue.
        assertThat(catalogueServiceIds(salonId)).isEmpty();

        UUID createdDefId = bulkCreateSalonMasterService(ownerToken, salonId, masterId);

        assertThat(catalogueServiceIds(salonId))
                .as("the service just bulk-created for this salon's master must appear in the "
                        + "response body of the very next catalogue read — a missing eviction "
                        + "would omit it here, not merely fail a mock verification")
                .contains(createdDefId);
    }

    @Test
    @DisplayName("Phase 304 case 2: renaming a service definition evicts the owning salon's "
            + "catalogue cache — the very next GET shows the new name, not the stale one")
    void should_showNewName_when_serviceDefinitionRenamedAfterCatalogueWasCached() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("cache-304-c2-owner@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 304 Case 2 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID serviceDefId = bulkCreateSalonMasterService(ownerToken, salonId, masterId);

        // Populate the cache with the pre-rename name.
        String originalName = catalogueService(salonId, serviceDefId).name();
        String newName = "Renamed " + UUID.randomUUID();
        assertThat(newName).isNotEqualTo(originalName);

        renameService(ownerToken, serviceDefId, newName);

        assertThat(catalogueService(salonId, serviceDefId).name())
                .as("the very next catalogue read must reflect the new name — a stale cache "
                        + "would keep serving the pre-rename value for the whole TTL")
                .isEqualTo(newName);
    }

    @Test
    @DisplayName("Phase 304 case 3 (regression pin for D4): deleting a service definition still "
            + "evicts the owning salon's catalogue cache — D4's comment rewrite must never become "
            + "a body change")
    void should_omitDeletedService_when_serviceDefinitionDeletedAfterCatalogueWasCached() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("cache-304-c3-owner@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 304 Case 3 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID serviceDefId = bulkCreateSalonMasterService(ownerToken, salonId, masterId);

        // Populate the cache with the pre-delete catalogue.
        assertThat(catalogueServiceIds(salonId)).contains(serviceDefId);

        deleteService(ownerToken, serviceDefId);

        assertThat(catalogueServiceIds(salonId))
                .as("a deleted service must be gone from the very next catalogue read")
                .doesNotContain(serviceDefId);
    }

    @Test
    @DisplayName("Phase 304 case 4: a write against one owner does not evict a sibling salon's "
            + "cached catalogue — per-key eviction, not allEntries=true; and an independent "
            + "master's write, which owns no salon catalogue, never evicts any salon's entry")
    void should_notEvictUnrelatedSalonCatalogue_when_oneOwnersServiceWrites() throws Exception {
        // ── Part A — a SALON-branch write against salon A must not evict salon B ──
        // (mutation check (c): allEntries=true would wipe this too.)
        String ownerAToken = fixtures.createSalonOwnerAndGetToken("cache-304-c4-owner-a@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Phase 304 Case 4 Salon A");
        UUID masterAId = fixtures.createSalonMaster(salonAId);
        fixtures.seedUsableSchedule(masterAId);

        String ownerBToken = fixtures.createSalonOwnerAndGetToken("cache-304-c4-owner-b@beautica.test");
        UUID salonBId = fixtures.createSalon(ownerBToken, "Phase 304 Case 4 Salon B");
        UUID masterBId = fixtures.createSalonMaster(salonBId);
        fixtures.seedUsableSchedule(masterBId);
        UUID salonBServiceId = bulkCreateSalonMasterService(ownerBToken, salonBId, masterBId);

        assertThat(catalogueServiceIds(salonBId)).containsExactly(salonBServiceId);
        Object warmedEntryB = salonCatalogCache().get(salonBId).get();

        bulkCreateSalonMasterService(ownerAToken, salonAId, masterAId);

        assertThat(salonCatalogCache().get(salonBId))
                .as("salon B's cache entry must survive a write against salon A")
                .isNotNull();
        assertThat(salonCatalogCache().get(salonBId).get())
                .as("same cached object identity: an allEntries=true eviction would have wiped "
                        + "this too, forcing an unnecessary recompute on the next read")
                .isSameAs(warmedEntryB);
        assertThat(catalogueServiceIds(salonBId))
                .as("salon B's catalogue content is unaffected by salon A's write")
                .containsExactly(salonBServiceId);

        // ── Part B — an INDEPENDENT_MASTER's write owns no salon catalogue and must evict
        // nothing (mutation check (d): salonCatalogIdOf wrongly returning ownerId — the
        // master's OWN id — for a master-owned definition would evict a "salon" cache entry
        // keyed by that master id). Poison the cache at exactly that key so a wrong eviction
        // is directly observable rather than an unfalsifiable no-op on a key nothing uses.
        String indepToken = fixtures.createIndependentMasterAndGetToken("cache-304-c4-indep@beautica.test");
        UUID indepMasterId = fixtures.resolveMasterIdForUserEmail("cache-304-c4-indep@beautica.test");
        UUID indepServiceDefId = fixtures.createIndependentMasterService(indepToken, "Case 4 Indep Service");

        Object sentinel = new Object();
        salonCatalogCache().put(indepMasterId, sentinel);

        renameService(indepToken, indepServiceDefId, "Renamed Indep Service " + UUID.randomUUID());

        assertThat(salonCatalogCache().get(indepMasterId))
                .as("an independent master's write must never evict a salon-service-catalog entry "
                        + "keyed by the master's own id — salonCatalogIdOf must return null here")
                .isNotNull();
        assertThat(salonCatalogCache().get(indepMasterId).get())
                .as("the poisoned entry must survive untouched — same object identity")
                .isSameAs(sentinel);
    }

    // ── Phase 304 HTTP + cache plumbing ─────────────────────────────────────────

    /** Bulk-creates ONE service for a salon master and returns the created definition's id. */
    private UUID bulkCreateSalonMasterService(String ownerToken, UUID salonId, UUID masterId) throws Exception {
        UUID serviceTypeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");
        BulkServiceItemRequest item = new BulkServiceItemRequest(
                serviceTypeId, 60, PriceType.FIXED, new BigDecimal("350.00"), null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(List.of(item)), bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<MasterServiceResponse> created = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data();
        return created.get(0).serviceDefinition().id();
    }

    /** PATCHes only {@code name} on a service definition — every other field left unchanged. */
    private void renameService(String ownerToken, UUID serviceDefId, String newName) {
        UpdateServiceDefinitionRequest request = new UpdateServiceDefinitionRequest(
                newName, null, null, null, null, null, null, null, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.PATCH,
                new HttpEntity<>(request, bearerHeaders(ownerToken)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void deleteService(String ownerToken, UUID serviceDefId) {
        ResponseEntity<Void> resp = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** GETs the public salon catalogue exactly as a real client would — no eviction workaround. */
    private SalonServiceCatalogResponse getSalonCatalogue(UUID salonId) throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {}).data();
    }

    private List<UUID> catalogueServiceIds(UUID salonId) throws Exception {
        return getSalonCatalogue(salonId).categories().stream()
                .flatMap(group -> group.services().stream())
                .map(ServiceDefinitionResponse::id)
                .toList();
    }

    private ServiceDefinitionResponse catalogueService(UUID salonId, UUID serviceDefId) throws Exception {
        Optional<ServiceDefinitionResponse> found = getSalonCatalogue(salonId).categories().stream()
                .flatMap(group -> group.services().stream())
                .filter(s -> s.id().equals(serviceDefId))
                .findFirst();
        assertThat(found).as("service %s must be present in salon %s's catalogue", serviceDefId, salonId)
                .isPresent();
        return found.get();
    }

    private Cache salonCatalogCache() {
        Cache cache = cacheManager.getCache(SALON_CATALOG_CACHE);
        assertThat(cache).as("salon-service-catalog must be registered").isNotNull();
        return cache;
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
