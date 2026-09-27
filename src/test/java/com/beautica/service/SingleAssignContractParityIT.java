package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.DuplicateServiceResponse;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.entity.PriceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 313 — {@code POST /salons/{salonId}/masters/{masterId}/services} single-assign contract
 * parity with its bulk and independent-master siblings.
 *
 * <h2>D1 — a deactivated definition is 404, indistinguishable from a nonexistent one</h2>
 * Cases 1-4 pin the guard and, critically, its ORDERING: it must run strictly after the ownership
 * check, so a caller probing another salon's definition ids still gets 403 (case 4), never a 404
 * that would confirm the id exists.
 *
 * <h2>D2 — the duplicate conflict is the typed {@code DuplicateServiceException}</h2>
 * Cases 5-6 pin the typed {@code DUPLICATE_SERVICE} 409 and its byte-for-byte parity with the
 * bulk endpoint's conflict body for the identical (master, definition) collision.
 *
 * <h2>D3 — Phase 307 D6's reactivation branch is untouched</h2>
 * {@code service_definitions.is_active} (D1) and {@code master_services.is_active} (D3's branch)
 * are DIFFERENT columns on DIFFERENT tables. Case 7 proves reactivation still works. Case 8 proves
 * D1 fires unconditionally before D3's reactivation branch is ever reached — but its fixture has
 * BOTH columns false at once, so on its own it cannot tell "D1 reads the right column" from "D1
 * reads the wrong one"; that discriminator is cases 1, 2, 7 and 9 (confirmed by the phase doc's
 * mutation check step 3 — reading {@code existingAssignment.isActive()} in D1's place turns those
 * four red, not case 8). Case 8b closes the remaining direction: D1 also wins over D2's duplicate
 * branch when the existing assignment stays ACTIVE while the definition is deactivated.
 *
 * <h2>REUSE-FIRST</h2>
 * Built on the same {@link ServiceTestFixtures} harness as {@code MasterServiceUnassignIT} and
 * {@code BulkServiceSetupIntegrationTest} — the bulk-create and unassign HTTP helpers are kept
 * local to this class, mirroring the established per-IT-class convention rather than widening a
 * shared fixture for a shape only this class needs.
 */
@Import(TestSecurityConfig.class)
@DisplayName("POST /salons/{salonId}/masters/{masterId}/services — Phase 313 contract parity")
class SingleAssignContractParityIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── Case 1 — a deactivated definition is 404, and writes nothing (D1) ──────────────────────

    @Test
    @DisplayName("Case 1: assigning a definition deactivated via DELETE /services/{id} — 404, "
            + "zero master_services rows for the pair (D1)")
    void should_return404AndWriteNothing_when_definitionIsDeactivated() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c1-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 313 Case 1 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 313 Case 1 Service");
        assertThat(deactivateDefinition(ownerToken, defId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> resp = assignSingle(ownerToken, salonId, masterId, defId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Long.class, masterId, defId);
        assertThat(rowCount).as("D1 — nothing written for a deactivated definition").isEqualTo(0L);
    }

    // ── Case 2 — indistinguishable from a nonexistent id (D1) ──────────────────────────────────

    @Test
    @DisplayName("Case 2: the 404 body for a deactivated definition is byte-identical to the body "
            + "for a random, never-existing UUID (D1)")
    void should_returnGenericNotFoundBody_when_definitionIsDeactivated() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c2-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 313 Case 2 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 313 Case 2 Service");
        assertThat(deactivateDefinition(ownerToken, defId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> deactivatedResp = assignSingle(ownerToken, salonId, masterId, defId);
        ResponseEntity<String> randomResp = assignSingle(ownerToken, salonId, masterId, UUID.randomUUID());

        assertThat(deactivatedResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(randomResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ApiResponse<Void> deactivatedBody = objectMapper.readValue(
                deactivatedResp.getBody(), new TypeReference<ApiResponse<Void>>() {});
        ApiResponse<Void> randomBody = objectMapper.readValue(
                randomResp.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(deactivatedBody)
                .as("a caller must not be able to tell 'deactivated' from 'never existed'")
                .isEqualTo(randomBody);
        assertThat(deactivatedBody.message()).isEqualTo("Resource not found");
    }

    // ── Case 3 — regression guard: an active definition still assigns normally ─────────────────

    @Test
    @DisplayName("Case 3: assigning an ACTIVE definition — 201, unchanged (D1 must not reject "
            + "everything)")
    void should_assignSuccessfully_when_definitionIsActive() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c3-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 313 Case 3 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 313 Case 3 Service");

        ResponseEntity<String> resp = assignSingle(ownerToken, salonId, masterId, defId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Boolean.class, masterId, defId);
        assertThat(isActive).isTrue();
    }

    // ── Case 4 — ordering: ownership beats the deactivation guard (D1) ──────────────────────────

    @Test
    @DisplayName("Case 4: a SALON_OWNER of another salon assigning THAT salon's deactivated "
            + "definition — 403, not 404 (the ownership check still runs first)")
    void should_return403_notFound_when_foreignSalonOwnerTargetsAnotherSalonsDeactivatedDefinition()
            throws Exception {
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c4-a-" + System.nanoTime() + "@beautica.test");
        UUID salonA = fixtures.createSalon(ownerAToken, "Phase 313 Case 4 Salon A");
        UUID masterA = fixtures.createSalonMaster(salonA);

        String ownerBToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c4-b-" + System.nanoTime() + "@beautica.test");
        UUID salonB = fixtures.createSalon(ownerBToken, "Phase 313 Case 4 Salon B");
        UUID salonBDefId = fixtures.createServiceDefinition(ownerBToken, salonB, "Phase 313 Case 4 Salon B Service");
        assertThat(deactivateDefinition(ownerBToken, salonBDefId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Owner A targets THEIR OWN salon/master (so canManageSalon + masterBelongsToSalon both
        // pass) but names salon B's deactivated definition — the ownership check on the
        // definition itself must fire before D1's isActive check ever runs.
        ResponseEntity<String> resp = assignSingle(ownerAToken, salonA, masterA, salonBDefId);

        assertThat(resp.getStatusCode())
                .as("ownership must be checked before is_active — a 404 here would leak that the id exists")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Case 5 — the duplicate conflict is typed DUPLICATE_SERVICE (D2) ─────────────────────────

    @Test
    @DisplayName("Case 5: assigning a definition the master already ACTIVELY offers — 409 "
            + "DUPLICATE_SERVICE with existingServiceDefId == serviceDefId (D2)")
    void should_return409DuplicateService_when_masterAlreadyActivelyOffersDefinition() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c5-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 313 Case 5 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 313 Case 5 Service");
        assertThat(assignSingle(ownerToken, salonId, masterId, defId).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> resp = assignSingle(ownerToken, salonId, masterId, defId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        DuplicateServiceResponse body = duplicateBodyFrom(resp);
        assertThat(body.code()).isEqualTo("DUPLICATE_SERVICE");
        assertThat(body.existingServiceDefId()).isEqualTo(defId);
    }

    // ── Case 6 — byte-identical to the bulk endpoint's 409 for the same collision (D2) ─────────

    @Test
    @DisplayName("Case 6: the single-assign 409 body is structurally identical, field-by-field, to "
            + "the bulk endpoint's 409 for the same (shared definition) collision")
    void should_matchBulkEndpoint409Shape_forTheSameCollision() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c6-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 313 Case 6 Salon");
        UUID masterA = fixtures.createSalonMaster(salonId);
        UUID masterB = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        // masterA creates the salon's shared definition via bulk; masterB reuses it (Phase 302 D2).
        UUID defId = bulkCreateOneService(ownerToken, salonId, masterA, typeId);
        UUID defIdForB = bulkCreateOneService(ownerToken, salonId, masterB, typeId);
        assertThat(defIdForB).as("same type -> same shared salon definition").isEqualTo(defId);

        // Bulk-path conflict: masterA re-submits the same type it already actively offers.
        ResponseEntity<String> bulkConflict = postSalonBulk(ownerToken, salonId, masterA,
                new BulkCreateServicesRequest(List.of(
                        new BulkServiceItemRequest(typeId, 60, PriceType.FIXED, new BigDecimal("350.00"), null, null))));
        // Single-path conflict: masterB is assigned the same shared definition directly.
        ResponseEntity<String> singleConflict = assignSingle(ownerToken, salonId, masterB, defId);

        assertThat(bulkConflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(singleConflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiResponse<DuplicateServiceResponse> bulkBody = objectMapper.readValue(
                bulkConflict.getBody(), new TypeReference<ApiResponse<DuplicateServiceResponse>>() {});
        ApiResponse<DuplicateServiceResponse> singleBody = objectMapper.readValue(
                singleConflict.getBody(), new TypeReference<ApiResponse<DuplicateServiceResponse>>() {});
        assertThat(singleBody)
                .as("same collision (same shared definition) -> byte-identical envelope: success, "
                        + "data.code, data.serviceName, data.existingServiceDefId and message must "
                        + "all agree, not merely the HTTP status")
                .isEqualTo(bulkBody);
        assertThat(singleBody.data().code()).isEqualTo("DUPLICATE_SERVICE");
        assertThat(singleBody.data().existingServiceDefId()).isEqualTo(defId);
    }

    // ── Case 7 — D3: the reactivation branch survives untouched ─────────────────────────────────

    @Test
    @DisplayName("Case 7: assigning a definition whose assignment is INACTIVE — 201, the existing "
            + "row is REACTIVATED, exactly one row for the pair (D3 — Phase 307 D6 preserved)")
    void should_reactivateInactiveAssignment_when_reassigned() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c7-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 313 Case 7 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 313 Case 7 Service");
        assertThat(assignSingle(ownerToken, salonId, masterId, defId).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(unassign(ownerToken, salonId, masterId, defId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> resp = assignSingle(ownerToken, salonId, masterId, defId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Long.class, masterId, defId);
        assertThat(rowCount).as("D3 — reactivation, never a second row").isEqualTo(1L);
        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Boolean.class, masterId, defId);
        assertThat(isActive).isTrue();
    }

    // ── Case 8 — D1 fires unconditionally before D3's reactivation branch runs ─────────────────
    // NOTE: this fixture has BOTH is_active columns false at once, so on its own it cannot tell
    // "D1 checks the right column" from "D1 checks the wrong one" — that discriminator lives in
    // cases 1, 2, 7 and 9 (see the class Javadoc above). Do not cite this case as the
    // column-conflation guard.

    @Test
    @DisplayName("Case 8: deactivate the DEFINITION, then attempt the reactivating assign of case "
            + "7 — 404, and the assignment STAYS is_active = false (D1 fires before D3's "
            + "reactivation branch ever runs; see cases 1/2/7/9 for the column-conflation guard)")
    void should_return404AndLeaveAssignmentInactive_when_definitionDeactivatedAfterUnassign() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c8-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 313 Case 8 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 313 Case 8 Service");
        assertThat(assignSingle(ownerToken, salonId, masterId, defId).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(unassign(ownerToken, salonId, masterId, defId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deactivateDefinition(ownerToken, defId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> resp = assignSingle(ownerToken, salonId, masterId, defId);

        assertThat(resp.getStatusCode())
                .as("D1 must fire before D3's reactivation branch is ever reached")
                .isEqualTo(HttpStatus.NOT_FOUND);
        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Boolean.class, masterId, defId);
        assertThat(isActive)
                .as("the inactive assignment must NOT have been reactivated — service_definitions."
                        + "is_active and master_services.is_active are different columns on different tables")
                .isFalse();
    }

    // ── Case 8b (QA addition) — D1 wins over D2's DUPLICATE branch too, not just D3's ──────────
    //
    // Case 8 proves D1 blocks D3's reactivation branch when BOTH is_active flags happen to be
    // false at once. It cannot discriminate a conflation bug from a correct implementation,
    // because both columns read false there. This case forces the OTHER combination — the
    // assignment stays ACTIVE while the definition is deactivated out from under it — which is
    // the state a naive refactor most plausibly mishandles: moving D1's guard down into only the
    // "no existing assignment" / "existing inactive assignment" branches (because that is where
    // today's two callers of it, cases 1 and 8, happen to sit) would leave the "existing ACTIVE
    // assignment" branch unguarded, and a re-POST of an already-offered-but-now-deactivated
    // service would 409 DUPLICATE_SERVICE instead of 404.

    @Test
    @DisplayName("Case 8b: re-assigning a definition the master already ACTIVELY offers, after "
            + "the definition was deactivated — 404, not 409 (D1 must win over D2's duplicate "
            + "branch too, not merely over D3's reactivation branch)")
    void should_return404NotConflict_when_definitionDeactivatedWhileAssignmentStaysActive() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c8b-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 313 Case 8b Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 313 Case 8b Service");
        assertThat(assignSingle(ownerToken, salonId, masterId, defId).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // No unassign here — unlike case 8, the master_services row stays ACTIVE.
        assertThat(deactivateDefinition(ownerToken, defId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> resp = assignSingle(ownerToken, salonId, masterId, defId);

        assertThat(resp.getStatusCode())
                .as("D1 must fire before D2's active-duplicate branch is ever reached, even though "
                        + "an active row already exists for this exact (master, definition) pair")
                .isEqualTo(HttpStatus.NOT_FOUND);
        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Long.class, masterId, defId);
        assertThat(rowCount).as("still exactly the one pre-existing row — no INSERT reattempted").isEqualTo(1L);
        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Boolean.class, masterId, defId);
        assertThat(isActive).as("the pre-existing active row is left exactly as it was").isTrue();
    }

    // ── Case 9 — SALON_ADMIN parity: both guards are role-independent ──────────────────────────

    @Test
    @DisplayName("Case 9: SALON_ADMIN gets identical 404 (deactivated definition) and 409 "
            + "(duplicate) behaviour as SALON_OWNER — both guards are role-independent")
    void should_returnIdentical404And409_when_salonAdminActs() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c9-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 313 Case 9 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        String adminToken = fixtures.createSalonAdminAndGetToken(
                salonId, "admin-313-c9-" + System.nanoTime() + "@beautica.test");

        UUID deactivatedDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 313 Case 9 Deactivated");
        assertThat(deactivateDefinition(ownerToken, deactivatedDefId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<String> adminOn404 = assignSingle(adminToken, salonId, masterId, deactivatedDefId);
        assertThat(adminOn404.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        UUID activeDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 313 Case 9 Active");
        assertThat(assignSingle(adminToken, salonId, masterId, activeDefId).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> adminOn409 = assignSingle(adminToken, salonId, masterId, activeDefId);

        assertThat(adminOn409.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateBodyFrom(adminOn409).code()).isEqualTo("DUPLICATE_SERVICE");
    }

    // ── Case 10 — the bulk path is unaffected; this phase changed only the single path ─────────

    @Test
    @DisplayName("Case 10: the equivalent BULK request against a deactivated definition still "
            + "behaves as today — filtered by findSalonBulkSetupCandidates, proving this phase "
            + "changed only the single path")
    void should_leaveBulkPathBehaviourUnchanged_when_definitionIsDeactivated() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-313-c10-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 313 Case 10 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID deactivatedDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Phase 313 Case 10 Service");
        UUID typeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");
        assertThat(deactivateDefinition(ownerToken, deactivatedDefId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> resp = postSalonBulk(ownerToken, salonId, masterId,
                new BulkCreateServicesRequest(List.of(
                        new BulkServiceItemRequest(typeId, 60, PriceType.FIXED, new BigDecimal("350.00"), null, null))));

        assertThat(resp.getStatusCode())
                .as("findSalonBulkSetupCandidates filters is_active=true, so the deactivated "
                        + "definition is invisible and the bulk path creates a fresh one — unaffected "
                        + "by the single-path D1 guard")
                .isEqualTo(HttpStatus.CREATED);
        UUID newDefId = createdFrom(resp).get(0).serviceDefinition().id();
        assertThat(newDefId).as("a brand-new definition, never the deactivated one").isNotEqualTo(deactivatedDefId);
    }

    // ── shared HTTP plumbing ─────────────────────────────────────────────────────────────────────

    private ResponseEntity<String> assignSingle(String token, UUID salonId, UUID masterId, UUID serviceDefId) {
        var request = new AssignServiceToMasterRequest(serviceDefId, null, null, null, null);
        return restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> unassign(String token, UUID salonId, UUID masterId, UUID serviceDefId) {
        return restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/" + serviceDefId,
                HttpMethod.DELETE, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> deactivateDefinition(String token, UUID serviceDefId) {
        return restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> postSalonBulk(
            String token, UUID salonId, UUID masterId, BulkCreateServicesRequest request) {
        return restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    /** Bulk-creates exactly ONE FIXED-price service for {@code masterId} and returns the definition id. */
    private UUID bulkCreateOneService(String ownerToken, UUID salonId, UUID masterId, UUID serviceTypeId)
            throws Exception {
        BulkServiceItemRequest item = new BulkServiceItemRequest(
                serviceTypeId, 60, PriceType.FIXED, new BigDecimal("350.00"), null, null);
        ResponseEntity<String> resp = postSalonBulk(
                ownerToken, salonId, masterId, new BulkCreateServicesRequest(List.of(item)));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return createdFrom(resp).get(0).serviceDefinition().id();
    }

    private List<MasterServiceResponse> createdFrom(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data();
    }

    private DuplicateServiceResponse duplicateBodyFrom(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<DuplicateServiceResponse>>() {}).data();
    }
}
