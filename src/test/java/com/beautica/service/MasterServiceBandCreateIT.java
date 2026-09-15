package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.UpdateMasterServiceBandRequest;
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
 * Phase 312 — {@code POST /salons/{salonId}/masters/{masterId}/services}, the single-assign path,
 * gains the ability to set a full per-master price band at creation time, validated by the SAME
 * {@link com.beautica.service.dto.MasterServiceBand} truth table {@code PATCH
 * .../services/{serviceDefId}} (Phase 311) and {@code POST .../services/bulk} (this phase) share.
 *
 * <h2>The merge-unit hazard (case 9)</h2>
 * Phase 311's {@code V165} forbids a partial band at the DB layer
 * ({@code chk_master_service_price_mode}). Before this phase, {@code assignServiceToMaster} wrote
 * a bare {@code price_override} — case 9 is the Testcontainers proof that what this endpoint now
 * writes can never violate that constraint, which only a real database can show.
 *
 * <h2>D3 — the retired guard</h2>
 * Case 2 is the shape the FIRST DRAFT of this phase (and Phase 302's guard) would have rejected —
 * {@code RANGE} on a {@code FIXED} definition — proving the retirement is real, not just untested.
 *
 * <h2>REUSE-FIRST</h2>
 * Built on {@link ServiceTestFixtures}, the same harness {@code MasterServiceBandEditIT} and
 * {@code BulkServiceSetupIntegrationTest} use.
 */
@Import(TestSecurityConfig.class)
@DisplayName("POST /salons/{salonId}/masters/{masterId}/services — Phase 312 band-on-create")
class MasterServiceBandCreateIT extends AbstractIntegrationTest {

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

    // ── Case 1 — FIXED own band on a FIXED definition ───────────────────────────────────────────

    @Test
    @DisplayName("Case 1: single assign with FIXED 750 against a FIXED 600 definition — 201, all "
            + "three band columns correct by direct DB read")
    void should_setOwnFixedBand_when_assigningAgainstFixedDefinition() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-312-c1-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 312 Case 1 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = createFixedDefinition(ownerToken, salonId, "600.00");

        MasterServiceResponse assigned = assign(ownerToken, salonId, masterId,
                new AssignServiceToMasterRequest(defId, PriceType.FIXED, new BigDecimal("750.00"), null, null))
                .response();

        assertThat(assigned.priceType()).isEqualTo(PriceType.FIXED);
        assertThat(assigned.priceMin()).isEqualByComparingTo("750.00");
        Map<String, Object> row = bandRow(masterId, defId);
        assertThat(row.get("price_type_override")).isEqualTo("FIXED");
        assertThat((BigDecimal) row.get("price_override")).isEqualByComparingTo("750.00");
        assertThat(row.get("price_max_override")).isNull();
    }

    // ── Case 2 — the capability the first draft would have 400ed: RANGE on a FIXED definition ──

    @Test
    @DisplayName("Case 2: single assign with RANGE 500-800 against a FIXED definition — 201, "
            + "the case the first draft would 400 and the PATCH already accepts")
    void should_setOwnRangeBand_when_assigningAgainstFixedDefinition() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-312-c2-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 312 Case 2 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = createFixedDefinition(ownerToken, salonId, "500.00");

        MasterServiceResponse assigned = assign(ownerToken, salonId, masterId,
                new AssignServiceToMasterRequest(
                        defId, PriceType.RANGE, new BigDecimal("500.00"), new BigDecimal("800.00"), null))
                .response();

        assertThat(assigned.priceType()).isEqualTo(PriceType.RANGE);
        assertThat(assigned.priceMin()).isEqualByComparingTo("500.00");
        assertThat(assigned.priceMax()).isEqualByComparingTo("800.00");
        Map<String, Object> row = bandRow(masterId, defId);
        assertThat(row.get("price_type_override")).isEqualTo("RANGE");
        assertThat((BigDecimal) row.get("price_max_override")).isEqualByComparingTo("800.00");
    }

    // ── Case 3 — the inverse: FIXED own band on a RANGE definition ──────────────────────────────

    @Test
    @DisplayName("Case 3: single assign with FIXED 700 against a RANGE 400-900 definition — 201, "
            + "the master shows a single price")
    void should_setOwnFixedBand_when_assigningAgainstRangeDefinition() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-312-c3-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 312 Case 3 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = createRangeDefinition(ownerToken, salonId, "400.00", "900.00");

        MasterServiceResponse assigned = assign(ownerToken, salonId, masterId,
                new AssignServiceToMasterRequest(defId, PriceType.FIXED, new BigDecimal("700.00"), null, null))
                .response();

        assertThat(assigned.priceType()).isEqualTo(PriceType.FIXED);
        assertThat(assigned.priceMin()).isEqualByComparingTo("700.00");
        assertThat(assigned.priceMax()).isNull();
    }

    // ── Case 4 — no band fields at all: Inherited, the unchanged-caller regression ──────────────

    @Test
    @DisplayName("Case 4: single assign with no band fields — 201, all three columns NULL "
            + "(Inherited) — proves D1 is additive")
    void should_stayInherited_when_assigningWithNoBandFields() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-312-c4-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 312 Case 4 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = createFixedDefinition(ownerToken, salonId, "600.00");

        assign(ownerToken, salonId, masterId,
                new AssignServiceToMasterRequest(defId, null, null, null, null)).response();

        Map<String, Object> row = bandRow(masterId, defId);
        assertThat(row.get("price_type_override")).isNull();
        assertThat(row.get("price_override")).isNull();
        assertThat(row.get("price_max_override")).isNull();
    }

    // ── Cases 5-8 — every illegal band shape is a 400, nothing written ──────────────────────────

    @Test
    @DisplayName("Case 5: priceOverride with no priceType — 400, nothing written (D1, 311 D2)")
    void should_return400_when_priceOverrideHasNoPriceType() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-312-c5-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 312 Case 5 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = createFixedDefinition(ownerToken, salonId, "600.00");

        ResponseEntity<String> resp = assign(ownerToken, salonId, masterId,
                new AssignServiceToMasterRequest(defId, null, new BigDecimal("750.00"), null, null)).raw();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activeAssignmentExists(masterId, defId)).isFalse();
    }

    @Test
    @DisplayName("Case 6: RANGE with priceMax below priceOverride — 400, nothing written")
    void should_return400_when_rangeCeilingBelowFloor() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-312-c6-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 312 Case 6 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = createFixedDefinition(ownerToken, salonId, "600.00");

        ResponseEntity<String> resp = assign(ownerToken, salonId, masterId,
                new AssignServiceToMasterRequest(
                        defId, PriceType.RANGE, new BigDecimal("800.00"), new BigDecimal("500.00"), null)).raw();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activeAssignmentExists(masterId, defId)).isFalse();
    }

    @Test
    @DisplayName("Case 7: RANGE with no priceMax — 400, nothing written")
    void should_return400_when_rangeHasNoCeiling() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-312-c7-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 312 Case 7 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = createFixedDefinition(ownerToken, salonId, "600.00");

        ResponseEntity<String> resp = assign(ownerToken, salonId, masterId,
                new AssignServiceToMasterRequest(defId, PriceType.RANGE, new BigDecimal("500.00"), null, null)).raw();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activeAssignmentExists(masterId, defId)).isFalse();
    }

    @Test
    @DisplayName("Case 8: FIXED with a priceMax — 400, nothing written")
    void should_return400_when_fixedHasACeiling() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-312-c8-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 312 Case 8 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = createFixedDefinition(ownerToken, salonId, "600.00");

        ResponseEntity<String> resp = assign(ownerToken, salonId, masterId,
                new AssignServiceToMasterRequest(
                        defId, PriceType.FIXED, new BigDecimal("750.00"), new BigDecimal("900.00"), null)).raw();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activeAssignmentExists(masterId, defId)).isFalse();
    }

    // ── Case 9 — the merge-unit guard: nothing written can violate the V165 CHECK ────────────────

    @Test
    @DisplayName("Case 9: a single assign carrying a band completes without a "
            + "DataIntegrityViolationException — the row satisfies chk_master_service_price_mode")
    void should_writeACoherentRow_when_assigningWithABand() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-312-c9-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 312 Case 9 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = createRangeDefinition(ownerToken, salonId, "300.00", "600.00");

        ResponseEntity<String> resp = assign(ownerToken, salonId, masterId,
                new AssignServiceToMasterRequest(
                        defId, PriceType.RANGE, new BigDecimal("350.00"), new BigDecimal("700.00"), null)).raw();

        assertThat(resp.getStatusCode())
                .as("must be a clean 201, never a 500-flavoured DataIntegrityViolationException")
                .isEqualTo(HttpStatus.CREATED);

        // Directly reproduce the CHECK's own predicate against the persisted row — a query that
        // would raise if the row failed the constraint proves it did not, without depending on
        // Postgres re-validating an already-committed row.
        Long violating = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ? "
                        + "AND NOT ("
                        + "  (price_type_override IS NULL AND price_override IS NULL AND price_max_override IS NULL)"
                        + "  OR (price_type_override = 'FIXED' AND price_override IS NOT NULL AND price_max_override IS NULL)"
                        + "  OR (price_type_override = 'RANGE' AND price_override IS NOT NULL AND price_max_override IS NOT NULL"
                        + "      AND price_max_override >= price_override)"
                        + ")",
                Long.class, masterId, defId);
        assertThat(violating).as("zero rows may violate chk_master_service_price_mode's predicate").isZero();

        Map<String, Object> row = bandRow(masterId, defId);
        assertThat(row.get("price_type_override")).isEqualTo("RANGE");
        assertThat((BigDecimal) row.get("price_override")).isEqualByComparingTo("350.00");
        assertThat((BigDecimal) row.get("price_max_override")).isEqualByComparingTo("700.00");
    }

    // ── Case 10 — the POST and the PATCH agree on what is legal ─────────────────────────────────

    @Test
    @DisplayName("Case 10: assign a band, then PATCH it to a different band via 311 — the POST "
            + "and PATCH accept and reject exactly the same bodies")
    void should_agreeWithPatchEndpoint_when_bandsAreAssignedThenEdited() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-312-c10-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 312 Case 10 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = createFixedDefinition(ownerToken, salonId, "600.00");

        assign(ownerToken, salonId, masterId,
                new AssignServiceToMasterRequest(defId, PriceType.FIXED, new BigDecimal("750.00"), null, null))
                .response();

        // Every D3-table body illegal on the PATCH must be illegal on the POST too (checked above
        // via cases 5-8) — here we prove the converse direction: a body the POST accepted (its own
        // resulting band) is also accepted by the PATCH, and a PATCH edit to a NEW representable
        // band (RANGE on this FIXED definition) is accepted exactly as the POST accepts it (case 2).
        ResponseEntity<String> patchResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/" + defId, HttpMethod.PATCH,
                new HttpEntity<>(new UpdateMasterServiceBandRequest(
                                PriceType.RANGE, new BigDecimal("500.00"), new BigDecimal("800.00"), null, null, null),
                        fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(patchResp.getStatusCode())
                .as("what the POST can represent (case 2's shape), the PATCH must accept identically")
                .isEqualTo(HttpStatus.OK);

        // And the PATCH's own illegal bodies (311 D3's table) must ALSO be illegal on assignment —
        // partial band, ceiling below floor, RANGE with no ceiling, FIXED with a ceiling. Assigning
        // a SECOND master with each of these bodies must 400 exactly as the PATCH would.
        UUID secondMaster = fixtures.createSalonMaster(salonId);
        assertThat(assign(ownerToken, salonId, secondMaster,
                new AssignServiceToMasterRequest(defId, PriceType.RANGE, new BigDecimal("800.00"), new BigDecimal("500.00"), null))
                .raw().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // The DEGENERATE boundary — floor == ceiling — is the one a forked comparison (>=
        // instead of >) silently flips without touching any of the bodies above (mutation 6:
        // "copy the validator's rules instead of calling the shared one, then change one
        // comparison"). Phase 312 D8 REVERSES this boundary from the earlier decision this case
        // was deliberately strengthened to pin: a RANGE band whose floor equals its ceiling is
        // FIXED spelled a second way, and `MasterServiceBand.isLegal` is now strict (`>`), so it
        // is REJECTED on both the POST and the PATCH — they must still agree, only the correct
        // answer flipped.
        UUID thirdMaster = fixtures.createSalonMaster(salonId);
        assertThat(assign(ownerToken, salonId, thirdMaster,
                new AssignServiceToMasterRequest(defId, PriceType.RANGE, new BigDecimal("600.00"), new BigDecimal("600.00"), null))
                .raw().getStatusCode())
                .as("a degenerate RANGE (floor == ceiling) must be REJECTED on the POST — Phase 312 "
                        + "D8 tightened isLegal's comparison from >= to strict >")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(masterServicesRowCount(thirdMaster, defId))
                .as("the rejected degenerate POST must write nothing — not even an Inherited row")
                .isZero();

        // Give thirdMaster a legal band directly so the degenerate PATCH rejection below has a
        // pre-existing band to prove is left untouched — a status-only assertion would be vacuous.
        assign(ownerToken, salonId, thirdMaster,
                new AssignServiceToMasterRequest(defId, PriceType.FIXED, new BigDecimal("600.00"), null, null))
                .response();
        Map<String, Object> bandBeforeDegeneratePatch = bandRow(thirdMaster, defId);

        ResponseEntity<String> degeneratePatch = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + thirdMaster + "/services/" + defId, HttpMethod.PATCH,
                new HttpEntity<>(new UpdateMasterServiceBandRequest(
                                PriceType.RANGE, new BigDecimal("610.00"), new BigDecimal("610.00"), null, null, null),
                        fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(degeneratePatch.getStatusCode())
                .as("the PATCH must agree with the POST in REJECTING the same degenerate boundary")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bandRow(thirdMaster, defId))
                .as("a rejected degenerate PATCH must leave the pre-existing band unchanged")
                .isEqualTo(bandBeforeDegeneratePatch);
    }

    // ── Case 11 — SALON_MASTER still 403s on the write paths (311 D6's refusal survives) ────────

    @Test
    @DisplayName("Case 11: SALON_MASTER 403s on the single assign and on bulk — the write refusal "
            + "311 D6 keeps")
    void should_denySalonMaster_when_callingAssignOrBulk() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-312-c11-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 312 Case 11 Salon");
        UUID targetMasterId = fixtures.createSalonMaster(salonId);
        UUID defId = createFixedDefinition(ownerToken, salonId, "600.00");
        String masterToken = fixtures.createSalonMasterWithRowAndGetToken(
                salonId, "salonmaster-312-c11-" + System.nanoTime() + "@beautica.test").token();

        ResponseEntity<String> assignResp = assign(masterToken, salonId, targetMasterId,
                new AssignServiceToMasterRequest(defId, PriceType.FIXED, new BigDecimal("700.00"), null, null)).raw();
        assertThat(assignResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        ResponseEntity<String> bulkResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + targetMasterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(
                                List.of(new BulkServiceItemRequest(typeId, 60, PriceType.FIXED, new BigDecimal("500.00"), null, null))),
                        fixtures.bearerHeaders(masterToken)),
                String.class);
        assertThat(bulkResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Shared setup + HTTP plumbing ─────────────────────────────────────────────────────────────

    private record AssignResult(ResponseEntity<String> raw, MasterServiceResponse parsed) {
        MasterServiceResponse response() {
            assertThat(raw.getStatusCode()).as("expected a successful assign, body=%s", raw.getBody())
                    .isEqualTo(HttpStatus.CREATED);
            return parsed;
        }
    }

    private AssignResult assign(String token, UUID salonId, UUID masterId, AssignServiceToMasterRequest request)
            throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
        MasterServiceResponse parsed = resp.getStatusCode() == HttpStatus.CREATED
                ? objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<MasterServiceResponse>>() {}).data()
                : null;
        return new AssignResult(resp, parsed);
    }

    private UUID createFixedDefinition(String ownerToken, UUID salonId, String price) throws Exception {
        UUID typeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");
        return fixtures.createServiceDefinition(ownerToken, salonId,
                new CreateServiceDefinitionRequest("Phase 312 Fixed " + System.nanoTime(), null,
                        "NAIL_SERVICE", 60, 0, PriceType.FIXED, new BigDecimal(price), null, null, typeId));
    }

    private UUID createRangeDefinition(String ownerToken, UUID salonId, String priceMin, String priceMax)
            throws Exception {
        UUID typeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");
        return fixtures.createServiceDefinition(ownerToken, salonId,
                new CreateServiceDefinitionRequest("Phase 312 Range " + System.nanoTime(), null,
                        "NAIL_SERVICE", 60, 0, PriceType.RANGE, null, new BigDecimal(priceMin),
                        new BigDecimal(priceMax), typeId));
    }

    private Map<String, Object> bandRow(UUID masterId, UUID defId) {
        return jdbcTemplate.queryForMap(
                "SELECT price_type_override, price_override, price_max_override "
                        + "FROM master_services WHERE master_id = ? AND service_def_id = ?",
                masterId, defId);
    }

    private boolean activeAssignmentExists(UUID masterId, UUID defId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ? AND is_active = TRUE",
                Long.class, masterId, defId);
        return count != null && count > 0;
    }

    private long masterServicesRowCount(UUID masterId, UUID defId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Long.class, masterId, defId);
        return count == null ? 0L : count;
    }
}
