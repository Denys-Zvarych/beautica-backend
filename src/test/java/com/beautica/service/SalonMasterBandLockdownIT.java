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
import java.util.ArrayList;
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
 * <p>Acceptance 3 — the same matrix on the SECOND copy of one service type (phases 464–466) — is
 * pinned by {@link #should_applyRoleMatrixOnSecondCopyRow_when_salonHoldsTwoCopiesOfOneType}; the
 * per-row gate across two DIFFERENT services is pinned by
 * {@link #should_applyRoleMatrixPerRow_when_masterHasTwoServices}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Phase 463 — salon master band PATCH lockdown (owner/admin only)")
class SalonMasterBandLockdownIT extends AbstractIntegrationTest {

    private static final BigDecimal SEEDED_PRICE = new BigDecimal("600.00");
    private static final BigDecimal EDITED_PRICE = new BigDecimal("750.00");
    private static final BigDecimal OWNER_PRICE = new BigDecimal("650.00");
    private static final int OWNER_DURATION = 90;
    private static final String ACCESS_DENIED = "Access denied";

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
        assertThat(resp.getBody()).contains(ACCESS_DENIED);
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
        assertThat(resp.getBody()).contains(ACCESS_DENIED);
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
        assertThat(resp.getBody()).contains(ACCESS_DENIED);
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
        assertThat(bandOnSalonPath.getBody()).contains(ACCESS_DENIED);
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
        assertThat(List.of(ownResp.getBody(), peerResp.getBody())).allSatisfy(b -> assertThat(b).contains(ACCESS_DENIED));
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
        assertThat(resp.getBody()).contains(ACCESS_DENIED);
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

    @Test
    @DisplayName("Anonymous band PATCH — 401, nothing written")
    void should_return401_when_anonymousPatchesBand() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-anon"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Anon Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        Assignment row = bulkCreateFixed(ownerToken, salonId, masterId);
        var request = new UpdateMasterServiceBandRequest(PriceType.FIXED, EDITED_PRICE, null, null, null, null);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/" + row.definitionId(),
                HttpMethod.PATCH, new HttpEntity<>(request), String.class);

        assertThat(resp.getStatusCode()).as("anonymous, body=%s", resp.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bandRow(row.assignmentId()).get("price_override")).as("nothing written").isNull();
    }

    @Test
    @DisplayName("SALON_OWNER (always a master) patches their OWN master row — 200, band written")
    void should_return200_when_ownerPatchesOwnMasterRow() throws Exception {
        String ownerEmail = uniqueEmail("owner-self");
        String ownerToken = fixtures.createSalonOwnerAndGetToken(ownerEmail);
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Owner Self Salon");
        UUID ownerMasterId = fixtures.resolveMasterIdForUserEmail(ownerEmail);
        Assignment own = bulkCreateFixed(ownerToken, salonId, ownerMasterId);

        ResponseEntity<String> resp = patchBand(ownerToken, salonId, ownerMasterId, own.definitionId());

        assertThat(resp.getStatusCode()).as("owner own row, body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat((BigDecimal) bandRow(own.assignmentId()).get("price_override")).isEqualByComparingTo(EDITED_PRICE);
    }

    @Test
    @DisplayName("Two DIFFERENT services of one salon master — the role matrix holds on each row: the "
            + "master 403 on both, owner 200 on row 1, admin 200 on row 2, foreign admin and client "
            + "403, and each write touches only its own row")
    void should_applyRoleMatrixPerRow_when_masterHasTwoServices() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-two"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Two Services Salon");
        var self = fixtures.createSalonMasterWithRowAndGetToken(salonId, uniqueEmail("self-two"));
        Assignment first = bulkCreateFixed(ownerToken, salonId, self.masterId(), 0);
        Assignment second = bulkCreateFixed(ownerToken, salonId, self.masterId(), 1);
        String adminToken = fixtures.createSalonAdminAndGetToken(salonId, uniqueEmail("admin-two"));
        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-two-other"));
        UUID otherSalonId = fixtures.createSalon(otherOwnerToken, "Phase 463 Two Services Other Salon");
        String foreignAdminToken = fixtures.createSalonAdminAndGetToken(otherSalonId, uniqueEmail("admin-two-for"));
        String clientToken = fixtures.createClientAndGetToken(uniqueEmail("client-two"));
        assertThat(second.definitionId()).as("two distinct services").isNotEqualTo(first.definitionId());

        List<ResponseEntity<String>> denied = new ArrayList<>();
        for (Assignment row : List.of(first, second)) {
            for (String token : List.of(self.token(), foreignAdminToken, clientToken)) {
                denied.add(patchBand(token, salonId, self.masterId(), row.definitionId()));
            }
        }
        var ownerBand = new UpdateMasterServiceBandRequest(PriceType.FIXED, OWNER_PRICE, null, null, null, null);
        ResponseEntity<String> ownerOnFirst = patchBand(ownerToken, salonId, self.masterId(), first.definitionId(), ownerBand);
        ResponseEntity<String> adminOnSecond = patchBand(adminToken, salonId, self.masterId(), second.definitionId());

        assertThat(denied).as("master, foreign admin, client on both rows").hasSize(6).allSatisfy(r -> {
            assertThat(r.getStatusCode()).as("body=%s", r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody()).contains(ACCESS_DENIED);
        });
        assertThat(ownerOnFirst.getStatusCode()).as("owner row 1, body=%s", ownerOnFirst.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(adminOnSecond.getStatusCode()).as("admin row 2, body=%s", adminOnSecond.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat((BigDecimal) bandRow(first.assignmentId()).get("price_override")).as("row 1 = owner price only")
                .isEqualByComparingTo(OWNER_PRICE);
        assertThat((BigDecimal) bandRow(second.assignmentId()).get("price_override")).as("row 2 = admin price only")
                .isEqualByComparingTo(EDITED_PRICE);
    }

    @Test
    @DisplayName("AC3: salon holds TWO copies of one service type (60 / 90 хв via salon bulk); on the "
            + "master's row of the SECOND copy the master, foreign admin and client 403 with the band "
            + "unchanged, owner and admin 200, and the first copy's row (and a peer's row on the same copy) "
            + "is never touched")
    void should_applyRoleMatrixOnSecondCopyRow_when_salonHoldsTwoCopiesOfOneType() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-copy"));
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 463 Second Copy Salon");
        var self = fixtures.createSalonMasterWithRowAndGetToken(salonId, uniqueEmail("self-copy"));
        UUID peerMasterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment firstCopy = bulkCreate(ownerToken, salonId, peerMasterId, typeId, 60);
        Assignment peerOnSecondCopy = bulkCreate(ownerToken, salonId, peerMasterId, typeId, 90);
        Assignment secondCopy = bulkCreate(ownerToken, salonId, self.masterId(), typeId, 90);
        String adminToken = fixtures.createSalonAdminAndGetToken(salonId, uniqueEmail("admin-copy"));
        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(uniqueEmail("owner-copy-other"));
        UUID otherSalonId = fixtures.createSalon(otherOwnerToken, "Phase 463 Second Copy Other Salon");
        String foreignAdminToken = fixtures.createSalonAdminAndGetToken(otherSalonId, uniqueEmail("admin-copy-for"));
        String clientToken = fixtures.createClientAndGetToken(uniqueEmail("client-copy"));
        assertThat(peerOnSecondCopy.definitionId()).as("the peer's 90 bulk mints a second salon copy")
                .isNotEqualTo(firstCopy.definitionId());
        assertThat(secondCopy.definitionId()).as("the master is assigned that second copy")
                .isEqualTo(peerOnSecondCopy.definitionId());
        assertThat(jdbcTemplate.queryForList(
                "SELECT service_type_id FROM service_definitions WHERE id IN (?, ?)", UUID.class,
                firstCopy.definitionId(), secondCopy.definitionId()))
                .as("both copies are of the same service type").containsExactly(typeId, typeId);
        Map<String, Object> firstCopyBefore = bandRow(firstCopy.assignmentId());
        Map<String, Object> peerSecondCopyBefore = bandRow(peerOnSecondCopy.assignmentId());
        var masterAttempt = new UpdateMasterServiceBandRequest(PriceType.FIXED, EDITED_PRICE, null, 30, null, null);

        ResponseEntity<String> selfResp = patchBand(self.token(), salonId, self.masterId(), secondCopy.definitionId(), masterAttempt);
        Map<String, Object> afterSelf = bandRow(secondCopy.assignmentId());
        ResponseEntity<String> foreignResp = patchBand(foreignAdminToken, salonId, self.masterId(), secondCopy.definitionId());
        ResponseEntity<String> clientResp = patchBand(clientToken, salonId, self.masterId(), secondCopy.definitionId());
        Map<String, Object> afterDenied = bandRow(secondCopy.assignmentId());
        var ownerBand = new UpdateMasterServiceBandRequest(PriceType.FIXED, OWNER_PRICE, null, OWNER_DURATION, null, null);
        ResponseEntity<String> ownerResp = patchBand(ownerToken, salonId, self.masterId(), secondCopy.definitionId(), ownerBand);
        Map<String, Object> afterOwner = bandRow(secondCopy.assignmentId());
        ResponseEntity<String> adminResp = patchBand(adminToken, salonId, self.masterId(), secondCopy.definitionId());
        Map<String, Object> afterAdmin = bandRow(secondCopy.assignmentId());

        assertThat(selfResp.getStatusCode()).as("self on 2nd copy, body=%s", selfResp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(selfResp.getBody()).contains(ACCESS_DENIED);
        assertThat(afterSelf.get("price_override")).as("self 403 writes no price").isNull();
        assertThat(afterSelf.get("duration_override_minutes")).as("self 403 writes no duration").isNull();
        assertThat(List.of(foreignResp, clientResp)).as("foreign admin, client").allSatisfy(r -> {
            assertThat(r.getStatusCode()).as("body=%s", r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody()).contains(ACCESS_DENIED);
        });
        assertThat(afterDenied.get("price_override")).as("denied writes leave the band unchanged").isNull();
        assertThat(ownerResp.getStatusCode()).as("owner on 2nd copy, body=%s", ownerResp.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat((BigDecimal) afterOwner.get("price_override")).as("owner price").isEqualByComparingTo(OWNER_PRICE);
        assertThat(afterOwner.get("duration_override_minutes")).as("owner duration").isEqualTo(OWNER_DURATION);
        assertThat(adminResp.getStatusCode()).as("admin on 2nd copy, body=%s", adminResp.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat((BigDecimal) afterAdmin.get("price_override")).as("admin price").isEqualByComparingTo(EDITED_PRICE);
        assertThat(bandRow(firstCopy.assignmentId())).as("first copy's row untouched by every write")
                .isEqualTo(firstCopyBefore)
                .containsEntry("price_override", null)
                .containsEntry("duration_override_minutes", null);
        assertThat(bandRow(peerOnSecondCopy.assignmentId())).as("the peer's row on the same second copy untouched")
                .isEqualTo(peerSecondCopyBefore);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private record Assignment(UUID assignmentId, UUID definitionId) {
    }

    private static String uniqueEmail(String prefix) {
        return "p463-" + prefix + "-" + System.nanoTime() + "@beautica.test";
    }

    private Assignment bulkCreateFixed(String ownerToken, UUID salonId, UUID masterId) throws Exception {
        return bulkCreateFixed(ownerToken, salonId, masterId, 0);
    }

    private Assignment bulkCreateFixed(String ownerToken, UUID salonId, UUID masterId, int typeIndex)
            throws Exception {
        UUID typeId = fixtures.activeSelectableServiceTypes(typeIndex + 1).get(typeIndex).id();
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

    /** One unnamed FIXED item of {@code typeId} at {@code duration} through the salon bulk path. */
    private Assignment bulkCreate(String ownerToken, UUID salonId, UUID masterId, UUID typeId, int duration)
            throws Exception {
        ResponseEntity<String> resp = fixtures.postSalonBulk(ownerToken, salonId, masterId,
                List.of(ServiceTestFixtures.bulkItem(typeId, duration, SEEDED_PRICE.toPlainString())));
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
