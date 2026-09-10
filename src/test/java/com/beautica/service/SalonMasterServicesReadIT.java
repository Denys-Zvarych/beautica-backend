package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.MasterServiceResponse;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 309 — {@code GET /salons/{salonId}/masters/{masterId}/services}, the salon management
 * read.
 *
 * <h2>Why this is not a duplicate of the public browse read</h2>
 * {@code GET /masters/{masterId}/services} (public, cached, {@code fromPublic}-masked) and this
 * endpoint run the SAME underlying query — the only difference is one {@code .map} call
 * ({@link com.beautica.service.dto.MasterServiceResponse#fromPublic}) and an ownership gate.
 * Case 1 pins the D1 claim directly (unmasked {@code priceOverride}); the contrast case pins it
 * against the public route in one assertion pair, as the phase spec requires — two unrelated
 * tests would not prove the contrast.
 *
 * <h2>D2 — 404, not 403, on a cross-salon or nonexistent master</h2>
 * Deliberately diverges from {@code DELETE .../services/{serviceDefId}} (Phase 307), whose
 * {@code @PreAuthorize} carries {@code masterBelongsToSalon} and so 403s on the same input. Here
 * that conjunct is NOT in the SpEL gate; the ownership check happens inside
 * {@code ServiceCatalogService#getSalonMasterServices} via
 * {@code MasterRepository#existsByIdAndSalonId} directly, denied with {@code NotFoundException}.
 *
 * <h2>REUSE-FIRST</h2>
 * Built on the same {@link ServiceTestFixtures} harness as {@code MasterServiceUnassignIT} —
 * {@code createSalonOwnerAndGetToken}, {@code createSalon}, {@code createSalonMaster},
 * {@code createServiceDefinition}, {@code createSalonAdminAndGetToken},
 * {@code createSalonMasterAndGetToken}, {@code createClientAndGetToken} and
 * {@code bearerHeaders} are all shared fixture methods; the single-assign-with-override HTTP
 * call is local to this class since no shared fixture creates an assignment with a
 * non-default {@code priceOverride}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /salons/{salonId}/masters/{masterId}/services — Phase 309 management read")
class SalonMasterServicesReadIT extends AbstractIntegrationTest {

    private static final String MASTER_SERVICES_CACHE = "masterServices";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── Case 1 — OWNER reads, priceOverride present and non-null, override != base_price (D1) ──

    @Test
    @DisplayName("Case 1: OWNER — 200, priceOverride present and non-null, and DIFFERENT from the "
            + "definition's base_price (500.00 base vs 300.00 override — an override equal to "
            + "base_price would pass even if the field were masked)")
    void should_return200WithUnmaskedPriceOverride_when_ownerReads() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c1-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 309 Case 1 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID definitionId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 309 Case 1 Service");
        assignWithOverride(ownerToken, salonId, masterId, definitionId, new BigDecimal("300.00"));

        ResponseEntity<String> resp = getSalonMasterServices(ownerToken, salonId, masterId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<MasterServiceResponse> services = readServiceList(resp);
        assertThat(services).hasSize(1);
        assertThat(services.get(0).priceOverride())
                .as("D1 — priceOverride must be present and unmasked")
                .isNotNull()
                .isEqualByComparingTo("300.00");
        assertThat(services.get(0).priceOverride())
                .as("override must differ from base_price so masking could not pass unnoticed")
                .isNotEqualByComparingTo(services.get(0).priceMin());
    }

    // ── Case 2 — ADMIN parity (phase 306) ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Case 2: SALON_ADMIN of the salon — 200 (phase 306 parity)")
    void should_return200_when_salonAdminReads() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c2-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 309 Case 2 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID definitionId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 309 Case 2 Service");
        assignWithOverride(ownerToken, salonId, masterId, definitionId, null);
        String adminToken = fixtures.createSalonAdminAndGetToken(
                salonId, "admin-309-c2-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = getSalonMasterServices(adminToken, salonId, masterId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readServiceList(resp)).hasSize(1);
    }

    // ── Case 3 — a master of a DIFFERENT salon: 404, indistinguishable from nonexistent (D2) ────

    @Test
    @DisplayName("Case 3: masterId belongs to ANOTHER salon — 404 (not 403, D2), body indistinguishable "
            + "from \"no such master\"")
    void should_return404_when_masterBelongsToAnotherSalon() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c3-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 309 Case 3 Salon A");

        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c3-other-" + System.nanoTime() + "@beautica.test");
        UUID salonB = fixtures.createSalon(otherOwnerToken, "Phase 309 Case 3 Salon B");
        UUID masterInSalonB = fixtures.createSalonMaster(salonB);

        ResponseEntity<String> crossSalonResp = getSalonMasterServices(ownerToken, salonId, masterInSalonB);
        ResponseEntity<String> nonexistentResp = getSalonMasterServices(ownerToken, salonId, UUID.randomUUID());

        assertThat(crossSalonResp.getStatusCode())
                .as("D2 — a master of a different salon must 404, not 403")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(nonexistentResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // GlobalExceptionHandler genericises NotFoundException — assert the CLIENT-VISIBLE body,
        // never the internal exception message, so "wrong salon" and "no such master" cannot be
        // told apart by a caller.
        assertThat(crossSalonResp.getBody())
                .as("the body must not disclose that the master exists in another salon")
                .isEqualTo(nonexistentResp.getBody());
    }

    // ── Case 4 — masterId is a USER id, not a masters row id (D3) ──────────────────────────────

    @Test
    @DisplayName("Case 4: masterId is a USER id, not a masters row id — 404 (D3)")
    void should_return404_when_masterIdIsAUserId() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c4-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 309 Case 4 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);

        ResponseEntity<String> resp = getSalonMasterServices(ownerToken, salonId, masterUserId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Case 5 — CLIENT / SALON_MASTER / anonymous denied ───────────────────────────────────────

    @Test
    @DisplayName("Case 5a: CLIENT — 403")
    void should_return403_when_clientAttemptsRead() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c5a-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 309 Case 5a Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        String clientToken = fixtures.createClientAndGetToken(
                "client-309-c5a-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = getSalonMasterServices(clientToken, salonId, masterId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Case 5b: SALON_MASTER (read-only role) — 403")
    void should_return403_when_salonMasterAttemptsRead() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c5b-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 309 Case 5b Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        String salonMasterToken = fixtures.createSalonMasterAndGetToken(
                salonId, "readonly-309-c5b-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = getSalonMasterServices(salonMasterToken, salonId, masterId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Case 5c: anonymous — 401")
    void should_return401_when_anonymousAttemptsRead() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c5c-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 309 Case 5c Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.GET,
                HttpEntity.EMPTY, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Case 6 — an owner of a DIFFERENT salon may not read ─────────────────────────────────────

    @Test
    @DisplayName("Case 6: SALON_OWNER of a different salon — 403 (does not manage salonId at all)")
    void should_return403_when_differentSalonOwnerAttemptsRead() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c6-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 309 Case 6 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c6-other-" + System.nanoTime() + "@beautica.test");
        fixtures.createSalon(otherOwnerToken, "Phase 309 Case 6 Other Salon");

        ResponseEntity<String> resp = getSalonMasterServices(otherOwnerToken, salonId, masterId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Case 7 — THE central contrast: public masks, management does not (one assertion pair) ──

    @Test
    @DisplayName("Case 7: for the SAME master, GET /masters/{id}/services masks priceOverride while "
            + "GET /salons/{s}/masters/{m}/services does not — the phase's central claim, pinned in "
            + "one test")
    void should_maskOnPublicRoute_butNotOnManagementRoute_forSameMaster() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c7-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 309 Case 7 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID definitionId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 309 Case 7 Service");
        assignWithOverride(ownerToken, salonId, masterId, definitionId, new BigDecimal("275.50"));

        ResponseEntity<String> publicResp = restTemplate.getForEntity(
                "/api/v1/masters/" + masterId + "/services", String.class);
        ResponseEntity<String> managementResp = getSalonMasterServices(ownerToken, salonId, masterId);

        assertThat(publicResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(managementResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        MasterServiceResponse publicRow = readServiceList(publicResp).get(0);
        MasterServiceResponse managementRow = readServiceList(managementResp).get(0);
        assertThat(publicRow.priceOverride())
                .as("public browse must mask priceOverride (fromPublic)")
                .isNull();
        assertThat(managementRow.priceOverride())
                .as("management read must return it unmasked (D1)")
                .isNotNull()
                .isEqualByComparingTo("275.50");
    }

    // ── Case 8 — the public masterServices cache is neither populated nor evicted (D4) ─────────

    @Test
    @DisplayName("Case 8: the public masterServices cache is NOT populated by the management read, "
            + "and a pre-existing (masked) cache entry is NOT evicted by it (D4)")
    void should_notTouchPublicCache_when_managementReadRuns() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-309-c8-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 309 Case 8 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID definitionId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 309 Case 8 Service");
        assignWithOverride(ownerToken, salonId, masterId, definitionId, new BigDecimal("199.00"));
        Cache cache = cacheManager.getCache(MASTER_SERVICES_CACHE);
        assertThat(cache).as("masterServices must be a registered cache").isNotNull();
        cache.evict(masterId);

        assertThat(cache.get(masterId, List.class))
                .as("precondition — cache must be empty for this master before the management read")
                .isNull();

        ResponseEntity<String> firstManagementResp = getSalonMasterServices(ownerToken, salonId, masterId);
        assertThat(firstManagementResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(cache.get(masterId, List.class))
                .as("D4 — the management read must NOT populate the public masked cache")
                .isNull();

        // Now populate the cache via the PUBLIC route (masked value cached).
        ResponseEntity<String> publicResp = restTemplate.getForEntity(
                "/api/v1/masters/" + masterId + "/services", String.class);
        assertThat(publicResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cache.get(masterId, List.class))
                .as("precondition — the public route must have populated the cache")
                .isNotNull();

        ResponseEntity<String> secondManagementResp = getSalonMasterServices(ownerToken, salonId, masterId);
        assertThat(secondManagementResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<?> cachedRaw = cache.get(masterId, List.class);
        assertThat(cachedRaw)
                .as("D4 — the management read must NOT evict the public cache either")
                .isNotNull().hasSize(1);
        // Same masked shape — proves the entry is untouched, not silently refreshed with a
        // different (unmasked) value by a regression.
        MasterServiceResponse cachedEntry = (MasterServiceResponse) cachedRaw.get(0);
        assertThat(cachedEntry.priceOverride())
                .as("the cached entry must stay the masked shape the public route wrote")
                .isNull();
    }

    // ── shared setup + HTTP plumbing ────────────────────────────────────────────────────────────

    private MasterServiceResponse assignWithOverride(
            String ownerToken, UUID salonId, UUID masterId, UUID serviceDefId, BigDecimal priceOverride)
            throws Exception {
        var request = new AssignServiceToMasterRequest(serviceDefId, priceOverride, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(ownerToken)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<MasterServiceResponse>>() {}).data();
    }

    private ResponseEntity<String> getSalonMasterServices(String token, UUID salonId, UUID masterId) {
        return restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private List<MasterServiceResponse> readServiceList(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data();
    }
}
