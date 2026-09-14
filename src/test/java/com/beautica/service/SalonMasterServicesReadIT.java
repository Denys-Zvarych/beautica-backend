package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.MasterServiceResponse;
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
 * {@code GET /masters/{masterId}/services} (public, {@code permitAll}, cached per {@code masterId})
 * and this endpoint run the SAME underlying query and now return the SAME payload. <b>What
 * separates them is audience and caching, not the DTO</b> — the {@code fromPublic} mask that used
 * to null {@code priceOverride} on the public route was RETIRED by the 2026-09-13 audit (S5)
 * because it was recoverable by subtraction from {@code effectivePrice} and the nested
 * definition's {@code priceMin}, i.e. it was a control that did not control. Case 1 pins the D1
 * claim directly (the management read returns {@code priceOverride} unmasked); case 7 pins the
 * routes against each other in one assertion pair and asserts they AGREE — two unrelated tests
 * would not prove the relationship either way.
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
 *
 * <h2>Phase 310 — a SALON_MASTER may read their OWN row</h2>
 * The "Phase 310" cases below widen the gate this class otherwise pins to owner/admin: a {@code
 * SALON_MASTER} reading their own {@code masters} row now gets the identical full, unmasked
 * response (D4), while every other SALON_MASTER read (a peer, a foreign salon, their own
 * masterId behind a foreign salonId) stays refused — D2/D2.4. {@code
 * ServiceTestFixtures#createSalonMasterWithRowAndGetToken} is the fixture these cases need: it
 * seeds BOTH the {@code users} row AND the linked {@code masters} row, unlike {@code
 * createSalonMasterAndGetToken} (Phase 309's fixture, which deliberately omits the {@code
 * masters} row for its role-fast-path rejection tests). D5 cases pin that this widening does not
 * leak into POST, POST .../bulk or DELETE for the master's own row.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /salons/{salonId}/masters/{masterId}/services — Phase 309 management read + "
        + "Phase 310 own-row widening")
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
        // Phase 311 D9 changed priceMin's resolution to COALESCE(override, base_price), so for a
        // FIXED own band priceMin now legitimately EQUALS priceOverride (300.00) — comparing
        // against priceMin here would no longer prove anything about masking. Compare against the
        // DEFINITION's own base_price (500.00, ServiceTestFixtures' default) instead, which is
        // what "override must differ from base_price" actually means.
        assertThat(services.get(0).priceOverride())
                .as("override must differ from the definition's own base_price so masking could "
                        + "not pass unnoticed")
                .isNotEqualByComparingTo(new BigDecimal("500.00"));
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

    // ── Case 7 — the two routes now agree on the DTO; what separates them is audience + cache ──

    /**
     * <b>Rewritten 2026-09-13 (audit S5).</b> This case used to pin "the public browse masks
     * priceOverride, the management read does not" as the phase's central claim. The mask has been
     * RETIRED: it nulled one field while the same response still carried {@code effectivePrice}
     * ({@code COALESCE(priceOverride, base_price)}) and the nested definition's {@code priceMin}
     * ({@code base_price}), so the "hidden" value — and the master's deviation from the salon's
     * list price — was recoverable by subtraction. A control that does not control must not be
     * shipped as one.
     *
     * <p>What the two routes still differ in, and what this case now pins: the public one is
     * {@code permitAll} and CACHED per {@code masterId} across every caller including anonymous
     * ones; the management one is authorization-gated and uncached. The payloads are identical.
     */
    @Test
    @DisplayName("Case 7 (S5): for the SAME master, the public browse and the management read "
            + "return the SAME priceOverride — the retired mask was recoverable by subtraction, so "
            + "the routes are separated by audience and caching, not by a masked field")
    void should_returnIdenticalPriceOverrideOnBothRoutes_forSameMaster() throws Exception {
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
        assertThat(managementRow.priceOverride())
                .as("management read must return it unmasked (D1) — unchanged")
                .isNotNull()
                .isEqualByComparingTo("275.50");
        assertThat(publicRow.priceOverride())
                .as("S5 — the public browse serves the same value: catalogue prices on this route "
                        + "are public by product design, and masking this ONE field never hid it")
                .isEqualByComparingTo("275.50");
        assertThat(publicRow.effectivePrice().subtract(
                        publicRow.serviceDefinition().priceMin()).signum())
                .as("the non-vacuity of the whole decision: effectivePrice and the nested "
                        + "definition's base_price are BOTH on the public row, so their difference "
                        + "already disclosed the deviation the old mask claimed to hide")
                .isNotZero();
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
        // The entry is untouched — proven by isFavorite, which the public route always writes as
        // null (Phase 32.1 D1/D4) and the management read would not. priceOverride can no longer
        // serve as the discriminator: since S5 retired the mask, both routes carry the same value.
        MasterServiceResponse cachedEntry = (MasterServiceResponse) cachedRaw.get(0);
        assertThat(cachedEntry.isFavorite())
                .as("this cache is shared with anonymous callers, so it must never hold a "
                        + "caller-specific flag — and a management read must not have rewritten it")
                .isNull();
        assertThat(cachedEntry.priceOverride())
                .as("S5 — the cached (public) entry carries priceOverride like every other route")
                .isEqualByComparingTo("199.00");
    }

    // ── Phase 310 Case 1 — SALON_MASTER reads their OWN row: 200, full, unmasked (D4) ──────────

    @Test
    @DisplayName("Phase 310 Case 1: SALON_MASTER reads their OWN row — 200, priceOverride present "
            + "and unmasked (D4)")
    void should_return200WithUnmaskedPriceOverride_when_salonMasterReadsOwnRow() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-310-c1-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 310 Case 1 Salon");
        var ownMaster = fixtures.createSalonMasterWithRowAndGetToken(
                salonId, "own-310-c1-" + System.nanoTime() + "@beautica.test");
        UUID definitionId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 310 Case 1 Service");
        assignWithOverride(ownerToken, salonId, ownMaster.masterId(), definitionId, new BigDecimal("310.00"));

        ResponseEntity<String> resp =
                getSalonMasterServices(ownMaster.token(), salonId, ownMaster.masterId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<MasterServiceResponse> services = readServiceList(resp);
        assertThat(services).hasSize(1);
        assertThat(services.get(0).priceOverride())
                .as("D4 — the master's own read returns priceOverride (S5: so does the public route "
                        + "now; what this case pins is that the OWN read is served at all)")
                .isNotNull()
                .isEqualByComparingTo("310.00");
    }

    // ── Phase 310 Case 2 — a PEER master in the SAME salon: refused (the most important negative) ─

    @Test
    @DisplayName("Phase 310 Case 2: SALON_MASTER reads a PEER master's row in the SAME salon — "
            + "refused (the single most important negative case, D2)")
    void should_returnForbidden_when_salonMasterReadsPeerInSameSalon() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-310-c2-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 310 Case 2 Salon");
        UUID peerMasterId = fixtures.createSalonMaster(salonId);
        var ownMaster = fixtures.createSalonMasterWithRowAndGetToken(
                salonId, "own-310-c2-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = getSalonMasterServices(ownMaster.token(), salonId, peerMasterId);

        assertThat(resp.getStatusCode())
                .as("a SALON_MASTER must never read a peer's services, even in their own salon")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Phase 310 Case 3 — a master genuinely in ANOTHER salon: refused, no existence oracle ─────

    @Test
    @DisplayName("Phase 310 Case 3: SALON_MASTER reads a master in ANOTHER salon — refused, body "
            + "indistinguishable from a nonexistent masterId for this actor")
    void should_returnForbidden_when_salonMasterReadsMasterInAnotherSalon() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-310-c3-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 310 Case 3 Salon");
        var ownMaster = fixtures.createSalonMasterWithRowAndGetToken(
                salonId, "own-310-c3-" + System.nanoTime() + "@beautica.test");

        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-310-c3-other-" + System.nanoTime() + "@beautica.test");
        UUID salonB = fixtures.createSalon(otherOwnerToken, "Phase 310 Case 3 Salon B");
        UUID masterInSalonB = fixtures.createSalonMaster(salonB);

        ResponseEntity<String> foreignMasterResp =
                getSalonMasterServices(ownMaster.token(), salonId, masterInSalonB);
        ResponseEntity<String> nonexistentResp =
                getSalonMasterServices(ownMaster.token(), salonId, UUID.randomUUID());

        assertThat(foreignMasterResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(nonexistentResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreignMasterResp.getBody())
                .as("the body must not disclose that the foreign-salon master exists")
                .isEqualTo(nonexistentResp.getBody());
    }

    // ── Phase 310 Case 4 (D2.4) — own masterId, FOREIGN salonId in the path: refused ────────────

    @Test
    @DisplayName("Phase 310 Case 4 (D2.4): SALON_MASTER's own masterId behind a FOREIGN salonId in "
            + "the path — refused")
    void should_returnForbidden_when_salonMasterOwnRowButForeignSalonIdInPath() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-310-c4-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 310 Case 4 Salon");
        var ownMaster = fixtures.createSalonMasterWithRowAndGetToken(
                salonId, "own-310-c4-" + System.nanoTime() + "@beautica.test");

        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-310-c4-other-" + System.nanoTime() + "@beautica.test");
        UUID foreignSalonId = fixtures.createSalon(otherOwnerToken, "Phase 310 Case 4 Foreign Salon");

        ResponseEntity<String> resp =
                getSalonMasterServices(ownMaster.token(), foreignSalonId, ownMaster.masterId());

        assertThat(resp.getStatusCode())
                .as("D2.4 — the path's salonId must actually own this master row")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Phase 310 D5 — the read widening must not leak into write access, for the OWN row ───────

    @Test
    @DisplayName("Phase 310 D5: SALON_MASTER still 403 on POST .../services for their OWN row")
    void should_return403_when_salonMasterAttemptsSingleAssignForOwnRow() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-310-d5a-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 310 D5a Salon");
        var ownMaster = fixtures.createSalonMasterWithRowAndGetToken(
                salonId, "own-310-d5a-" + System.nanoTime() + "@beautica.test");
        UUID definitionId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 310 D5a Service");
        var request = new AssignServiceToMasterRequest(definitionId, null, null, null, null);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + ownMaster.masterId() + "/services",
                HttpMethod.POST, new HttpEntity<>(request, fixtures.bearerHeaders(ownMaster.token())),
                String.class);

        assertThat(resp.getStatusCode())
                .as("D5 — read widening must not leak into single-assign write access")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Phase 310 D5: SALON_MASTER still 403 on POST .../services/bulk for their OWN row")
    void should_return403_when_salonMasterAttemptsBulkAssignForOwnRow() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-310-d5b-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 310 D5b Salon");
        var ownMaster = fixtures.createSalonMasterWithRowAndGetToken(
                salonId, "own-310-d5b-" + System.nanoTime() + "@beautica.test");
        UUID serviceTypeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");
        var request = new BulkCreateServicesRequest(List.of(
                new BulkServiceItemRequest(
                        serviceTypeId, 60, PriceType.FIXED, new BigDecimal("350.00"), null, null)));

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + ownMaster.masterId() + "/services/bulk",
                HttpMethod.POST, new HttpEntity<>(request, fixtures.bearerHeaders(ownMaster.token())),
                String.class);

        assertThat(resp.getStatusCode())
                .as("D5 — read widening must not leak into bulk-assign write access")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Phase 310 D5: SALON_MASTER still 403 on DELETE .../services/{serviceDefId} for "
            + "their OWN row")
    void should_return403_when_salonMasterAttemptsUnassignForOwnRow() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-310-d5c-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 310 D5c Salon");
        var ownMaster = fixtures.createSalonMasterWithRowAndGetToken(
                salonId, "own-310-d5c-" + System.nanoTime() + "@beautica.test");
        UUID definitionId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 310 D5c Service");
        assignWithOverride(ownerToken, salonId, ownMaster.masterId(), definitionId, null);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + ownMaster.masterId() + "/services/" + definitionId,
                HttpMethod.DELETE, new HttpEntity<>(fixtures.bearerHeaders(ownMaster.token())), String.class);

        assertThat(resp.getStatusCode())
                .as("D5 — read widening must not leak into unassign write access")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── shared setup + HTTP plumbing ────────────────────────────────────────────────────────────

    private MasterServiceResponse assignWithOverride(
            String ownerToken, UUID salonId, UUID masterId, UUID serviceDefId, BigDecimal priceOverride)
            throws Exception {
        // Phase 312 D1 — a non-null floor now requires a shape; every caller here assigns
        // against a FIXED definition (ServiceTestFixtures.createServiceDefinition's default).
        var request = new AssignServiceToMasterRequest(
                serviceDefId, priceOverride != null ? PriceType.FIXED : null, priceOverride, null, null);
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
