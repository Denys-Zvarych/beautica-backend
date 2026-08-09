package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Full-HTTP-stack suite for {@code PATCH /appointments/{appointmentId}/services/{bookingId}/reschedule}
 * (track 30.x) — the NEW per-item counterpart of {@link AppointmentRescheduleIT}'s whole-visit route.
 *
 * <p><b>Locked semantics under test (phase 30.1 design record).</b> L1: exactly ONE child {@code
 * Booking} row moves. L2: every sibling keeps its {@code startsAt}/{@code endsAt} byte-for-byte —
 * there is no re-layout, no cascade, no gap-closing. L3: the contiguity invariant is RELAXED — gaps
 * (and even non-adjacent, out-of-order windows) between items are legal once any item has moved
 * individually. This suite deliberately never asserts that items stay contiguous after a per-item
 * move — that would pin the OPPOSITE of the locked decision.
 *
 * <p>Visits are seeded directly via SQL (mirrors {@link AppointmentRescheduleIT}'s
 * {@code seedIndependentVisit}/{@code seedVisitRows} pattern) — deterministic windows, no
 * slot-availability coupling for the ORIGINAL state. Working hours are always seeded 00:00–23:59
 * for every day so the NEW target time re-validates against the master's schedule
 * ({@code assertItemStartsOnAvailableSlot}, 30-minute slot granularity —
 * {@code SlotCalculationService.SLOT_STEP}).
 *
 * <p><b>Written against BEHAVIOUR, not mechanism.</b> A concurrent fix batch may move the sole
 * authoritative provider-ownership check from the controller's {@code @PreAuthorize} SpEL into
 * {@code AuthorizationService#enforceCanManageAppointment} inside the service (role-only gate on the
 * controller instead). Every assertion here is on the HTTP status code and persisted DB/JSON state,
 * never on which layer produced the rejection, so this suite survives that refactor unchanged.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /appointments/{appointmentId}/services/{bookingId}/reschedule — per-item reschedule")
class AppointmentItemRescheduleIT extends AbstractIntegrationTest {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── 1. Sibling independence — the single most important test in the track (L2) ────────────────

    @Test
    @DisplayName("client moves the MIDDLE leg of a three-service visit — 200, ONLY that leg's window "
            + "changes, BOTH other legs stay byte-for-byte unchanged, and the visit still holds all "
            + "three items")
    void should_moveOnlyMiddleLegByteForByte_when_clientReschedulesMiddleOfThreeServiceVisit() throws Exception {
        Visit v = seedContiguousVisit("middle", 3, futureStart());
        List<ItemRow> before = itemRows(v.id());
        assertThat(before).hasSize(3);
        UUID middleId = before.get(1).id();
        OffsetDateTime newStart = futureStart().plusDays(3).withHour(15);

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), middleId, newStart);

        assertThat(resp.getStatusCode())
                .as("moving the middle leg of a three-service visit must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        List<ItemRow> after = itemRows(v.id());
        assertThat(after).as("the appointment must still hold all three items").hasSize(3);

        ItemRow leg0Before = before.get(0);
        ItemRow leg2Before = before.get(2);
        ItemRow leg0After = findById(after, leg0Before.id());
        ItemRow leg2After = findById(after, leg2Before.id());
        assertThat(leg0After.startsAt().toInstant())
                .as("the FIRST leg's startsAt must be untouched by a move of the MIDDLE leg")
                .isEqualTo(leg0Before.startsAt().toInstant());
        assertThat(leg0After.endsAt().toInstant())
                .as("the FIRST leg's endsAt must be untouched by a move of the MIDDLE leg")
                .isEqualTo(leg0Before.endsAt().toInstant());
        assertThat(leg2After.startsAt().toInstant())
                .as("the THIRD leg's startsAt must be untouched by a move of the MIDDLE leg")
                .isEqualTo(leg2Before.startsAt().toInstant());
        assertThat(leg2After.endsAt().toInstant())
                .as("the THIRD leg's endsAt must be untouched by a move of the MIDDLE leg")
                .isEqualTo(leg2Before.endsAt().toInstant());

        ItemRow movedAfter = findById(after, middleId);
        assertThat(movedAfter.startsAt().toInstant())
                .as("the moved leg's startsAt must equal the requested new time")
                .isEqualTo(newStart.toInstant());
        assertThat(movedAfter.endsAt().toInstant())
                .as("the moved leg's endsAt must be startsAt + the FROZEN 60-minute duration")
                .isEqualTo(newStart.plusHours(1).toInstant());
        assertThat(movedAfter.status()).isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(v.id()))
                .as("the header status is untouched by a per-item move — it stays CONFIRMED")
                .isEqualTo("CONFIRMED");
    }

    // ── 2. Gap tolerance + the header endsAt = max(endsAt) recompute (phase 30.7 D1) ────────────────

    @Test
    @DisplayName("moving the MIDDLE leg far into the future so it now ends LAST creates a legal "
            + "non-contiguous gap; GET /appointments/{id} reports header endsAt = the MOVED leg's new "
            + "endsAt (max over ALL items), not the leg that used to be last")
    void should_reportHeaderEndsAtAsMovedLeg_when_movedLegNowEndsLast() throws Exception {
        Visit v = seedContiguousVisit("gap-moved-last", 3, futureStart());
        List<ItemRow> before = itemRows(v.id());
        UUID middleId = before.get(1).id();
        OffsetDateTime farNewStart = futureStart().plusDays(5).withHour(9);

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), middleId, farNewStart);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = getAppointment(v.clientToken(), v.id());
        OffsetDateTime headerEndsAt = OffsetDateTime.parse(data.path("endsAt").asText());
        assertThat(headerEndsAt.toInstant())
                .as("header endsAt must be max(endsAt) over ALL items — the moved leg is now the latest")
                .isEqualTo(farNewStart.plusHours(1).toInstant());
        assertThat(data.path("items")).as("the response must still list all three items").hasSize(3);
    }

    @Test
    @DisplayName("a DECLINED (terminal) sibling that spans past the last CONFIRMED item still counts "
            + "toward header endsAt = max(endsAt) over ALL items INCLUDING terminal ones (phase 30.1 D1 "
            + "'the endsAt defect' the whole track was named for)")
    void should_reportHeaderEndsAtAsTerminalSibling_when_terminalSiblingEndsLast() throws Exception {
        // A 2-item visit whose SECOND leg is deliberately WIDE (3h) so that, once declined, it still
        // spans past a later per-item move of the FIRST leg into the window it released (terminal
        // exemption from every overlap guard, phase 30.3).
        OffsetDateTime firstStart = futureStart();
        Visit v = seedVisitWithWindows("gap-terminal-last", List.of(
                new Window(firstStart, firstStart.plusHours(1)),
                new Window(firstStart.plusHours(1), firstStart.plusHours(4))));
        List<ItemRow> before = itemRows(v.id());
        UUID leg0 = before.get(0).id();
        UUID leg1 = before.get(1).id();

        declineItem(v.masterToken(), v.id(), leg1);
        assertThat(dbStatus(leg1)).isEqualTo("DECLINED");

        // Move leg0 INTO leg1's now-released window — legal per the terminal exemption — to a time
        // whose own endsAt (firstStart + 3h) is still LESS than leg1's untouched endsAt (firstStart + 4h).
        OffsetDateTime newStart = firstStart.plusHours(2);
        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), leg0, newStart);
        assertThat(resp.getStatusCode())
                .as("moving into a DECLINED sibling's released window must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode data = getAppointment(v.clientToken(), v.id());
        OffsetDateTime headerEndsAt = OffsetDateTime.parse(data.path("endsAt").asText());
        assertThat(headerEndsAt.toInstant())
                .as("header endsAt must reflect the DECLINED sibling's endsAt (firstStart+4h), which "
                        + "still exceeds the moved CONFIRMED leg's new endsAt (firstStart+3h) — the exact "
                        + "'row with greatest startsAt is not the row with greatest endsAt' defect")
                .isEqualTo(firstStart.plusHours(4).toInstant());
    }

    // ── 3. Self-overlap rejection — the design's explicit trap ──────────────────────────────────────
    //
    // QA audit F6 (cross-batch): this test cannot, by itself, prove assertNoSiblingOverlap fired
    // rather than the ordinary slot-availability guard (assertItemStartsOnAvailableSlot) — both
    // independently 409 on this exact scenario. Investigated and confirmed GENUINELY IMPOSSIBLE to
    // separate at the HTTP level in this domain, not merely inconvenient to construct:
    // SlotCalculationService.getAvailableSlots subtracts EVERY one of the master's CONFIRMED
    // bookings (bookingRepository.findOverlappingByMaster is scoped by masterId ALONE, never by
    // masterServiceId — see that query's own Javadoc) from the day's free ranges before generating
    // candidate slots. A CONFIRMED sibling of THIS SAME visit is, by definition, one of the
    // master's own CONFIRMED bookings, so its window is ALREADY excluded from "available" before
    // assertNoSiblingOverlap (which only ever inspects CONFIRMED siblings, phase 30.3) gets a
    // chance to run — and assertItemStartsOnAvailableSlot is checked strictly FIRST in
    // rescheduleAppointmentItem (line ~662, vs. assertNoSiblingOverlap at line ~670). There is no
    // target time that is simultaneously "on an available master slot" and "overlapping a CONFIRMED
    // sibling" — the two sets are disjoint by construction. This mirrors assertNoSiblingOverlap's
    // OWN Javadoc, which already documents the sibling booking-scoped `existsOverlapExcluding` call
    // and the DB EXCLUDE constraint as making the overlap "unreachable" for the SAME reason; the
    // slot-availability guard makes it triply so. The test below therefore intentionally pins "both
    // independent guards reject this case" rather than isolating one — that is the true, honest
    // behaviour, not a gap to close.

    @Test
    @DisplayName("moving a leg onto a CONFIRMED sibling's window is rejected with 409 and NOTHING "
            + "changes — a moved item must never double-book its own visit's master against themself "
            + "(existsOverlapExcluding only excludes the MOVED row, so a sibling of the SAME visit IS "
            + "caught, phase 30.3 D3(a); NOTE — per the F6 investigation above, this 409 may come from "
            + "EITHER assertNoSiblingOverlap or the slot-availability guard, and the two are provably "
            + "inseparable for a CONFIRMED sibling — this test pins the OUTCOME, not the mechanism)")
    void should_return409AndMoveNothing_when_movedLegOverlapsConfirmedSibling() throws Exception {
        Visit v = seedContiguousVisit("self-overlap", 2, futureStart());
        List<ItemRow> before = itemRows(v.id());
        UUID leg1 = before.get(1).id();
        // leg0 occupies [futureStart, futureStart+1h); land leg1 30 minutes INSIDE that window —
        // a valid 30-minute slot boundary (SlotCalculationService.SLOT_STEP) that overlaps leg0.
        OffsetDateTime overlappingStart = futureStart().plusMinutes(30);

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), leg1, overlappingStart);

        assertThat(resp.getStatusCode())
                .as("overlapping a CONFIRMED sibling of the same visit must be rejected — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        List<ItemRow> after = itemRows(v.id());
        assertThat(after.get(0).startsAt().toInstant())
                .as("leg0 must be completely unchanged by the rejected move")
                .isEqualTo(before.get(0).startsAt().toInstant());
        assertThat(after.get(1).startsAt().toInstant())
                .as("leg1 (the rejected target) must NOT have moved")
                .isEqualTo(before.get(1).startsAt().toInstant());
    }

    // ── 3b. Half-open boundary — QA audit F9 (cross-batch) ──────────────────────────────────────────

    @Test
    @DisplayName("moving a leg to land EXACTLY on a CONFIRMED sibling's endsAt (half-open boundary "
            + "touch) is LEGAL — 200, never a 409 — assertNoSiblingOverlap's half-open comparison "
            + "must not reject a back-to-back landing")
    void should_return200_when_movedLegLandsExactlyOnSiblingBoundary() throws Exception {
        Visit v = seedContiguousVisit("half-open-boundary", 2, futureStart());
        List<ItemRow> before = itemRows(v.id());
        UUID leg1 = before.get(1).id();
        OffsetDateTime leg0EndsAt = before.get(0).endsAt();
        // leg1 already starts exactly at leg0's endsAt in the untouched contiguous seed — move it
        // AWAY first, then back onto that exact boundary, so the half-open touch is proven as a
        // deliberately CHOSEN target, not merely an artifact of the seed's own initial layout.
        ResponseEntity<String> away = reschedule(v.clientToken(), v.id(), leg1, futureStart().plusDays(3));
        assertThat(away.getStatusCode())
                .as("fixture setup: moving leg1 away must succeed — body: %s", away.getBody())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> back = reschedule(v.clientToken(), v.id(), leg1, leg0EndsAt);

        assertThat(back.getStatusCode())
                .as("landing exactly on a sibling's endsAt is a legal half-open touch, not an "
                        + "overlap — body: %s", back.getBody())
                .isEqualTo(HttpStatus.OK);
        ItemRow leg1After = findById(itemRows(v.id()), leg1);
        assertThat(leg1After.startsAt().toInstant())
                .as("the boundary-touching move must actually have landed")
                .isEqualTo(leg0EndsAt.toInstant());
    }

    // ── 4. IDOR / membership + the load-bearing 403 → 404 → 409 ordering (D5/D7) ────────────────────

    @Test
    @DisplayName("a bookingId that belongs to a DIFFERENT appointment returns 404, even though the "
            + "caller IS authorized on the path's own appointmentId")
    void should_return404_when_bookingIdBelongsToDifferentAppointment() throws Exception {
        Visit visitA = seedContiguousVisit("membership-a", 2, futureStart());
        Visit visitB = seedContiguousVisit("membership-b", 2, futureStart().plusDays(1));
        UUID foreignBookingId = itemRows(visitB.id()).get(0).id();

        ResponseEntity<String> resp = reschedule(
                visitA.clientToken(), visitA.id(), foreignBookingId, futureStart().plusDays(2));

        assertThat(resp.getStatusCode())
                .as("a bookingId that is not a child of THIS appointmentId must 404 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(dbStatus(foreignBookingId))
                .as("the unrelated visit's booking must be completely untouched")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("a foreign client (not the visit's owner) attempting a per-item reschedule of "
            + "someone else's leg is denied with 403 and nothing moves — IDOR")
    void should_return403AndMoveNothing_when_foreignClientAttemptsPerItemReschedule() throws Exception {
        Visit v = seedContiguousVisit("foreign-client", 2, futureStart());
        UUID leg0 = itemRows(v.id()).get(0).id();
        String foreignClientEmail = "item-resched-foreign-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(foreignClientEmail, "CLIENT", null);
        String foreignClientToken = fixtures.tokenFor(foreignClientEmail);

        ResponseEntity<String> resp = reschedule(foreignClientToken, v.id(), leg0, futureStart().plusDays(2));

        assertThat(resp.getStatusCode())
                .as("a client with no ownership over the visit must be forbidden — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(leg0)).isEqualTo("CONFIRMED");
        assertThat(itemRows(v.id()).get(0).startsAt().toInstant())
                .isEqualTo(futureStart().toInstant());
    }

    @Test
    @DisplayName("ordering proof: an unauthorized caller passing a NON-CHILD bookingId on someone "
            + "else's appointment still gets 403, never 404 — authorization is decided BEFORE "
            + "membership, so this is not an existence oracle for either the appointment or the "
            + "booking id (phase 30.1 D7)")
    void should_return403NotFound404_when_unauthorizedCallerPassesNonChildBookingId() throws Exception {
        Visit v = seedContiguousVisit("ordering", 2, futureStart());
        String foreignClientEmail = "item-resched-ordering-foreign-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(foreignClientEmail, "CLIENT", null);
        String foreignClientToken = fixtures.tokenFor(foreignClientEmail);
        UUID nonChildBookingId = UUID.randomUUID();

        ResponseEntity<String> resp = reschedule(foreignClientToken, v.id(), nonChildBookingId, futureStart().plusDays(2));

        assertThat(resp.getStatusCode())
                .as("an unauthorized caller must be rejected at the AUTHORIZATION step — 403 — before "
                        + "the service ever checks whether bookingId is a real child (which would be 404) "
                        + "— body: %s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a read-only SALON_MASTER is denied with 403 on the per-item route — excluded exactly "
            + "as on the whole-visit route, regardless of which layer (controller @PreAuthorize vs "
            + "service-layer enforceCanManageAppointment) performs the check")
    void should_return403_when_salonMasterAttemptsPerItemReschedule() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("item-resched-salon-owner-" + System.nanoTime() + "@beautica.test");
        String clientEmail = "item-resched-salon-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceB = fixtures.createSalonService(salon.salonId(), salon.masterId());
        addWorkingHoursForEveryDay(salon.masterId());
        OffsetDateTime firstStart = futureStart();
        UUID appointmentId = seedVisitRows(clientId, salon.masterId(), salon.salonId(),
                new UUID[]{serviceA, serviceB}, firstStart);
        UUID leg0 = itemRows(appointmentId).get(0).id();
        String salonMasterToken = fixtures.tokenFor(salon.masterEmail());

        ResponseEntity<String> resp = reschedule(salonMasterToken, appointmentId, leg0, futureStart().plusDays(2));

        assertThat(resp.getStatusCode())
                .as("SALON_MASTER (read-only calendar access) must never per-item reschedule a visit")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(leg0)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("an authorized INDEPENDENT_MASTER provider CAN move a leg of their own visit — 200 — "
            + "proving the provider path still works under either enforcement layer")
    void should_return200_when_authorizedProviderMovesLeg() throws Exception {
        Visit v = seedContiguousVisit("provider-ok", 2, futureStart());
        UUID leg0 = itemRows(v.id()).get(0).id();

        ResponseEntity<String> resp = reschedule(v.masterToken(), v.id(), leg0, futureStart().plusDays(2));

        assertThat(resp.getStatusCode())
                .as("the visit's own provider must be able to move a leg — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(dbStatus(leg0)).isEqualTo("CONFIRMED");
    }

    // ── 6. Per-ROW temporal guard, not visit-level (phase 30.1 D6) ──────────────────────────────────

    @Test
    @DisplayName("a visit whose FIRST leg has already ELAPSED but whose LATER leg has not — moving the "
            + "LATER (still-future) leg must SUCCEED; an elapsed sibling must never freeze a still-"
            + "future leg, which is exactly the outcome the per-item feature exists to prevent")
    void should_succeed_when_clientMovesFutureLegWhileEarlierSiblingHasElapsed() throws Exception {
        OffsetDateTime pastAnchor = OffsetDateTime.now(ZoneOffset.UTC).minusHours(3).withNano(0);
        OffsetDateTime futureAnchor = futureStart();
        Visit v = seedVisitWithWindows("elapsed-sibling", List.of(
                new Window(pastAnchor, pastAnchor.plusHours(1)),
                new Window(futureAnchor, futureAnchor.plusHours(1))));
        List<ItemRow> before = itemRows(v.id());
        UUID elapsedLeg = before.get(0).id();
        UUID futureLeg = before.get(1).id();
        assertThat(before.get(0).endsAt().toInstant())
                .as("sanity: the first leg really is elapsed")
                .isBefore(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), futureLeg, futureAnchor.plusDays(2));

        assertThat(resp.getStatusCode())
                .as("moving a still-future leg must succeed even though a SIBLING has elapsed — body: %s",
                        resp.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(dbStatus(elapsedLeg))
                .as("the elapsed leg itself is untouched by moving its sibling")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("moving the ELAPSED leg itself (not a sibling) is rejected with 409 "
            + "BOOKING_ALREADY_ELAPSED — the per-row guard still protects the target row's own window")
    void should_return409_when_clientMovesTheElapsedLegItself() throws Exception {
        OffsetDateTime pastAnchor = OffsetDateTime.now(ZoneOffset.UTC).minusHours(3).withNano(0);
        OffsetDateTime futureAnchor = futureStart();
        Visit v = seedVisitWithWindows("elapsed-target", List.of(
                new Window(pastAnchor, pastAnchor.plusHours(1)),
                new Window(futureAnchor, futureAnchor.plusHours(1))));
        UUID elapsedLeg = itemRows(v.id()).get(0).id();

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), elapsedLeg, futureAnchor.plusDays(2));

        assertThat(resp.getStatusCode())
                .as("moving the elapsed row itself must still be refused — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(elapsedLeg)).isEqualTo("CONFIRMED");
    }

    // ── 7. Status preconditions (D7) ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a target child already DECLINED cannot be rescheduled — 409, nothing changes")
    void should_return409_when_targetLegAlreadyDeclined() throws Exception {
        Visit v = seedContiguousVisit("already-declined", 2, futureStart());
        UUID leg0 = itemRows(v.id()).get(0).id();
        declineItem(v.masterToken(), v.id(), leg0);
        assertThat(dbStatus(leg0)).isEqualTo("DECLINED");

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), leg0, futureStart().plusDays(2));

        assertThat(resp.getStatusCode())
                .as("an already-DECLINED leg must not be reschedulable — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(leg0)).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("a target child already CANCELLED cannot be rescheduled — 409, nothing changes")
    void should_return409_when_targetLegAlreadyCancelled() throws Exception {
        Visit v = seedContiguousVisit("already-cancelled", 2, futureStart());
        UUID leg0 = itemRows(v.id()).get(0).id();
        cancelItem(v.clientToken(), v.id(), leg0);
        assertThat(dbStatus(leg0)).isEqualTo("CANCELLED");

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), leg0, futureStart().plusDays(2));

        assertThat(resp.getStatusCode())
                .as("an already-CANCELLED leg must not be reschedulable — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(leg0)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a header that is no longer CONFIRMED (whole visit already CANCELLED) rejects a "
            + "per-item reschedule with 409 — the header lock doubles as the status guard")
    void should_return409_when_headerNotConfirmed() throws Exception {
        Visit v = seedContiguousVisit("header-not-confirmed", 2, futureStart());
        UUID leg0 = itemRows(v.id()).get(0).id();
        jdbcTemplate.update("UPDATE appointments SET status = 'CANCELLED' WHERE id = ?", v.id());
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED' WHERE appointment_id = ?", v.id());

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), leg0, futureStart().plusDays(2));

        assertThat(resp.getStatusCode())
                .as("a non-CONFIRMED header must reject the per-item move — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ── 7. Outbox addressing — design decision D8, QA audit F10 (cross-batch) ──────────────────────

    @Test
    @DisplayName("D8: a per-item reschedule of a NON-FIRST leg enqueues its BOOKING_RESCHEDULED "
            + "outbox row against the MOVED CHILD's id, never item 0's, with a payload containing "
            + "ONLY initiatedBy — no note/comment/PII")
    void should_addressOutboxToMovedChildOnly_when_nonFirstLegRescheduled() throws Exception {
        Visit v = seedContiguousVisit("outbox-addressing", 3, futureStart());
        List<ItemRow> before = itemRows(v.id());
        UUID item0Id = before.get(0).id();
        UUID movedLegId = before.get(2).id();
        OffsetDateTime newStart = futureStart().plusDays(4).withHour(9);

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), movedLegId, newStart);
        assertThat(resp.getStatusCode())
                .as("fixture setup: moving the third leg must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);

        Integer countOnMovedChild = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE aggregate_id = ? AND event_type = 'BOOKING_RESCHEDULED'",
                Integer.class, movedLegId);
        assertThat(countOnMovedChild)
                .as("exactly one BOOKING_RESCHEDULED row referencing the MOVED child (D8)")
                .isEqualTo(1);
        Integer countOnItem0 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE aggregate_id = ? AND event_type = 'BOOKING_RESCHEDULED'",
                Integer.class, item0Id);
        assertThat(countOnItem0)
                .as("the UNTOUCHED item 0 must NOT receive the outbox row — D8 forbids the 'always "
                        + "reference item 0' shape the whole-visit path uses")
                .isZero();

        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM notification_outbox WHERE aggregate_id = ? AND event_type = 'BOOKING_RESCHEDULED'",
                String.class, movedLegId);
        JsonNode payloadNode = objectMapper.readTree(payload);
        assertThat(payloadNode.fieldNames())
                .as("the payload must carry ONLY initiatedBy — no note/comment/PII ever enters it")
                .toIterable().containsExactly("initiatedBy");
        assertThat(payloadNode.path("initiatedBy").asText()).isEqualTo("CLIENT");
    }

    // ── 8. Whole-visit reschedule after per-item moves re-contiguates (ACCEPTED consequence, L4) ────

    @Test
    @DisplayName("performing a WHOLE-VISIT reschedule after a per-item move re-lays-out every CONFIRMED "
            + "item back into ONE contiguous block, DISCARDING the manual per-item separation — this is "
            + "the phase 30.1 D2 ACCEPTED consequence of L4 ('the whole-visit route is unchanged'), "
            + "pinned here as INTENDED behaviour, not a regression")
    void should_reContiguateConfirmedItems_when_wholeVisitRescheduledAfterPerItemMove() throws Exception {
        Visit v = seedContiguousVisit("recontiguate", 3, futureStart());
        List<ItemRow> before = itemRows(v.id());
        UUID middleId = before.get(1).id();
        OffsetDateTime detachedStart = futureStart().plusDays(10).withHour(9);

        ResponseEntity<String> perItem = reschedule(v.clientToken(), v.id(), middleId, detachedStart);
        assertThat(perItem.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<ItemRow> afterPerItemMove = itemRows(v.id());
        assertThat(afterPerItemMove.get(1).startsAt().toInstant())
                .as("sanity: the visit is now genuinely non-contiguous")
                .isNotEqualTo(afterPerItemMove.get(0).endsAt().toInstant());

        OffsetDateTime wholeVisitNewStart = futureStart().plusDays(20).withHour(11);
        ResponseEntity<String> wholeVisit = rescheduleWholeVisit(v.clientToken(), v.id(), wholeVisitNewStart);

        assertThat(wholeVisit.getStatusCode())
                .as("the whole-visit reschedule must still succeed after a prior per-item move — body: %s",
                        wholeVisit.getBody())
                .isEqualTo(HttpStatus.OK);
        List<ItemRow> afterWholeVisit = itemRows(v.id());
        assertThat(afterWholeVisit).hasSize(3);
        assertThat(afterWholeVisit.get(0).startsAt().toInstant()).isEqualTo(wholeVisitNewStart.toInstant());
        assertThat(afterWholeVisit.get(0).endsAt().toInstant())
                .as("item 0 is back-to-back with item 1 — CONTIGUOUS again")
                .isEqualTo(afterWholeVisit.get(1).startsAt().toInstant());
        assertThat(afterWholeVisit.get(1).endsAt().toInstant())
                .as("item 1 is back-to-back with item 2 — CONTIGUOUS again")
                .isEqualTo(afterWholeVisit.get(2).startsAt().toInstant());
    }

    // ── 9. Concurrency — perf/QA audit F8a (cross-batch) ────────────────────────────────────────────
    //
    // Exercises the NEW three-lock ordering rescheduleAppointmentItem introduced (header → client →
    // master, phase 30.1/30.4) — genuinely new code, not a re-run of an existing regression. Pattern
    // reused from this repo's established concurrency-IT convention: a raw CountDownLatch "go" +
    // virtual threads racing for the SAME resource (mirrors GuestBookingConcurrencyIT) — no @SpyBean
    // rendezvous is needed here since both racers call the IDENTICAL method. The second F8 scenario
    // (a per-item reschedule racing a WHOLE-VISIT decline) needs a @SpyBean CyclicBarrier rendezvous
    // on two DIFFERENT package-private lock methods and therefore lives in
    // AppointmentCrossPathTransitionConcurrencyIT (com.beautica.booking.service) instead — see the
    // note further down where that test would otherwise have gone.

    @Test
    @DisplayName("F8a: two per-item reschedules racing the SAME master onto the IDENTICAL new window "
            + "(two different visits) — exactly one wins (200) and one loses (409), never both, never "
            + "neither — the master-scoped advisory lock + existsOverlapExcluding/EXCLUDE constraint "
            + "serialize the race deterministically")
    void should_allowExactlyOneWinner_when_twoPerItemReschedulesRaceTheSameMasterWindow() throws Exception {
        String masterEmail = "item-resched-race-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        addWorkingHoursForEveryDay(masterId);
        String clientAEmail = "item-resched-race-a-" + System.nanoTime() + "@beautica.test";
        UUID clientAId = fixtures.createUser(clientAEmail, "CLIENT", null);
        String clientBEmail = "item-resched-race-b-" + System.nanoTime() + "@beautica.test";
        UUID clientBId = fixtures.createUser(clientBEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        OffsetDateTime originalA = futureStart();
        OffsetDateTime originalB = futureStart().plusDays(1);
        UUID appointmentA = seedVisitRows(clientAId, masterId, null, new UUID[]{serviceA}, originalA);
        UUID appointmentB = seedVisitRows(clientBId, masterId, null, new UUID[]{serviceB}, originalB);
        UUID bookingA = itemRows(appointmentA).get(0).id();
        UUID bookingB = itemRows(appointmentB).get(0).id();
        String tokenA = fixtures.tokenFor(clientAEmail);
        String tokenB = fixtures.tokenFor(clientBEmail);
        OffsetDateTime contestedWindow = futureStart().plusDays(9).withHour(14);

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<ResponseEntity<String>> respA = new AtomicReference<>();
        AtomicReference<ResponseEntity<String>> respB = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                respA.set(reschedule(tokenA, appointmentA, bookingA, contestedWindow));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                respB.set(reschedule(tokenB, appointmentB, bookingB, contestedWindow));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        go.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);

        assertThat(finished).as("both racers must finish within 30s — no hang, no unbounded lock wait").isTrue();
        assertThat(List.of(respA.get().getStatusCode(), respB.get().getStatusCode()))
                .as("exactly one racer must win (200) and the other must lose (409) — body A: %s, body B: %s",
                        respA.get().getBody(), respB.get().getBody())
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);

        boolean aWon = respA.get().getStatusCode().equals(HttpStatus.OK);
        UUID winnerBooking = aWon ? bookingA : bookingB;
        UUID loserBooking = aWon ? bookingB : bookingA;
        OffsetDateTime loserOriginal = aWon ? originalB : originalA;
        assertThat(dbStartsAt(winnerBooking).toInstant())
                .as("the winner must actually occupy the contested window")
                .isEqualTo(contestedWindow.toInstant());
        assertThat(dbStartsAt(loserBooking).toInstant())
                .as("the loser must be completely untouched — still at its ORIGINAL window")
                .isEqualTo(loserOriginal.toInstant());
    }

    // NOTE (F8b — per-item reschedule racing a whole-visit decline): that test lives in
    // AppointmentCrossPathTransitionConcurrencyIT (com.beautica.booking.service), NOT here — it
    // needs a @SpyBean CyclicBarrier rendezvous on AppointmentTransitionService's package-private
    // lockHeaderForWholeVisitTransition/lockAppointmentHeaderBeforeItemReschedule methods, which this
    // class's package (com.beautica.booking) cannot see. Reusing that class (rather than widening
    // either method's visibility, or duplicating its @SpyBean scaffolding here) follows this repo's
    // established "one shared concurrency-IT-per-package-for-@SpyBean-races" convention exactly —
    // see that class's own Javadoc.

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A seeded independent-master CONFIRMED visit plus the owning client/master tokens and master id. */
    private record Visit(UUID id, UUID masterId, String clientToken, String masterToken) {}

    private record ItemRow(UUID id, OffsetDateTime startsAt, OffsetDateTime endsAt, String status) {}

    private record Window(OffsetDateTime startsAt, OffsetDateTime endsAt) {}

    private ItemRow findById(List<ItemRow> rows, UUID id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("item " + id + " not found in " + rows));
    }

    private Visit seedContiguousVisit(String tag, int itemCount, OffsetDateTime firstStart) throws Exception {
        List<Window> windows = new ArrayList<>();
        OffsetDateTime cursor = firstStart;
        for (int i = 0; i < itemCount; i++) {
            windows.add(new Window(cursor, cursor.plusHours(1)));
            cursor = cursor.plusHours(1);
        }
        return seedVisitWithWindows(tag, windows);
    }

    /** Seeds an independent-master visit whose items carry the EXACT windows supplied — no chaining
     * assumption, so callers can construct gaps, wide terminal spans, or a past+future pair. */
    private Visit seedVisitWithWindows(String tag, List<Window> windows) throws Exception {
        String masterEmail = "item-resched-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "item-resched-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);

        UUID[] serviceIds = new UUID[windows.size()];
        for (int i = 0; i < windows.size(); i++) {
            serviceIds[i] = fixtures.createIndependentMasterService(masterId);
        }
        addWorkingHoursForEveryDay(masterId);

        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, 'CONFIRMED', 'APP', NOW(), NOW())",
                appointmentId, clientId);
        for (int i = 0; i < windows.size(); i++) {
            Window w = windows.get(i);
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, appointment_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, NULL, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', ?, NOW(), NOW())",
                    UUID.randomUUID(), clientId, masterId, serviceIds[i], w.startsAt(), w.endsAt(), appointmentId);
        }
        return new Visit(appointmentId, masterId, fixtures.tokenFor(clientEmail), fixtures.tokenFor(masterEmail));
    }

    /** Mirrors {@code AppointmentRescheduleIT#seedVisitRows} for the salon-master authorization test. */
    private UUID seedVisitRows(UUID clientId, UUID masterId, UUID salonId, UUID[] serviceIds, OffsetDateTime firstStart) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CONFIRMED', 'APP', NOW(), NOW())",
                appointmentId, clientId, salonId);
        OffsetDateTime cursor = firstStart;
        for (UUID serviceId : serviceIds) {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, appointment_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', ?, NOW(), NOW())",
                    UUID.randomUUID(), clientId, masterId, serviceId, salonId,
                    cursor, cursor.plusHours(1), appointmentId);
            cursor = cursor.plusHours(1);
        }
        return appointmentId;
    }

    private ResponseEntity<String> reschedule(
            String token, UUID appointmentId, UUID bookingId, OffsetDateTime newStartsAt) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        String body = "{\"newStartsAt\":\"" + newStartsAt + "\"}";
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/reschedule",
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> rescheduleWholeVisit(String token, UUID appointmentId, OffsetDateTime newStartsAt) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        String body = "{\"newStartsAt\":\"" + newStartsAt + "\"}";
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/reschedule",
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private void declineItem(String masterToken, UUID appointmentId, UUID bookingId) {
        HttpHeaders headers = fixtures.bearerHeaders(masterToken);
        ResponseEntity<String> resp = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/decline",
                HttpMethod.PATCH, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode())
                .as("fixture setup: per-item decline must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void cancelItem(String clientToken, UUID appointmentId, UUID bookingId) {
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"cancellationReason\":\"CLIENT_CANCELLED\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode())
                .as("fixture setup: per-item cancel must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private JsonNode getAppointment(String token, UUID appointmentId) throws Exception {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        ResponseEntity<String> resp = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode())
                .as("fixture read: GET appointment must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private List<ItemRow> itemRows(UUID appointmentId) {
        return jdbcTemplate.query(
                "SELECT id, starts_at, ends_at, status FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                (rs, rowNum) -> new ItemRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getObject("starts_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getString("status")),
                appointmentId);
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private OffsetDateTime dbStartsAt(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, bookingId);
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    private static OffsetDateTime futureStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private void addWorkingHoursForEveryDay(UUID masterId) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                        + "VALUES (?, ?, DATE '2020-01-01', NULL)",
                scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, '00:00', '23:59')",
                    UUID.randomUUID(), scheduleId, day);
        }
    }
}
