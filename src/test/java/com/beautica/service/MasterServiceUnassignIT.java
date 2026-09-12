package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
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
 * Phase 307 — {@code DELETE /salons/{salonId}/masters/{masterId}/services/{serviceDefId}}, the
 * per-master unassign endpoint.
 *
 * <h2>NOT {@code DELETE /services/{serviceDefId}}</h2>
 * That endpoint (already shipped, {@code ServiceControllerTest}) deactivates the SHARED
 * definition and removes it from every master in the salon at once. This endpoint touches ONE
 * {@code master_services} row and leaves the definition and every other master's assignment
 * alone — case 2's two-master arm and case 3 are the tests that would catch a regression toward
 * the wrong endpoint's blast radius.
 *
 * <h2>D1 — soft unassign only</h2>
 * {@code is_active} flips to {@code false}; the row is never deleted. Case 1 asserts the row, not
 * just the response status. {@code bookings.master_service_id} has no {@code ON DELETE} clause
 * (V18), so a regression to a hard delete would abort with a foreign-key violation the moment any
 * booking (past or future) references the row — case 5's COMPLETED booking is what a DELETE
 * regression would trip.
 *
 * <h2>D4 — future CONFIRMED refuses with 409, D6 — reactivation on re-assign</h2>
 * Case 6/7 pin the refusal and its exact scope (assignment, not master). Case 8/9 pin the D6
 * amendment to {@code assignServiceToMaster} that makes re-assigning after an unassign possible at
 * all — without it, {@code master_services}' non-partial {@code UNIQUE (master_id,
 * service_def_id)} turns a plain re-insert into an opaque constraint-violation 409 (the
 * pre-registered Phase 302 backlog finding this phase closes).
 *
 * <h2>REUSE-FIRST</h2>
 * Built on the same {@link ServiceTestFixtures} harness as {@code SalonCatalogueVisibilityIT} and
 * {@code BulkServiceSetupIntegrationTest} — {@code createSalonOwnerAndGetToken}, {@code
 * createSalon}, {@code createSalonMaster}, {@code seedUsableSchedule},
 * {@code createSalonAdminAndGetToken}, {@code createSalonMasterAndGetToken},
 * {@code createClientAndGetToken}, {@code activeSelectableServiceTypes} and
 * {@code bearerHeaders} are all shared fixture methods. The bulk-create helper and the raw-SQL
 * booking inserts stay local to this class, mirroring the established per-IT-class convention
 * (e.g. {@code BookingCommentPersistenceIT#insertBookingWithStatus}) rather than widening a
 * shared fixture for a shape only this class needs.
 */
@Import(TestSecurityConfig.class)
@DisplayName("DELETE /salons/{salonId}/masters/{masterId}/services/{serviceDefId} — Phase 307 unassign matrix")
class MasterServiceUnassignIT extends AbstractIntegrationTest {

    private static final String SALON_CATALOG_CACHE = "salon-service-catalog";

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

    // ── Case 1 — soft unassign: row survives with is_active = false (D1) ───────────────────────

    @Test
    @DisplayName("Case 1: unassign with no bookings — 204, the master_services row STILL EXISTS "
            + "with is_active = false (D1); asserts the row, not just the status")
    void should_deactivateRow_when_noBookingsBlock() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c1-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 1 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);

        ResponseEntity<String> resp = unassign(ownerToken, salonId, masterId, assignment.definitionId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE id = ?", Boolean.class, assignment.assignmentId());
        assertThat(isActive).as("D1 — the row must survive, only is_active flips").isFalse();
    }

    // ── Case 2 — blast radius: gone from the catalogue alone, kept with a second master ─────────

    @Test
    @DisplayName("Case 2a: after unassign, the service disappears from GET /salons/{s}/services "
            + "when no other master performs it")
    void should_disappearFromCatalogue_when_soleMasterUnassigned() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c2a-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 2a Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);
        assertThat(catalogueServiceIds(salonId)).contains(assignment.definitionId());

        assertThat(unassign(ownerToken, salonId, masterId, assignment.definitionId()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(catalogueServiceIds(salonId))
                .as("the only performing master was unassigned — the service must fall out")
                .doesNotContain(assignment.definitionId());
    }

    @Test
    @DisplayName("Case 2b: with a SECOND master still assigned to the same definition, the service "
            + "STAYS listed after the first master is unassigned — the multi-master blast-radius "
            + "guarantee that distinguishes this endpoint from DELETE /services/{id}")
    void should_stayInCatalogue_when_anotherMasterStillAssigned() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c2b-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 2b Salon");
        UUID masterA = fixtures.createSalonMaster(salonId);
        UUID masterB = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        fixtures.seedUsableSchedule(masterB);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignmentA = bulkCreateOneService(ownerToken, salonId, masterA, typeId);
        // Same service type -> reuses the SAME salon-owned definition (Phase 302 D2).
        Assignment assignmentB = bulkCreateOneService(ownerToken, salonId, masterB, typeId);
        assertThat(assignmentA.definitionId()).isEqualTo(assignmentB.definitionId());

        assertThat(unassign(ownerToken, salonId, masterA, assignmentA.definitionId()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(catalogueServiceIds(salonId))
                .as("master B still actively performs the shared definition — must stay visible")
                .contains(assignmentA.definitionId());
    }

    // ── Case 3 — the shared definition is untouched (D2) ────────────────────────────────────────

    @Test
    @DisplayName("Case 3: after unassign, the service_definitions row is untouched — is_active = "
            + "true, owner unchanged (D2)")
    void should_leaveDefinitionUntouched_when_masterUnassigned() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c3-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 3 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);

        assertThat(unassign(ownerToken, salonId, masterId, assignment.definitionId()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        var row = jdbcTemplate.queryForMap(
                "SELECT is_active, owner_type, owner_id FROM service_definitions WHERE id = ?",
                assignment.definitionId());
        assertThat(row.get("is_active")).as("D2 — the definition must stay active").isEqualTo(true);
        assertThat(row.get("owner_type")).isEqualTo("SALON");
        assertThat(row.get("owner_id")).isEqualTo(salonId);
    }

    // ── Case 4 — other masters' assignments for the same definition are untouched ───────────────

    @Test
    @DisplayName("Case 4: after unassign, another master's assignment for the SAME definition is "
            + "untouched (still is_active = true)")
    void should_leaveOtherMastersAssignmentsUntouched_when_oneMasterUnassigned() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c4-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 4 Salon");
        UUID masterA = fixtures.createSalonMaster(salonId);
        UUID masterB = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        fixtures.seedUsableSchedule(masterB);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignmentA = bulkCreateOneService(ownerToken, salonId, masterA, typeId);
        Assignment assignmentB = bulkCreateOneService(ownerToken, salonId, masterB, typeId);

        assertThat(unassign(ownerToken, salonId, masterA, assignmentA.definitionId()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        Boolean masterBStillActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE id = ?", Boolean.class, assignmentB.assignmentId());
        assertThat(masterBStillActive).as("master B's own assignment must be untouched").isTrue();
    }

    // ── Case 5 — past COMPLETED booking history stays fully readable (D1's whole reason) ────────

    @Test
    @DisplayName("Case 5: a past COMPLETED booking through the deactivated assignment still reads "
            + "back with its provider, service name and price_at_booking")
    void should_keepCompletedBookingReadable_when_assignmentDeactivated() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c5-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 5 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);
        UUID clientId = insertClientUser();
        UUID bookingId = insertBooking(salonId, masterId, assignment.assignmentId(), clientId,
                "COMPLETED", "-2 days", new BigDecimal("350.00"));

        assertThat(unassign(ownerToken, salonId, masterId, assignment.definitionId()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class);
        assertThat(resp.getStatusCode())
                .as("D1 — booking history through a deactivated assignment must stay readable")
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<java.util.Map<String, Object>>>() {}).data();
        assertThat(body.get("serviceName")).isNotNull();
        assertThat(body.get("priceAtBooking")).isNotNull();
        // "its provider" — the fixture-created master has no first/last name (nullable per V1:6,
        // never set by ServiceTestFixtures#createSalonMaster), so pin the provider identity via
        // masterId rather than a name field the fixture leaves null.
        assertThat(body.get("masterId")).isEqualTo(masterId.toString());
    }

    // ── Case 6 — D4 refuses with 409 on a future CONFIRMED booking, nothing written ─────────────

    @Test
    @DisplayName("Case 6: 409 when the master has a future CONFIRMED booking for THIS service — "
            + "the assignment stays is_active = true and the booking stays CONFIRMED (D4; Phase "
            + "308 inverts this case, it is not deleted)")
    void should_refuseWith409_when_futureConfirmedBookingExistsForThisAssignment() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c6-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 6 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);
        UUID clientId = insertClientUser();
        UUID bookingId = insertBooking(salonId, masterId, assignment.assignmentId(), clientId,
                "CONFIRMED", "+2 days", new BigDecimal("350.00"));

        ResponseEntity<String> resp = unassign(ownerToken, salonId, masterId, assignment.definitionId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Boolean stillActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE id = ?", Boolean.class, assignment.assignmentId());
        assertThat(stillActive).as("D4 — nothing written on refusal").isTrue();
        String bookingStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("CONFIRMED");
    }

    // ── Case 7 — the D4 count is scoped to the ASSIGNMENT, not the master ───────────────────────

    @Test
    @DisplayName("Case 7: a future CONFIRMED booking for a DIFFERENT service of the same master "
            + "does NOT block — the count is scoped to the assignment, not the master")
    void should_notBlock_when_futureConfirmedBookingIsForDifferentService() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c7-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 7 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        List<ServiceTestFixtures.SeededServiceType> types = fixtures.activeSelectableServiceTypes(2);
        Assignment targetAssignment = bulkCreateOneService(ownerToken, salonId, masterId, types.get(0).id());
        Assignment otherAssignment = bulkCreateOneService(ownerToken, salonId, masterId, types.get(1).id());
        UUID clientId = insertClientUser();
        // Future CONFIRMED booking on the OTHER assignment, not the one being unassigned.
        insertBooking(salonId, masterId, otherAssignment.assignmentId(), clientId,
                "CONFIRMED", "+2 days", new BigDecimal("350.00"));

        ResponseEntity<String> resp = unassign(ownerToken, salonId, masterId, targetAssignment.definitionId());

        assertThat(resp.getStatusCode())
                .as("case 7 — a future booking for a DIFFERENT assignment must not block")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── Case 8 — D6: re-assigning after unassign REACTIVATES the row, no duplicate ──────────────

    @Test
    @DisplayName("Case 8: re-assigning after unassign — 201, and master_services still holds "
            + "exactly ONE row for the pair, now is_active = true (D6)")
    void should_reactivateExistingRow_when_reassignedAfterUnassign() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c8-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 8 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);
        assertThat(unassign(ownerToken, salonId, masterId, assignment.definitionId()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        var request = new AssignServiceToMasterRequest(assignment.definitionId(), null, null, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(ownerToken)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Long.class, masterId, assignment.definitionId());
        assertThat(rowCount).as("D6 — reactivation, never a second row").isEqualTo(1L);
        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Boolean.class, masterId, assignment.definitionId());
        assertThat(isActive).isTrue();
    }

    // ── Case 9 — D6's preserved arm: assigning an ACTIVE service still 409s ─────────────────────

    @Test
    @DisplayName("Case 9: assigning a service the master already ACTIVELY performs — 409 (D6's "
            + "preserved conflict arm)")
    void should_return409_when_assigningAlreadyActiveService() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c9-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 9 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);

        var request = new AssignServiceToMasterRequest(assignment.definitionId(), null, null, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(ownerToken)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ── Case 10 — SALON_ADMIN of the salon may unassign (D5) ────────────────────────────────────

    @Test
    @DisplayName("Case 10: SALON_ADMIN of the salon — 204 (D5)")
    void should_return204_when_salonAdminUnassigns() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c10-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 10 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);
        String adminToken = fixtures.createSalonAdminAndGetToken(
                salonId, "admin-307-c10-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = unassign(adminToken, salonId, masterId, assignment.definitionId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── Case 11 — SALON_MASTER (read-only) may not unassign ─────────────────────────────────────

    @Test
    @DisplayName("Case 11: SALON_MASTER (read-only role) — 403; the assignment is untouched")
    void should_return403_when_salonMasterAttemptsUnassign() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c11-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 11 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);
        String salonMasterToken = fixtures.createSalonMasterAndGetToken(
                salonId, "readonly-307-c11-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = unassign(salonMasterToken, salonId, masterId, assignment.definitionId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Boolean stillActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE id = ?", Boolean.class, assignment.assignmentId());
        assertThat(stillActive).isTrue();
    }

    // ── Case 12 — an owner of a DIFFERENT salon may not unassign ────────────────────────────────

    @Test
    @DisplayName("Case 12: SALON_OWNER of a different salon — 403")
    void should_return403_when_differentSalonOwnerAttemptsUnassign() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c12-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 12 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);
        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c12-other-" + System.nanoTime() + "@beautica.test");
        fixtures.createSalon(otherOwnerToken, "Phase 307 Case 12 Other Salon");

        ResponseEntity<String> resp = unassign(otherOwnerToken, salonId, masterId, assignment.definitionId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Case 13 — masterId belongs to another salon: 403/404, indistinguishable from nonexistent ──

    @Test
    @DisplayName("Case 13: masterId of a master in ANOTHER salon — 403/404, indistinguishable from "
            + "nonexistent (D5 masterBelongsToSalon IDOR guard)")
    void should_denyIndistinguishably_when_masterBelongsToAnotherSalon() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c13-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 13 Salon A");
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        UUID masterInSalonA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterInSalonA);
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterInSalonA, typeId);

        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c13-other-" + System.nanoTime() + "@beautica.test");
        UUID salonB = fixtures.createSalon(otherOwnerToken, "Phase 307 Case 13 Salon B");
        UUID masterInSalonB = fixtures.createSalonMaster(salonB);

        ResponseEntity<String> resp = unassign(ownerToken, salonId, masterInSalonB, assignment.definitionId());

        assertThat(resp.getStatusCode())
                .as("D5 — a master from another salon must be denied, whichever status is chosen")
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    // ── Case 14 — idempotent by row state: a second DELETE is 404, no write (D7) ────────────────

    @Test
    @DisplayName("Case 14: a second DELETE of the same pair — 404, no write (D7)")
    void should_return404_when_unassigningAlreadyInactivePair() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c14-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 14 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);
        assertThat(unassign(ownerToken, salonId, masterId, assignment.definitionId()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> secondResp = unassign(ownerToken, salonId, masterId, assignment.definitionId());

        assertThat(secondResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Case 15 — CLIENT may not unassign ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case 15: CLIENT — 403")
    void should_return403_when_clientAttemptsUnassign() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c15-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 15 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);
        String clientToken = fixtures.createClientAndGetToken(
                "client-307-c15-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = unassign(clientToken, salonId, masterId, assignment.definitionId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Case 16 — afterCommit eviction: the very next catalogue read reflects the unassign ──────

    @Test
    @DisplayName("Case 16: the salon catalogue read immediately after the unassign reflects it — "
            + "no manual eviction workaround, proving Phase 304's afterCommit eviction fired")
    void should_reflectUnassignImmediately_when_firstGetAfterWrite() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c16-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 16 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);
        // Populate the cache with the pre-write (service present) catalogue — no manual eviction
        // from here on.
        assertThat(catalogueServiceIdsNoEviction(salonId)).contains(assignment.definitionId());

        assertThat(unassign(ownerToken, salonId, masterId, assignment.definitionId()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(catalogueServiceIdsNoEviction(salonId))
                .as("the very next catalogue read (no manual eviction) must reflect the unassign — "
                        + "a missing afterCommit eviction would keep serving the stale, pre-write "
                        + "catalogue for the whole 60s TTL")
                .doesNotContain(assignment.definitionId());
    }

    // ── Case 17 — serviceDefId belongs to a DIFFERENT salon's definition ───────────────────────────

    @Test
    @DisplayName("Case 17: serviceDefId belongs to a definition owned by ANOTHER salon — 404 "
            + "(no assignment row links salon A's master to it; defense-in-depth ownerId check "
            + "added for this Phase 307 audit finding is unreachable via the public API today)")
    void should_return404_when_serviceDefIdBelongsToAnotherSalon_noAssignmentExists() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c17-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 17 Salon A");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        // Salon A's master has an existing active assignment to a service it actually offers —
        // proves this isn't merely "master has zero assignments".
        bulkCreateOneService(ownerToken, salonId, masterId, typeId);

        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c17-other-" + System.nanoTime() + "@beautica.test");
        UUID salonB = fixtures.createSalon(otherOwnerToken, "Phase 307 Case 17 Salon B");
        // A bare, salon-B-owned definition with no assignment to ANY master — never assigned to
        // salon A's master, so findByMasterIdAndServiceDefinitionId resolves empty regardless of
        // the new ownerId check below it.
        UUID salonBDefinitionId = fixtures.createServiceDefinition(
                otherOwnerToken, salonB, "Phase 307 Case 17 Salon B Service");

        ResponseEntity<String> resp = unassign(ownerToken, salonId, masterId, salonBDefinitionId);

        assertThat(resp.getStatusCode())
                .as("no master_services row links salon A's master to salon B's definition, so this "
                        + "is the pre-existing 404 (no assignment found) — the new ownerId "
                        + "re-validation in unassignServiceFromMaster is defense-in-depth against a "
                        + "future invariant break, not something this test can reach")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Case 18 — MEDIUM-1/2 scoping guard: two services on one master, unassign ONE ───────────

    @Test
    @DisplayName("Case 18: one master with TWO services assigned — unassigning service A leaves "
            + "service B's assignment ACTIVE (Phase 307 perf audit LOW-5, scoping guard for the "
            + "JOIN-FETCH collapse of findByMasterIdAndServiceDefinitionId)")
    void should_leaveOtherServiceActive_when_unassigningOneOfTwoServicesForSameMaster() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c18-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 18 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        List<ServiceTestFixtures.SeededServiceType> types = fixtures.activeSelectableServiceTypes(2);
        Assignment serviceA = bulkCreateOneService(ownerToken, salonId, masterId, types.get(0).id());
        Assignment serviceB = bulkCreateOneService(ownerToken, salonId, masterId, types.get(1).id());

        ResponseEntity<String> resp = unassign(ownerToken, salonId, masterId, serviceA.definitionId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Boolean serviceAActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE id = ?", Boolean.class, serviceA.assignmentId());
        assertThat(serviceAActive)
                .as("the finder must resolve service A's row, not any row for this master")
                .isFalse();
        Boolean serviceBActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE id = ?", Boolean.class, serviceB.assignmentId());
        assertThat(serviceBActive)
                .as("a scoping bug in the JOIN-FETCH rewrite would silently touch or resolve the "
                        + "WRONG row when a master has more than one active assignment")
                .isTrue();
    }

    // ── Case 19 — bulk-path D6 end-to-end: re-add through POST .../services/bulk ───────────────

    @Test
    @DisplayName("Case 19: unassign, then re-add through the BULK endpoint — 201, and "
            + "master_services holds exactly ONE row for the pair, now is_active = true (Phase 307 "
            + "audit LOW-7, end-to-end proof of the bulk-path D6 reactivation branch)")
    void should_reactivateExistingRow_when_reassignedAfterUnassignThroughBulkEndpoint() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-307-c19-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 307 Case 19 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, typeId);
        assertThat(unassign(ownerToken, salonId, masterId, assignment.definitionId()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        BulkServiceItemRequest item = new BulkServiceItemRequest(
                typeId, 60, PriceType.FIXED, new BigDecimal("350.00"), null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(List.of(item)), fixtures.bearerHeaders(ownerToken)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("the pre-registered backlog warning: this used to throw an opaque 409 at flush "
                        + "instead of reactivating the master's own inactive row")
                .isEqualTo(HttpStatus.CREATED);
        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Long.class, masterId, assignment.definitionId());
        assertThat(rowCount).as("D6 bulk path — reactivation, never a second row").isEqualTo(1L);
        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Boolean.class, masterId, assignment.definitionId());
        assertThat(isActive).isTrue();
    }

    // ── shared setup + HTTP plumbing ────────────────────────────────────────────────────────────

    private record Assignment(UUID assignmentId, UUID definitionId) {
    }

    /** Bulk-creates exactly ONE FIXED-price service for {@code masterId} via the salon on-behalf endpoint. */
    private Assignment bulkCreateOneService(
            String ownerToken, UUID salonId, UUID masterId, UUID serviceTypeId) throws Exception {
        BulkServiceItemRequest item = new BulkServiceItemRequest(
                serviceTypeId, 60, PriceType.FIXED, new BigDecimal("350.00"), null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(List.of(item)), fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MasterServiceResponse created = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data().get(0);
        return new Assignment(created.id(), created.serviceDefinition().id());
    }

    private ResponseEntity<String> unassign(String token, UUID salonId, UUID masterId, UUID serviceDefId) {
        return restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/" + serviceDefId,
                HttpMethod.DELETE, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private UUID insertClientUser() {
        UUID clientId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                clientId, "unassign-client-" + UUID.randomUUID() + "@beautica.test",
                passwordEncoder.encode(ServiceTestFixtures.TEST_PASSWORD));
        return clientId;
    }

    /**
     * Direct-SQL booking insert (mirrors {@code BookingCommentPersistenceIT#insertBookingWithStatus}):
     * no create endpoint lets a test pin an arbitrary past/future {@code starts_at} + status pair in
     * one call. {@code startsAtOffset}/{@code endsAtOffset} are Postgres interval literals, e.g.
     * {@code "+2 days"} or {@code "-2 days"}, both applied relative to {@code NOW()}.
     */
    private UUID insertBooking(UUID salonId, UUID masterId, UUID masterServiceId, UUID clientId,
            String status, String startsAtOffset, BigDecimal price) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW() + ?::interval, NOW() + ?::interval + interval '1 hour', "
                        + "?, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status,
                startsAtOffset, startsAtOffset, price);
        return bookingId;
    }

    /** Reads the salon catalogue after explicitly evicting the cache — the D2 query contract alone. */
    private List<UUID> catalogueServiceIds(UUID salonId) throws Exception {
        Cache cache = cacheManager.getCache(SALON_CATALOG_CACHE);
        assertThat(cache).as("salon-service-catalog must be a registered cache").isNotNull();
        cache.evict(salonId);
        return catalogueServiceIdsNoEviction(salonId);
    }

    /** GETs the public salon catalogue exactly as a real client would — no eviction workaround. */
    private List<UUID> catalogueServiceIdsNoEviction(UUID salonId) throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SalonServiceCatalogResponse catalog = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {}).data();
        return catalog.categories().stream()
                .flatMap(group -> group.services().stream())
                .map(ServiceDefinitionResponse::id)
                .toList();
    }
}
