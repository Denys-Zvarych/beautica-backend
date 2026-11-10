package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.UpdateMasterServiceBandRequest;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 463 — the legacy band PATCH
 * {@code PATCH /api/v1/salons/{salonId}/masters/{masterId}/services/{serviceDefId}} is owner/admin
 * only. A {@code SALON_MASTER} never sets or edits their own durations or prices (locked
 * decision), so their own row now 403s; owner and admin of the salon keep 200; an admin of another
 * salon and a client 403; an independent master's own service paths are unaffected.
 *
 * <p>Acceptance 3 (the same matrix on a second copy of one type) needs phase 464, which is not on
 * this branch; the gate is per {@code master_services} row, so this matrix covers it by construction.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Phase 463 — salon master band PATCH lockdown (owner/admin only)")
class SalonMasterBandLockdownIT extends AbstractIntegrationTest {

    private static final BigDecimal SEEDED_PRICE = new BigDecimal("600.00");
    private static final BigDecimal EDITED_PRICE = new BigDecimal("750.00");
    private static final BigDecimal OWNER_PRICE = new BigDecimal("650.00");
    private static final int OWNER_DURATION = 90;

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

    @Test
    @DisplayName("SALON_MASTER patches their OWN row — 403 FORBIDDEN, nothing written (was 200)")
    void should_return403_when_salonMasterPatchesOwnBand() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-sm"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 SM Salon");
        var self = fixtures.createSalonMasterWithRowAndGetToken(salonId, uniqueEmail("self"));
        Assignment own = bulkCreateFixed(ownerToken, salonId, self.masterId());

        ResponseEntity<String> resp = patchBand(self.token(), salonId, self.masterId(), own.definitionId());

        assertThat(resp.getStatusCode()).as("own row, body=%s", resp.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).contains("Access denied");
        assertThat(bandRow(own.assignmentId()).get("price_override"))
                .as("the refused self-edit must write nothing").isNull();
    }

    @Test
    @DisplayName("SALON_OWNER patches a master's row in their salon — 200, band written")
    void should_return200_when_ownerPatchesBand() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Owner Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        Assignment row = bulkCreateFixed(ownerToken, salonId, masterId);

        ResponseEntity<String> resp = patchBand(ownerToken, salonId, masterId, row.definitionId());

        assertThat(resp.getStatusCode()).as("owner, body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat((BigDecimal) bandRow(row.assignmentId()).get("price_override")).isEqualByComparingTo(EDITED_PRICE);
    }

    @Test
    @DisplayName("SALON_ADMIN of the salon patches a master's row — 200, band written")
    void should_return200_when_adminOfSalonPatchesBand() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-adm"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Admin Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        Assignment row = bulkCreateFixed(ownerToken, salonId, masterId);
        String adminToken = fixtures.createSalonAdminAndGetToken(salonId, uniqueEmail("admin"));

        ResponseEntity<String> resp = patchBand(adminToken, salonId, masterId, row.definitionId());

        assertThat(resp.getStatusCode()).as("admin, body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat((BigDecimal) bandRow(row.assignmentId()).get("price_override")).isEqualByComparingTo(EDITED_PRICE);
    }

    @Test
    @DisplayName("SALON_ADMIN of ANOTHER salon — 403, nothing written")
    void should_return403_when_foreignAdminPatchesBand() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-for"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Target Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        Assignment row = bulkCreateFixed(ownerToken, salonId, masterId);
        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-other"));
        UUID otherSalonId = fixtures.createSalon(otherOwnerToken, "Phase 463 Other Salon");
        String foreignAdminToken = fixtures.createSalonAdminAndGetToken(otherSalonId, uniqueEmail("admin-for"));

        ResponseEntity<String> resp = patchBand(foreignAdminToken, salonId, masterId, row.definitionId());

        assertThat(resp.getStatusCode()).as("foreign admin").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bandRow(row.assignmentId()).get("price_override")).as("nothing written").isNull();
    }

    @Test
    @DisplayName("CLIENT — 403, nothing written")
    void should_return403_when_clientPatchesBand() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-cli"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Client Probe Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        Assignment row = bulkCreateFixed(ownerToken, salonId, masterId);
        String clientToken = fixtures.createClientAndGetToken(uniqueEmail("client"));

        ResponseEntity<String> resp = patchBand(clientToken, salonId, masterId, row.definitionId());

        assertThat(resp.getStatusCode()).as("client").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bandRow(row.assignmentId()).get("price_override")).as("nothing written").isNull();
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER unaffected — own service PATCH still 200; the salon-scoped band "
            + "PATCH on their own row stays 403 as before")
    void should_keepIndependentMasterPathsUnchanged() throws Exception {
        String email = uniqueEmail("indep");
        String indepToken = fixtures.createIndependentMasterAndGetToken(email);
        UUID indepServiceId = fixtures.createIndependentMasterService(indepToken, "Phase 463 Indep Nails");
        UUID indepMasterId = fixtures.resolveMasterIdForUserEmail(email);
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-ind"));
        UUID anySalonId = fixtures.createSalon(ownerToken, "Phase 463 Indep Probe Salon");
        var rename = new UpdateServiceDefinitionRequest(
                "Phase 463 Indep Nails Renamed", null, null, null, null, null, null, null, null, null);

        ResponseEntity<String> ownEdit = restTemplate.exchange(
                "/api/v1/services/" + indepServiceId, HttpMethod.PATCH,
                new HttpEntity<>(rename, fixtures.bearerHeaders(indepToken)), String.class);
        ResponseEntity<String> bandOnSalonPath = patchBand(indepToken, anySalonId, indepMasterId, indepServiceId);

        assertThat(ownEdit.getStatusCode()).as("own service edit, body=%s", ownEdit.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(bandOnSalonPath.getStatusCode()).as("salon-scoped band PATCH").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("SALON_MASTER patches price AND duration on their own row and on a PEER's row — "
            + "403 both, the owner-set price and duration stay byte-identical in the DB")
    void should_leavePriceAndDurationUnchanged_when_salonMasterPatchesOwnOrPeerRow() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-pd"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Price+Duration Salon");
        var self = fixtures.createSalonMasterWithRowAndGetToken(salonId, uniqueEmail("self-pd"));
        UUID peerMasterId = fixtures.createSalonMaster(salonId);
        Assignment own = bulkCreateFixed(ownerToken, salonId, self.masterId());
        Assignment peer = bulkCreateFixed(ownerToken, salonId, peerMasterId);
        var ownerBand = new UpdateMasterServiceBandRequest(PriceType.FIXED, OWNER_PRICE, null, OWNER_DURATION, null, null);
        assertThat(patchBand(ownerToken, salonId, self.masterId(), own.definitionId(), ownerBand).getStatusCode())
                .as("owner seeds own row").isEqualTo(HttpStatus.OK);
        assertThat(patchBand(ownerToken, salonId, peerMasterId, peer.definitionId(), ownerBand).getStatusCode())
                .as("owner seeds peer row").isEqualTo(HttpStatus.OK);
        var masterAttempt = new UpdateMasterServiceBandRequest(PriceType.FIXED, EDITED_PRICE, null, 30, null, null);

        ResponseEntity<String> ownResp = patchBand(self.token(), salonId, self.masterId(), own.definitionId(), masterAttempt);
        ResponseEntity<String> peerResp = patchBand(self.token(), salonId, peerMasterId, peer.definitionId(), masterAttempt);

        assertThat(ownResp.getStatusCode()).as("own row, body=%s", ownResp.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(peerResp.getStatusCode()).as("peer row, body=%s", peerResp.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        for (Assignment row : List.of(own, peer)) {
            Map<String, Object> db = bandRow(row.assignmentId());
            assertThat((BigDecimal) db.get("price_override")).as("price kept on %s", row.assignmentId())
                    .isEqualByComparingTo(OWNER_PRICE);
            assertThat(db.get("duration_override_minutes")).as("duration kept on %s", row.assignmentId())
                    .isEqualTo(OWNER_DURATION);
        }
    }

    @Test
    @DisplayName("SALON_OWNER patches with a serviceDefId belonging to ANOTHER salon — 404, the "
            + "foreign row is not touched")
    void should_return404_when_ownerPatchesWithForeignSalonServiceDef() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-xdef"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Home Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-xdef-other"));
        UUID otherSalonId = fixtures.createSalon(otherOwnerToken, "Phase 463 Foreign Def Salon");
        UUID otherMasterId = fixtures.createSalonMaster(otherSalonId);
        Assignment foreign = bulkCreateFixed(otherOwnerToken, otherSalonId, otherMasterId);

        ResponseEntity<String> resp = patchBand(ownerToken, salonId, masterId, foreign.definitionId());

        assertThat(resp.getStatusCode()).as("foreign serviceDefId, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bandRow(foreign.assignmentId()).get("price_override")).as("foreign row untouched").isNull();
    }

    @Test
    @DisplayName("SALON_OWNER patches a master of ANOTHER salon under their own salonId — 403 "
            + "(masterBelongsToSalon), nothing written")
    void should_return403_when_ownerPatchesForeignMasterUnderOwnSalonPath() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-xm"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Path Salon");
        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-xm-other"));
        UUID otherSalonId = fixtures.createSalon(otherOwnerToken, "Phase 463 Foreign Master Salon");
        UUID foreignMasterId = fixtures.createSalonMaster(otherSalonId);
        Assignment foreign = bulkCreateFixed(otherOwnerToken, otherSalonId, foreignMasterId);

        ResponseEntity<String> resp = patchBand(ownerToken, salonId, foreignMasterId, foreign.definitionId());

        assertThat(resp.getStatusCode()).as("foreign master, body=%s", resp.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bandRow(foreign.assignmentId()).get("price_override")).as("foreign row untouched").isNull();
    }

    @Test
    @DisplayName("SALON_MASTER can still READ their own services (read gate unchanged) — 200 with "
            + "the owner-set price, while the same row's PATCH is 403")
    void should_return200_when_salonMasterReadsOwnServices() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-rd"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Read Salon");
        var self = fixtures.createSalonMasterWithRowAndGetToken(salonId, uniqueEmail("self-rd"));
        Assignment own = bulkCreateFixed(ownerToken, salonId, self.masterId());

        ResponseEntity<String> read = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + self.masterId() + "/services", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(self.token())), String.class);

        assertThat(read.getStatusCode()).as("own read, body=%s", read.getBody()).isEqualTo(HttpStatus.OK);
        List<MasterServiceResponse> rows = objectMapper.readValue(read.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data();
        assertThat(rows).extracting(MasterServiceResponse::id).containsExactly(own.assignmentId());
        assertThat(rows.get(0).serviceDefinition().id()).isEqualTo(own.definitionId());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private record Assignment(UUID assignmentId, UUID definitionId) {
    }

    private static String uniqueEmail(String prefix) {
        return "p463-" + prefix + "-" + System.nanoTime() + "@beautica.test";
    }

    private Assignment bulkCreateFixed(String ownerToken, UUID salonId, UUID masterId) throws Exception {
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        var item = new BulkServiceItemRequest(typeId, 60, PriceType.FIXED, SEEDED_PRICE, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(List.of(item)), fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).as("bulk setup, body=%s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        MasterServiceResponse created = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data().get(0);
        return new Assignment(created.id(), created.serviceDefinition().id());
    }

    private ResponseEntity<String> patchBand(String token, UUID salonId, UUID masterId, UUID serviceDefId) {
        return patchBand(token, salonId, masterId, serviceDefId,
                new UpdateMasterServiceBandRequest(PriceType.FIXED, EDITED_PRICE, null, null, null, null));
    }

    private ResponseEntity<String> patchBand(String token, UUID salonId, UUID masterId, UUID serviceDefId,
                                             UpdateMasterServiceBandRequest request) {
        return restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/" + serviceDefId,
                HttpMethod.PATCH, new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    private Map<String, Object> bandRow(UUID assignmentId) {
        return jdbcTemplate.queryForMap(
                "SELECT price_override, duration_override_minutes FROM master_services WHERE id = ?", assignmentId);
    }
}
