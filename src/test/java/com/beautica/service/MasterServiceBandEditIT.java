package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.UpdateMasterServiceBandRequest;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 311 — {@code PATCH /salons/{salonId}/masters/{masterId}/services/{serviceDefId}}, the
 * per-master price band editor.
 *
 * <h2>D2 — all-or-nothing</h2>
 * Cases 10-14 pin the illegal shapes; case 7 pins {@code clearBand}; case 18 pins that duration is
 * OUTSIDE the band.
 *
 * <h2>D2 — Inherited tracks, own band is immune</h2>
 * Cases 8/9 are the phase's whole point: after a master takes their own band, a later edit to the
 * shared definition must NOT move it, while a still-Inherited sibling continues to track it.
 *
 * <h2>D9 — the resolved band, not the definition's</h2>
 * Cases 1-4 exercise every direction the resolved band can now diverge from the definition's,
 * including the shape the phase exists to add (case 2: RANGE own band on a FIXED definition).
 *
 * <h2>D10 — bookings are frozen, never re-priced, never a block</h2>
 * Cases 15-17.
 *
 * <h2>D11 — conditional eviction</h2>
 * Cases 23/25 are proven here end-to-end (real HTTP + real DB). Case 24's PRECISE mechanism
 * (duration-changed vs band-only, the exact conditional the phase's mutation 12 targets) is proven
 * with total precision against a real Caffeine cache in {@code ServiceCatalogServiceCacheTest}
 * (REUSE-FIRST — that file already carries the identical eviction-key-fixture harness for this
 * exact cache, established for {@code deactivateServiceDefinition}/{@code
 * unassignServiceFromMaster}); reproducing it here would require driving the slot calculator to a
 * genuinely populated {@code available-slots} entry through HTTP, for no additional confidence.
 *
 * <h2>REUSE-FIRST</h2>
 * Built on the same {@link ServiceTestFixtures} harness as {@code MasterServiceUnassignIT} and
 * {@code BulkServiceSetupIntegrationTest} — {@code createSalonOwnerAndGetToken}, {@code
 * createSalon}, {@code createSalonMaster}, {@code seedUsableSchedule}, {@code
 * createSalonAdminAndGetToken}, {@code createSalonMasterAndGetToken}, {@code
 * createSalonMasterWithRowAndGetToken}, {@code createClientAndGetToken},
 * {@code activeSelectableServiceTypes}, {@code minEffectivePriceForMaster} and {@code
 * bearerHeaders} are all shared fixture methods.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /salons/{salonId}/masters/{masterId}/services/{serviceDefId} — Phase 311 band editor")
class MasterServiceBandEditIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── Case 1/5/6 — FIXED own band on a FIXED definition; shared def + siblings untouched ─────

    @Test
    @DisplayName("Case 1/5/6: owner sets FIXED 750 on a FIXED 600 definition — 200, DB columns "
            + "correct, priceDisplay '750 ₴'; the shared definition and a second master are untouched")
    void should_setOwnFixedBand_when_ownerPatchesFixedDefinition() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c1-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 1 Salon");
        UUID masterA = fixtures.createSalonMaster(salonId);
        UUID masterB = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignmentA = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("600.00"));
        Assignment assignmentB = bulkCreateOnSameType(ownerToken, salonId, masterB, assignmentA);

        MasterServiceResponse patched = patchBand(ownerToken, salonId, masterA, assignmentA.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("750.00"), null, null, null, null))
                .response();

        assertThat(patched.priceType()).isEqualTo(PriceType.FIXED);
        assertThat(patched.priceMin()).isEqualByComparingTo("750.00");
        assertThat(patched.priceDisplay()).isEqualTo("750 ₴");
        var row = bandRow(assignmentA.assignmentId());
        assertThat(row.get("price_type_override")).isEqualTo("FIXED");
        assertThat((BigDecimal) row.get("price_override")).isEqualByComparingTo("750.00");
        assertThat(row.get("price_max_override")).isNull();

        // Case 5 — the shared definition is untouched.
        var defRow = jdbcTemplate.queryForMap(
                "SELECT base_price, price_type FROM service_definitions WHERE id = ?", assignmentA.definitionId());
        assertThat((BigDecimal) defRow.get("base_price")).isEqualByComparingTo("600.00");
        assertThat(defRow.get("price_type")).isEqualTo("FIXED");

        // Case 6 — a second master on the same definition is unaffected, still Inherited.
        var rowB = bandRow(assignmentB.assignmentId());
        assertThat(rowB.get("price_type_override")).isNull();
        assertThat(rowB.get("price_override")).isNull();
    }

    // ── Case 2 — RANGE own band on a FIXED definition: THE capability this phase adds ──────────

    @Test
    @DisplayName("Case 2: owner sets RANGE 500-800 on a FIXED definition — 200; priceDisplay "
            + "'від 500 до 800 ₴' — impossible before V165")
    void should_setOwnRangeBand_when_ownerPatchesFixedDefinition() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c2-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 2 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));

        MasterServiceResponse patched = patchBand(ownerToken, salonId, masterId, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(
                        PriceType.RANGE, new BigDecimal("500.00"), new BigDecimal("800.00"), null, null, null))
                .response();

        assertThat(patched.priceType()).isEqualTo(PriceType.RANGE);
        assertThat(patched.priceMin()).isEqualByComparingTo("500.00");
        assertThat(patched.priceMax()).isEqualByComparingTo("800.00");
        assertThat(patched.priceDisplay()).isEqualTo("від 500 до 800 ₴");
    }

    // ── Case 3 — the inverse: FIXED own band on a RANGE definition ──────────────────────────────

    @Test
    @DisplayName("Case 3: owner sets FIXED 700 on a RANGE 400-900 definition — 200; single price, "
            + "definition untouched")
    void should_setOwnFixedBand_when_ownerPatchesRangeDefinition() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c3-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 3 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateRange(
                ownerToken, salonId, masterId, typeId, new BigDecimal("400.00"), new BigDecimal("900.00"));

        MasterServiceResponse patched = patchBand(ownerToken, salonId, masterId, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("700.00"), null, null, null, null))
                .response();

        assertThat(patched.priceType()).isEqualTo(PriceType.FIXED);
        assertThat(patched.priceMin()).isEqualByComparingTo("700.00");
        assertThat(patched.priceMax()).isNull();
        var defRow = jdbcTemplate.queryForMap(
                "SELECT base_price, price_max FROM service_definitions WHERE id = ?", assignment.definitionId());
        assertThat((BigDecimal) defRow.get("base_price")).isEqualByComparingTo("400.00");
        assertThat((BigDecimal) defRow.get("price_max")).isEqualByComparingTo("900.00");
    }

    // ── Case 4 — no envelope: a band entirely outside the salon's is legal (D3) ─────────────────

    @Test
    @DisplayName("Case 4: owner sets RANGE 1000-1500 on a RANGE 400-900 definition — 200; no "
            + "containment against the salon's band")
    void should_acceptBandOutsideDefinition_when_noEnvelopeEnforced() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c4-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 4 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateRange(
                ownerToken, salonId, masterId, typeId, new BigDecimal("400.00"), new BigDecimal("900.00"));

        ResponseEntity<String> resp = patchBand(ownerToken, salonId, masterId, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(
                        PriceType.RANGE, new BigDecimal("1000.00"), new BigDecimal("1500.00"), null, null, null))
                .raw();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        MasterServiceResponse body = parse(resp);
        assertThat(body.priceMin()).isEqualByComparingTo("1000.00");
        assertThat(body.priceMax()).isEqualByComparingTo("1500.00");
    }

    // ── Cases 7/8/9 — clearBand reverts to Inherited, which then tracks; an own band does not ──

    @Test
    @DisplayName("Case 7/8/9: clearBand reverts to Inherited and RESUMES tracking the definition; "
            + "a sibling with an own band stays immune to the same definition edit")
    void should_revertToInheritedAndTrack_when_clearBandThenDefinitionEdited() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c789-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 789 Salon");
        UUID masterInherited = fixtures.createSalonMaster(salonId);
        UUID masterOwnBand = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment inherited = bulkCreateFixed(ownerToken, salonId, masterInherited, typeId, new BigDecimal("600.00"));
        Assignment ownBand = bulkCreateOnSameType(ownerToken, salonId, masterOwnBand, inherited);

        // masterInherited first takes an own band, then clears it (case 7).
        patchBand(ownerToken, salonId, masterInherited, inherited.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("999.00"), null, null, null, null));
        MasterServiceResponse cleared = patchBand(ownerToken, salonId, masterInherited, inherited.definitionId(),
                new UpdateMasterServiceBandRequest(null, null, null, null, true, null))
                .response();
        assertThat(cleared.priceMin())
                .as("case 7 — cleared band equals the definition's again (Inherited)")
                .isEqualByComparingTo("600.00");
        var clearedRow = bandRow(inherited.assignmentId());
        assertThat(clearedRow.get("price_type_override")).isNull();
        assertThat(clearedRow.get("price_override")).isNull();
        assertThat(clearedRow.get("price_max_override")).isNull();

        // masterOwnBand takes its own band and keeps it.
        patchBand(ownerToken, salonId, masterOwnBand, ownBand.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("777.00"), null, null, null, null));

        // The owner edits the shared definition.
        ResponseEntity<String> defPatch = restTemplate.exchange(
                "/api/v1/services/" + inherited.definitionId(), HttpMethod.PATCH,
                new HttpEntity<>(new UpdateServiceDefinitionRequest(
                                null, null, null, null, null, PriceType.FIXED, new BigDecimal("450.00"), null, null, null),
                        fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(defPatch.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Case 8 — the now-Inherited master moves WITH the definition.
        MasterServiceResponse inheritedAfter = getBandResponse(ownerToken, salonId, masterInherited, inherited.definitionId());
        assertThat(inheritedAfter.priceMin())
                .as("case 8 — Inherited tracks the definition's new price")
                .isEqualByComparingTo("450.00");

        // Case 9 — the own-band master does NOT move.
        MasterServiceResponse ownBandAfter = getBandResponse(ownerToken, salonId, masterOwnBand, ownBand.definitionId());
        assertThat(ownBandAfter.priceMin())
                .as("case 9 — own band is immune to the definition edit")
                .isEqualByComparingTo("777.00");
    }

    // ── Cases 10-14 — illegal bands, all 400, nothing written (D2/D3/D4) ────────────────────────

    @Test
    @DisplayName("Case 10: RANGE with priceMax < price — 400")
    void should_return400_when_rangeCeilingBelowFloor() throws Exception {
        assertIllegalPatchRejected(new UpdateMasterServiceBandRequest(
                PriceType.RANGE, new BigDecimal("800.00"), new BigDecimal("500.00"), null, null, null));
    }

    @Test
    @DisplayName("Case 11: RANGE with no priceMax — 400")
    void should_return400_when_rangeHasNoCeiling() throws Exception {
        assertIllegalPatchRejected(new UpdateMasterServiceBandRequest(
                PriceType.RANGE, new BigDecimal("500.00"), null, null, null, null));
    }

    @Test
    @DisplayName("Case 12: FIXED with a priceMax — 400")
    void should_return400_when_fixedHasCeiling() throws Exception {
        assertIllegalPatchRejected(new UpdateMasterServiceBandRequest(
                PriceType.FIXED, new BigDecimal("750.00"), new BigDecimal("900.00"), null, null, null));
    }

    @Test
    @DisplayName("Case 13: price with no priceType — 400 (partial band, D2)")
    void should_return400_when_priceWithNoPriceType() throws Exception {
        assertIllegalPatchRejected(new UpdateMasterServiceBandRequest(
                null, new BigDecimal("750.00"), null, null, null, null));
    }

    @Test
    @DisplayName("Case 14a: {} — 400 (empty patch rejected)")
    void should_return400_when_patchIsEntirelyEmpty() throws Exception {
        assertIllegalPatchRejected(new UpdateMasterServiceBandRequest(null, null, null, null, null, null));
    }

    @Test
    @DisplayName("Case 14b: a full band + clearBand together — 400 (contradictory)")
    void should_return400_when_bandAndClearBandContradict() throws Exception {
        assertIllegalPatchRejected(new UpdateMasterServiceBandRequest(
                PriceType.FIXED, new BigDecimal("750.00"), null, null, true, null));
    }

    @Test
    @DisplayName("Case 14c (Phase 312 D8): RANGE with priceMax == price (degenerate) — 400, band "
            + "left Inherited")
    void should_return400_when_rangeCeilingEqualsFloor() throws Exception {
        // 311 owns the PATCH, so the degenerate boundary must be pinned in this suite too, not
        // only in MasterServiceBandCreateIT's cross-verb Case 10 — a RANGE band whose floor
        // equals its ceiling is FIXED spelled a second way (D8), and MasterServiceBand.isLegal's
        // comparison is now strict (>), so this is rejected exactly like Case 10 above.
        assertIllegalPatchRejected(new UpdateMasterServiceBandRequest(
                PriceType.RANGE, new BigDecimal("500.00"), new BigDecimal("500.00"), null, null, null));
    }

    private void assertIllegalPatchRejected(UpdateMasterServiceBandRequest illegalRequest) throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-illegal-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Illegal Band Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));

        ResponseEntity<String> resp = patchBand(ownerToken, salonId, masterId, assignment.definitionId(), illegalRequest).raw();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var row = bandRow(assignment.assignmentId());
        assertThat(row.get("price_type_override")).isNull();
        assertThat(row.get("price_override")).isNull();
    }

    // ── Case 15/16 — existing bookings are frozen and never block ───────────────────────────────

    @Test
    @DisplayName("Case 15: a COMPLETED booking made before the edit still reads its original "
            + "priceAtBooking/priceMaxAtBooking (D10)")
    void should_keepFrozenPrice_when_completedBookingPredatesEdit() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c15-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 15 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));
        UUID clientId = insertClientUser();
        UUID bookingId = insertBooking(salonId, masterId, assignment.assignmentId(), clientId,
                "COMPLETED", "-2 days", new BigDecimal("600.00"), null);

        patchBand(ownerToken, salonId, masterId, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("999.00"), null, null, null, null));

        var row = jdbcTemplate.queryForMap(
                "SELECT price_at_booking, price_max_at_booking FROM bookings WHERE id = ?", bookingId);
        assertThat((BigDecimal) row.get("price_at_booking")).isEqualByComparingTo("600.00");
        assertThat(row.get("price_max_at_booking")).isNull();
    }

    @Test
    @DisplayName("Case 16: a future CONFIRMED booking does NOT block the edit — 200, snapshot "
            + "prices unchanged (D10, unlike Phase 307's unassign)")
    void should_notBlockEdit_when_futureConfirmedBookingExists() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c16-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 16 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));
        UUID clientId = insertClientUser();
        UUID bookingId = insertBooking(salonId, masterId, assignment.assignmentId(), clientId,
                "CONFIRMED", "+2 days", new BigDecimal("600.00"), null);

        ResponseEntity<String> resp = patchBand(ownerToken, salonId, masterId, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("999.00"), null, null, null, null))
                .raw();

        assertThat(resp.getStatusCode())
                .as("D10 — no 409, unlike Phase 307's unassign")
                .isEqualTo(HttpStatus.OK);
        var row = jdbcTemplate.queryForMap(
                "SELECT price_at_booking, status FROM bookings WHERE id = ?", bookingId);
        assertThat((BigDecimal) row.get("price_at_booking")).isEqualByComparingTo("600.00");
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
    }

    // ── Case 17 — a NEW booking against a RANGE own band records THAT ceiling (D9's change) ────

    @Test
    @DisplayName("Case 17: a booking created AFTER the master takes a RANGE band records the "
            + "MASTER'S ceiling, not the definition's, and not null")
    void should_recordOwnCeiling_when_bookingCreatedAfterRangeBandTaken() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c17-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 17 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));

        patchBand(ownerToken, salonId, masterId, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(
                        PriceType.RANGE, new BigDecimal("500.00"), new BigDecimal("900.00"), null, null, null));

        String clientToken = fixtures.createClientAndGetToken(
                "client-311-c17-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        var bookingRequest = new CreateBookingRequest(masterId, assignment.assignmentId(), startsAt, null, null, false);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(bookingRequest, fixtures.bearerHeaders(clientToken)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BookingResponse booking = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<BookingResponse>>() {}).data();
        assertThat(booking.priceAtBooking()).isEqualByComparingTo("500.00");
        assertThat(booking.priceMaxAtBooking())
                .as("the master's OWN ceiling (900), not the definition's (none — it is FIXED), "
                        + "and not null")
                .isEqualByComparingTo("900.00");
    }

    // ── Case 18 — duration-only edit leaves the band untouched (D2) ─────────────────────────────

    @Test
    @DisplayName("Case 18: durationOverrideMinutes-only patch leaves all three band columns untouched")
    void should_leaveBandUntouched_when_onlyDurationPatched() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c18-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 18 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));
        patchBand(ownerToken, salonId, masterId, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("750.00"), null, null, null, null));

        MasterServiceResponse patched = patchBand(ownerToken, salonId, masterId, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(null, null, null, 90, null, null))
                .response();

        assertThat(patched.durationOverrideMinutes()).isEqualTo(90);
        assertThat(patched.priceMin())
                .as("the band set two calls ago must be untouched by a duration-only patch")
                .isEqualByComparingTo("750.00");
    }

    // ── Case 19/20 — SALON_MASTER may edit their OWN row and NOTHING else (D5/D6) ──────────────

    @Test
    @DisplayName("Case 19: SALON_MASTER patches their OWN row — 200; a PEER's row — 403, nothing written")
    void should_allowOwnRow_and_refusePeerRow_when_salonMasterPatchesBand() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c19-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 19 Salon");
        var self = fixtures.createSalonMasterWithRowAndGetToken(
                salonId, "self-311-c19-" + System.nanoTime() + "@beautica.test");
        UUID peerMasterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(2).get(0).id();
        UUID peerTypeId = fixtures.activeSelectableServiceTypes(2).get(1).id();
        Assignment ownAssignment = bulkCreateFixed(ownerToken, salonId, self.masterId(), typeId, new BigDecimal("600.00"));
        Assignment peerAssignment = bulkCreateFixed(ownerToken, salonId, peerMasterId, peerTypeId, new BigDecimal("600.00"));

        ResponseEntity<String> ownResp = patchBand(self.token(), salonId, self.masterId(), ownAssignment.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("750.00"), null, null, null, null))
                .raw();
        assertThat(ownResp.getStatusCode()).as("case 19 — own row").isEqualTo(HttpStatus.OK);

        ResponseEntity<String> peerResp = patchBand(self.token(), salonId, peerMasterId, peerAssignment.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("111.00"), null, null, null, null))
                .raw();
        assertThat(peerResp.getStatusCode()).as("case 19 — a peer's row").isEqualTo(HttpStatus.FORBIDDEN);
        var peerRow = bandRow(peerAssignment.assignmentId());
        assertThat(peerRow.get("price_override")).as("nothing written on the refused peer edit").isNull();
    }

    @Test
    @DisplayName("Case 20: SALON_MASTER still 403s on assign, bulk, unassign, PATCH /services/{id} "
            + "and DELETE /services/{id} for their own row (D6)")
    void should_stayRefused_when_salonMasterAttemptsEveryOtherServiceWrite() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c20-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 20 Salon");
        var self = fixtures.createSalonMasterWithRowAndGetToken(
                salonId, "self-311-c20-" + System.nanoTime() + "@beautica.test");
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, self.masterId(), typeId, new BigDecimal("600.00"));

        var assignReq = new com.beautica.service.dto.AssignServiceToMasterRequest(assignment.definitionId(), null, null, null, null);
        assertThat(restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + self.masterId() + "/services", HttpMethod.POST,
                new HttpEntity<>(assignReq, fixtures.bearerHeaders(self.token())), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        var bulkItem = new BulkServiceItemRequest(typeId, 60, PriceType.FIXED, new BigDecimal("350.00"), null, null);
        assertThat(restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + self.masterId() + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(List.of(bulkItem)), fixtures.bearerHeaders(self.token())),
                String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + self.masterId() + "/services/" + assignment.definitionId(),
                HttpMethod.DELETE, new HttpEntity<>(fixtures.bearerHeaders(self.token())), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        var defPatch = new UpdateServiceDefinitionRequest(
                "Hijacked", null, null, null, null, null, null, null, null, null);
        assertThat(restTemplate.exchange(
                "/api/v1/services/" + assignment.definitionId(), HttpMethod.PATCH,
                new HttpEntity<>(defPatch, fixtures.bearerHeaders(self.token())), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(restTemplate.exchange(
                "/api/v1/services/" + assignment.definitionId(), HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(self.token())), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Case 21 — role/ownership matrix ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case 21: SALON_ADMIN — 200; SALON_OWNER of another salon — 403; CLIENT — 403; anonymous — 401")
    void should_matchRoleMatrix_when_variousActorsPatchBand() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c21-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 21 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));
        var band = new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("750.00"), null, null, null, null);

        String adminToken = fixtures.createSalonAdminAndGetToken(
                salonId, "admin-311-c21-" + System.nanoTime() + "@beautica.test");
        assertThat(patchBand(adminToken, salonId, masterId, assignment.definitionId(), band).raw().getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c21-other-" + System.nanoTime() + "@beautica.test");
        assertThat(patchBand(otherOwnerToken, salonId, masterId, assignment.definitionId(), band).raw().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        String clientToken = fixtures.createClientAndGetToken(
                "client-311-c21-" + System.nanoTime() + "@beautica.test");
        assertThat(patchBand(clientToken, salonId, masterId, assignment.definitionId(), band).raw().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> anon = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/" + assignment.definitionId(),
                HttpMethod.PATCH, new HttpEntity<>(band), String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Case 22 — 404/403 boundaries ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case 22a: masterId in another salon, no assignment for the given definition — 403")
    void should_return403_when_masterInAnotherSalonHasNoSuchAssignment() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c22a-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 22a Salon A");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));

        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c22a-other-" + System.nanoTime() + "@beautica.test");
        UUID salonB = fixtures.createSalon(otherOwnerToken, "Phase 311 Case 22a Salon B");
        UUID masterInSalonB = fixtures.createSalonMaster(salonB);

        ResponseEntity<String> resp = patchBand(ownerToken, salonId, masterInSalonB, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("1.00"), null, null, null, null))
                .raw();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Case 22a-variant: masterId in another salon, WITH vs WITHOUT an active assignment "
            + "for the given definition — must be INDISTINGUISHABLE (both 403), closing the "
            + "cross-tenant 403/404 existence oracle")
    void should_return403Uniformly_regardlessOfWhetherTheForeignMasterHoldsTheAssignment() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c22a2-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 22a-variant Salon A");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));

        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c22a2-other-" + System.nanoTime() + "@beautica.test");
        UUID salonB = fixtures.createSalon(otherOwnerToken, "Phase 311 Case 22a-variant Salon B");
        UUID masterInSalonB = fixtures.createSalonMaster(salonB);
        UUID typeIdB = fixtures.activeSelectableServiceTypes(1).get(0).id();
        // masterInSalonB genuinely holds an ACTIVE assignment for THIS definition — unlike case
        // 22a's masterInSalonB (which has none). Pre-fix, the service layer would find this row
        // and reach the 403 branch at ServiceCatalogService's master.getSalon() check, while the
        // no-assignment case hits the 404 NotFoundException branch instead — the oracle. The
        // assertion below compares the two outcomes DIRECTLY rather than pinning each to a
        // hardcoded literal, so it fails (RED) whenever they diverge, regardless of which literal
        // either branch happens to return.
        Assignment assignmentInSalonB =
                bulkCreateFixed(otherOwnerToken, salonB, masterInSalonB, typeIdB, new BigDecimal("600.00"));

        org.springframework.http.HttpStatusCode statusWithoutAssignment = patchBand(
                ownerToken, salonId, masterInSalonB, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("1.00"), null, null, null, null))
                .raw().getStatusCode();
        org.springframework.http.HttpStatusCode statusWithAssignment = patchBand(
                ownerToken, salonId, masterInSalonB, assignmentInSalonB.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("1.00"), null, null, null, null))
                .raw().getStatusCode();

        assertThat(statusWithAssignment)
                .as("an existing-but-foreign assignment (%s) must be indistinguishable from a "
                        + "nonexistent one (%s) — a caller must never learn which is true",
                        statusWithAssignment, statusWithoutAssignment)
                .isEqualTo(statusWithoutAssignment);
        assertThat(statusWithoutAssignment).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Case 22b: serviceDefId owned by another salon — 404")
    void should_return404_when_serviceDefIdBelongsToAnotherSalon() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c22b-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 22b Salon A");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));

        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c22b-other-" + System.nanoTime() + "@beautica.test");
        UUID salonB = fixtures.createSalon(otherOwnerToken, "Phase 311 Case 22b Salon B");
        UUID salonBDefId = fixtures.createServiceDefinition(otherOwnerToken, salonB, "Salon B Service (311)");

        ResponseEntity<String> resp = patchBand(ownerToken, salonId, masterId, salonBDefId,
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("1.00"), null, null, null, null))
                .raw();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Case 22c: an inactive assignment — 404, the row stays is_active = false")
    void should_return404_when_assignmentInactive() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c22c-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 22c Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));
        assertThat(restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/" + assignment.definitionId(),
                HttpMethod.DELETE, new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> resp = patchBand(ownerToken, salonId, masterId, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("1.00"), null, null, null, null))
                .raw();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE id = ?", Boolean.class, assignment.assignmentId());
        assertThat(isActive).isFalse();
    }

    // ── Case 23 — afterCommit eviction: catalogue + management read reflect the edit ───────────

    @Test
    @DisplayName("Case 23: GET /masters/{m}/services (the CACHED public route backing the "
            + "\"masterServices\" cache, D11's first row) reflects the band edit immediately with "
            + "no manual eviction; GET /salons/{s}/services (Phase 314's aggregate) reflects it too")
    void should_reflectEditImmediately_when_readingAfterPatch() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c23-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 23 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));
        // Populate both caches with the pre-edit snapshot — the PUBLIC /masters/{id}/services read
        // is what backs the "masterServices" Caffeine cache D11's table names; the salon-owner
        // management read (getSalonMasterServices) is explicitly NOT cached (309/310 D4) and would
        // prove nothing about eviction.
        assertThat(catalogueMinPrice(salonId, assignment.definitionId())).isEqualByComparingTo("600.00");
        assertThat(publicMasterServicesMinPrice(masterId, assignment.definitionId())).isEqualByComparingTo("600.00");

        patchBand(ownerToken, salonId, masterId, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("999.00"), null, null, null, null));

        // Phase 314 D1/D7/D11 composed: GET /salons/{s}/services now prices the row as the union
        // hull of its bookable masters' RESOLVED bands, not the shared definition's own (unchanged)
        // band — with exactly one bookable master here, the hull IS that master's band, so the
        // catalogue must reflect the edit on the very next read (evictSalonCatalogAfterCommit
        // firing, D11 "always", is what makes this possible with no manual eviction workaround).
        // See SalonCatalogueAggregatePriceIT case 13 for the dedicated end-to-end proof.
        assertThat(catalogueMinPrice(salonId, assignment.definitionId()))
                .as("Phase 314: the catalogue aggregates the sole bookable master's edited band")
                .isEqualByComparingTo("999.00");
        // The PUBLIC masterServices read — MasterServiceResponse.from -> ServicePricing.ofAssignment
        // -> fromPublic (masks priceOverride only, keeps priceMin) — DOES resolve the master's own
        // band (D9), and its "masterServices" cache entry MUST be gone with no manual eviction
        // workaround, proving evictMasterServicesCache's afterCommit registration fired.
        assertThat(publicMasterServicesMinPrice(masterId, assignment.definitionId()))
                .as("the masterServices-cached public read must reflect the edit with no manual eviction")
                .isEqualByComparingTo("999.00");
    }

    // ── Case 25 — a floor-lowering edit refreshes min_effective_price ───────────────────────────

    @Test
    @DisplayName("Case 25: after a band edit that lowers the master's cheapest floor, "
            + "masters.min_effective_price matches the new minimum (D11)")
    void should_refreshMinEffectivePrice_when_bandLowersFloor() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-311-c25-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 311 Case 25 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID typeId = fixtures.activeSelectableServiceTypes(1).get(0).id();
        Assignment assignment = bulkCreateFixed(ownerToken, salonId, masterId, typeId, new BigDecimal("600.00"));
        assertThat(fixtures.minEffectivePriceForMaster(masterId)).isEqualByComparingTo("600.00");

        patchBand(ownerToken, salonId, masterId, assignment.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("150.00"), null, null, null, null));

        assertThat(fixtures.minEffectivePriceForMaster(masterId))
                .as("D11 — min_effective_price refreshes on a floor change, read directly from the DB")
                .isEqualByComparingTo("150.00");
    }

    // ── shared setup + HTTP plumbing ─────────────────────────────────────────────────────────────

    private record Assignment(UUID assignmentId, UUID definitionId) {
    }

    private record PatchResult(ResponseEntity<String> raw, MasterServiceResponse parsed) {
        MasterServiceResponse response() {
            assertThat(raw.getStatusCode()).as("expected a successful band PATCH, body=%s", raw.getBody())
                    .isEqualTo(HttpStatus.OK);
            return parsed;
        }
    }

    private Assignment bulkCreateFixed(String ownerToken, UUID salonId, UUID masterId, UUID serviceTypeId,
            BigDecimal price) throws Exception {
        return bulkCreate(ownerToken, salonId, masterId,
                new BulkServiceItemRequest(serviceTypeId, 60, PriceType.FIXED, price, null, null));
    }

    private Assignment bulkCreateRange(String ownerToken, UUID salonId, UUID masterId, UUID serviceTypeId,
            BigDecimal priceMin, BigDecimal priceMax) throws Exception {
        return bulkCreate(ownerToken, salonId, masterId,
                new BulkServiceItemRequest(serviceTypeId, 60, PriceType.RANGE, null, priceMin, priceMax));
    }

    /** A SECOND master assigned to the SAME salon-owned definition an existing assignment reuses. */
    private Assignment bulkCreateOnSameType(String ownerToken, UUID salonId, UUID masterId, Assignment existing)
            throws Exception {
        UUID typeId = jdbcTemplate.queryForObject(
                "SELECT service_type_id FROM service_definitions WHERE id = ?", UUID.class, existing.definitionId());
        var request = new com.beautica.service.dto.AssignServiceToMasterRequest(existing.definitionId(), null, null, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(ownerToken)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MasterServiceResponse created = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<MasterServiceResponse>>() {}).data();
        return new Assignment(created.id(), created.serviceDefinition().id());
    }

    private Assignment bulkCreate(String ownerToken, UUID salonId, UUID masterId, BulkServiceItemRequest item)
            throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(List.of(item)), fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MasterServiceResponse created = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data().get(0);
        return new Assignment(created.id(), created.serviceDefinition().id());
    }

    private PatchResult patchBand(String token, UUID salonId, UUID masterId, UUID serviceDefId,
            UpdateMasterServiceBandRequest request) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/" + serviceDefId,
                HttpMethod.PATCH, new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
        MasterServiceResponse parsed = resp.getStatusCode() == HttpStatus.OK ? parse(resp) : null;
        return new PatchResult(resp, parsed);
    }

    private MasterServiceResponse parse(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<MasterServiceResponse>>() {}).data();
    }

    private MasterServiceResponse getBandResponse(String token, UUID salonId, UUID masterId, UUID serviceDefId)
            throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<MasterServiceResponse> services = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data();
        return services.stream().filter(s -> s.serviceDefinition().id().equals(serviceDefId)).findFirst()
                .orElseThrow(() -> new AssertionError("service not found in master's list: " + serviceDefId));
    }

    private java.util.Map<String, Object> bandRow(UUID assignmentId) {
        return jdbcTemplate.queryForMap(
                "SELECT price_type_override, price_override, price_max_override FROM master_services WHERE id = ?",
                assignmentId);
    }

    /** GETs the public, CACHED {@code /masters/{id}/services} route — no auth, no manual eviction. */
    private BigDecimal publicMasterServicesMinPrice(UUID masterId, UUID serviceDefId) throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity("/api/v1/masters/" + masterId + "/services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<MasterServiceResponse> services = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data();
        return services.stream()
                .filter(s -> s.serviceDefinition().id().equals(serviceDefId))
                .findFirst()
                .map(MasterServiceResponse::priceMin)
                .orElseThrow(() -> new AssertionError("service not found in public master services: " + serviceDefId));
    }

    private BigDecimal catalogueMinPrice(UUID salonId, UUID serviceDefId) throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity("/api/v1/salons/" + salonId + "/services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SalonServiceCatalogResponse catalog = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {}).data();
        return catalog.categories().stream()
                .flatMap(group -> group.services().stream())
                .filter(s -> s.id().equals(serviceDefId))
                .findFirst()
                .map(ServiceDefinitionResponse::priceMin)
                .orElseThrow(() -> new AssertionError("service not found in salon catalogue: " + serviceDefId));
    }

    private UUID insertClientUser() {
        UUID clientId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                clientId, "band-client-" + UUID.randomUUID() + "@beautica.test",
                passwordEncoder.encode(ServiceTestFixtures.TEST_PASSWORD));
        return clientId;
    }

    /** Direct-SQL booking insert, mirroring {@code MasterServiceUnassignIT#insertBooking}. */
    private UUID insertBooking(UUID salonId, UUID masterId, UUID masterServiceId, UUID clientId,
            String status, String startsAtOffset, BigDecimal price, BigDecimal priceMax) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, price_max_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW() + ?::interval, NOW() + ?::interval + interval '1 hour', "
                        + "?, ?, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status,
                startsAtOffset, startsAtOffset, price, priceMax);
        return bookingId;
    }
}
