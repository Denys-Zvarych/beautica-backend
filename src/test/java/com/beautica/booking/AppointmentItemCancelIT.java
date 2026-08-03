package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Full-HTTP-stack suite for {@code PATCH /appointments/{appointmentId}/services/{bookingId}/cancel}
 * (track 30.6) — the NEW appointment-scoped, per-item CLIENT cancel route.
 *
 * <p>This route deliberately reuses {@code BookingService#cancelBooking} VERBATIM (phase 30.6 D1) so
 * it can never diverge from the pre-existing {@code PATCH /bookings/{bookingId}/cancel}. The
 * "non-divergence" test below is therefore not incidental coverage — it is the load-bearing proof of
 * the design's central claim for this route.
 *
 * <p>Complements {@link BookingAppointmentChildTransitionGuardIT}, which already proves the per-leg
 * cancel INVARIANTS (sibling untouched, header collapse) through the OLD {@code /bookings/{id}/cancel}
 * path. This suite proves the SAME invariants through the NEW appointment-scoped path, plus the
 * membership/authorization dimensions that are specific to having an {@code appointmentId} in the URL
 * at all (404 on a non-child bookingId, 403-before-404 ordering).
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /appointments/{appointmentId}/services/{bookingId}/cancel — per-item client cancel")
class AppointmentItemCancelIT extends AbstractIntegrationTest {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final String BOOKINGS_URL = "/api/v1/bookings";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── 5. Per-item cancel — the core happy paths ───────────────────────────────────────────────────

    @Test
    @DisplayName("cancelling ONE leg of a three-service visit returns 204, that leg is CANCELLED with "
            + "the supplied reason/note, BOTH other legs stay CONFIRMED, and the header stays CONFIRMED")
    void should_cancelOnlyThatLeg_when_clientCancelsOneLegOfThreeServiceVisit() throws Exception {
        Visit v = seedContiguousVisit("three-leg", 3, futureStart());
        List<UUID> legs = itemIds(v.id());

        ResponseEntity<String> resp = cancel(v.clientToken(), v.id(), legs.get(0),
                "{\"cancellationReason\":\"CLIENT_CANCELLED\",\"comment\":\"одна послуга більше не потрібна\"}");

        assertThat(resp.getStatusCode())
                .as("cancelling one leg of a three-service visit must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(legs.get(0))).as("the targeted leg is now CANCELLED").isEqualTo("CANCELLED");
        assertThat(dbCancellationReason(legs.get(0))).isEqualTo("CLIENT_CANCELLED");
        assertThat(dbClientCancellationNote(legs.get(0))).isEqualTo("одна послуга більше не потрібна");
        assertThat(dbStatus(legs.get(1))).as("sibling leg 1 stays CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(dbStatus(legs.get(2))).as("sibling leg 2 stays CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(v.id()))
                .as("the header stays CONFIRMED while two siblings remain CONFIRMED").isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("cancelling the LAST CONFIRMED leg (after its siblings were already cancelled/moved "
            + "out) collapses the HEADER to CANCELLED with reason CLIENT_CANCELLED and that cancel's "
            + "note — mirrors the OLD route's header-recompute invariant through the NEW route")
    void should_collapseHeaderToCancelled_when_lastConfirmedLegCancelledViaNewRoute() throws Exception {
        Visit v = seedContiguousVisit("collapse", 2, futureStart());
        List<UUID> legs = itemIds(v.id());

        ResponseEntity<String> first = cancel(v.clientToken(), v.id(), legs.get(0),
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(appointmentStatus(v.id()))
                .as("header still CONFIRMED after the FIRST leg — one sibling remains").isEqualTo("CONFIRMED");

        ResponseEntity<String> second = cancel(v.clientToken(), v.id(), legs.get(1),
                "{\"cancellationReason\":\"CLIENT_CANCELLED\",\"comment\":\"скасовуємо весь візит\"}");

        assertThat(second.getStatusCode())
                .as("cancelling the LAST confirmed leg must also succeed — body: %s", second.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(legs.get(0))).isEqualTo("CANCELLED");
        assertThat(dbStatus(legs.get(1))).isEqualTo("CANCELLED");
        assertThat(appointmentStatus(v.id()))
                .as("no CONFIRMED sibling remains — the header collapses to CANCELLED").isEqualTo("CANCELLED");
        assertThat(appointmentCancellationReason(v.id())).isEqualTo("CLIENT_CANCELLED");
        assertThat(appointmentClientCancellationNote(v.id()))
                .as("the header carries the note from the cancel that actually collapsed it")
                .isEqualTo("скасовуємо весь візит");
    }

    @Test
    @DisplayName("non-divergence (phase 30.6 D6): cancelling equivalent legs of two IDENTICAL visits — "
            + "one via the NEW /appointments/.../services/{bookingId}/cancel route, the other via the "
            + "OLD /bookings/{bookingId}/cancel route — leaves byte-identical child row state "
            + "(status, cancellation_reason, client_cancellation_note); the two routes must never diverge "
            + "because the new one calls the exact same BookingService#cancelBooking method")
    void should_produceByteIdenticalOutcome_when_comparingNewRouteToLegacyBookingsCancelRoute() throws Exception {
        Visit viaNewRoute = seedContiguousVisit("non-divergence-new", 2, futureStart());
        Visit viaLegacyRoute = seedContiguousVisit("non-divergence-legacy", 2, futureStart().plusDays(1));
        UUID legNew = itemIds(viaNewRoute.id()).get(0);
        UUID legLegacy = itemIds(viaLegacyRoute.id()).get(0);
        String requestBody = "{\"cancellationReason\":\"CLIENT_CANCELLED\",\"comment\":\"порівняльний тест\"}";

        ResponseEntity<String> newRouteResp = cancel(viaNewRoute.clientToken(), viaNewRoute.id(), legNew, requestBody);
        ResponseEntity<String> legacyRouteResp = restTemplate.exchange(
                BOOKINGS_URL + "/" + legLegacy + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>(requestBody, fixtures.bearerHeaders(viaLegacyRoute.clientToken())), String.class);

        assertThat(newRouteResp.getStatusCode())
                .as("new route must succeed — body: %s", newRouteResp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(legacyRouteResp.getStatusCode())
                .as("legacy route must succeed — body: %s", legacyRouteResp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(legNew)).as("status must match").isEqualTo(dbStatus(legLegacy)).isEqualTo("CANCELLED");
        assertThat(dbCancellationReason(legNew)).as("cancellation_reason must match")
                .isEqualTo(dbCancellationReason(legLegacy)).isEqualTo("CLIENT_CANCELLED");
        assertThat(dbClientCancellationNote(legNew)).as("client_cancellation_note must match")
                .isEqualTo(dbClientCancellationNote(legLegacy)).isEqualTo("порівняльний тест");
        assertThat(appointmentStatus(viaNewRoute.id())).as("identical header outcome — both stay CONFIRMED")
                .isEqualTo(appointmentStatus(viaLegacyRoute.id())).isEqualTo("CONFIRMED");
    }

    // ── 4. IDOR / membership ordering (mirrors the reschedule route's D5/D7) ────────────────────────

    @Test
    @DisplayName("a bookingId that belongs to a DIFFERENT appointment returns 404, even though the "
            + "caller IS authorized on the path's own appointmentId")
    void should_return404_when_bookingIdBelongsToDifferentAppointment() throws Exception {
        Visit visitA = seedContiguousVisit("cancel-membership-a", 2, futureStart());
        Visit visitB = seedContiguousVisit("cancel-membership-b", 2, futureStart().plusDays(1));
        UUID foreignBookingId = itemIds(visitB.id()).get(0);

        ResponseEntity<String> resp = cancel(visitA.clientToken(), visitA.id(), foreignBookingId,
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertThat(resp.getStatusCode())
                .as("a bookingId that is not a child of THIS appointmentId must 404 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(dbStatus(foreignBookingId))
                .as("the unrelated visit's booking must be completely untouched").isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("a foreign CLIENT (not the visit's owner) cancelling someone else's leg is denied "
            + "with 403, byte-identical to the 403 for a booking id that does not exist at all — no "
            + "existence oracle leaks across accounts")
    void should_return403IndistinguishableFromNonexistentId_when_foreignClientCancelsAnothersLeg() throws Exception {
        Visit v = seedContiguousVisit("cancel-foreign-client", 2, futureStart());
        UUID leg0 = itemIds(v.id()).get(0);
        String foreignClientEmail = "item-cancel-foreign-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(foreignClientEmail, "CLIENT", null);
        String foreignClientToken = fixtures.tokenFor(foreignClientEmail);

        ResponseEntity<String> foreignAttempt = cancel(foreignClientToken, v.id(), leg0,
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");
        ResponseEntity<String> nonexistentAttempt = cancel(foreignClientToken, UUID.randomUUID(), leg0,
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertThat(foreignAttempt.getStatusCode())
                .as("a client cancelling ANOTHER client's leg must be refused — body: %s", foreignAttempt.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(nonexistentAttempt.getStatusCode())
                .as("a nonexistent appointment id must be refused with the SAME status — body: %s",
                        nonexistentAttempt.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreignAttempt.getBody())
                .as("the foreign-owner response body must be byte-identical to the nonexistent-appointment "
                        + "response — no existence oracle leaks through a differently-worded message")
                .isEqualTo(nonexistentAttempt.getBody());
        assertThat(dbStatus(leg0)).as("the foreign attempt must not have mutated the leg").isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("ordering proof: an unauthorized caller passing a NON-CHILD bookingId on someone "
            + "else's appointment still gets 403, never 404 — authorization on the visit precedes the "
            + "bookingId membership check")
    void should_return403NotFound404_when_unauthorizedCallerPassesNonChildBookingId() throws Exception {
        Visit v = seedContiguousVisit("cancel-ordering", 2, futureStart());
        String foreignClientEmail = "item-cancel-ordering-foreign-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(foreignClientEmail, "CLIENT", null);
        String foreignClientToken = fixtures.tokenFor(foreignClientEmail);
        UUID nonChildBookingId = UUID.randomUUID();

        ResponseEntity<String> resp = cancel(foreignClientToken, v.id(), nonChildBookingId,
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertThat(resp.getStatusCode())
                .as("an unauthorized caller must be rejected at the AUTHORIZATION step (403) before the "
                        + "service ever checks bookingId membership (which would be 404) — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Role gate — CLIENT-only (D5), survives either enforcement layer ────────────────────────────

    @Test
    @DisplayName("a PROVIDER (INDEPENDENT_MASTER) attempting the per-item CLIENT-only cancel route is "
            + "denied with 403 — this route has no provider arm at all, unlike reschedule")
    void should_return403_when_providerRoleAttemptsPerItemCancel() throws Exception {
        Visit v = seedContiguousVisit("cancel-provider-role", 2, futureStart());
        UUID leg0 = itemIds(v.id()).get(0);

        ResponseEntity<String> resp = cancel(v.masterToken(), v.id(), leg0,
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertThat(resp.getStatusCode())
                .as("the visit's OWN provider must still be refused — this route is CLIENT-only")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(leg0)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("a read-only SALON_MASTER is denied with 403 on the per-item cancel route")
    void should_return403_when_salonMasterAttemptsPerItemCancel() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("item-cancel-salon-owner-" + System.nanoTime() + "@beautica.test");
        String clientEmail = "item-cancel-salon-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceB = fixtures.createSalonService(salon.salonId(), salon.masterId());
        addWorkingHoursForEveryDay(salon.masterId());
        UUID appointmentId = seedVisitRows(clientId, salon.masterId(), salon.salonId(),
                new UUID[]{serviceA, serviceB}, futureStart());
        UUID leg0 = itemIds(appointmentId).get(0);
        String salonMasterToken = fixtures.tokenFor(salon.masterEmail());

        ResponseEntity<String> resp = cancel(salonMasterToken, appointmentId, leg0,
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(leg0)).isEqualTo("CONFIRMED");
    }

    // ── 7. Status precondition — replay guard (matches the legacy route's 400, not 409) ─────────────

    @Test
    @DisplayName("cancelling the SAME leg twice via the new route returns 400 on the replay — the "
            + "identical CONFIRMED-status guard BookingService#cancelBooking uses for any non-CONFIRMED "
            + "booking (verified precedent: BookingAppointmentChildTransitionGuardIT's legacy-route "
            + "replay test gets the same 400, never 409 — the two routes share one guard, one status)")
    void should_return400OnReplay_when_sameLegCancelledTwiceViaNewRoute() throws Exception {
        Visit v = seedContiguousVisit("cancel-replay", 2, futureStart());
        UUID leg0 = itemIds(v.id()).get(0);

        ResponseEntity<String> first = cancel(v.clientToken(), v.id(), leg0, "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> replay = cancel(v.clientToken(), v.id(), leg0, "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertThat(replay.getStatusCode())
                .as("a replayed cancel of an already-CANCELLED leg must be rejected, not silently "
                        + "accepted or double-processed — body: %s", replay.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dbStatus(leg0)).as("the leg stays CANCELLED as the first call left it").isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("missing cancellationReason returns 400 and writes nothing")
    void should_return400_when_cancellationReasonMissing() throws Exception {
        Visit v = seedContiguousVisit("cancel-missing-reason", 2, futureStart());
        UUID leg0 = itemIds(v.id()).get(0);

        ResponseEntity<String> resp = cancel(v.clientToken(), v.id(), leg0, "{}");

        assertThat(resp.getStatusCode())
                .as("a missing cancellationReason must fail bean validation — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dbStatus(leg0)).isEqualTo("CONFIRMED");
    }

    // ── elapsed-leg guard, pinned directly on the NEW route — QA audit F7 (cross-batch) ───────────────

    @Test
    @DisplayName("F7: cancelling an ELAPSED leg via the NEW per-item route returns 409 "
            + "BOOKING_ALREADY_ELAPSED, pinned directly on THIS route rather than only indirectly via "
            + "the shared assertNotElapsedForClient guard's other callers")
    void should_return409_when_cancellingAnElapsedLegViaNewRoute() throws Exception {
        OffsetDateTime pastAnchor = OffsetDateTime.now(ZoneOffset.UTC).minusHours(3).withNano(0);
        Visit v = seedContiguousVisit("cancel-elapsed", 1, pastAnchor);
        UUID leg0 = itemIds(v.id()).get(0);

        ResponseEntity<String> resp = cancel(v.clientToken(), v.id(), leg0,
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertThat(resp.getStatusCode())
                .as("an elapsed leg must be read-only for the client via the NEW route too — body: %s",
                        resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(leg0))
                .as("the elapsed leg must be untouched by the rejected cancel").isEqualTo("CONFIRMED");
    }

    // ── outbox / slot release ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a per-item cancel enqueues exactly ONE STATUS_CHANGED outbox row for the cancelled "
            + "child, with no note text ever entering the payload")
    void should_enqueueOneStatusChangedRow_when_legCancelled() throws Exception {
        Visit v = seedContiguousVisit("cancel-outbox", 2, futureStart());
        UUID leg0 = itemIds(v.id()).get(0);

        ResponseEntity<String> resp = cancel(v.clientToken(), v.id(), leg0,
                "{\"cancellationReason\":\"CLIENT_CANCELLED\",\"comment\":\"конфіденційна причина\"}");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE aggregate_id = ? AND event_type = 'STATUS_CHANGED'",
                Integer.class, leg0);
        assertThat(outboxCount).as("exactly one STATUS_CHANGED row for the cancelled child").isEqualTo(1);
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM notification_outbox WHERE aggregate_id = ? AND event_type = 'STATUS_CHANGED'",
                String.class, leg0);
        assertThat(payload).as("no note text ever enters the outbox payload (locked rule)").isNull();
    }

    @Test
    @DisplayName("the cancelled leg's slot is released: a subsequent booking of the EXACT same "
            + "master+service+time by a different client succeeds")
    void should_releaseSlot_when_legCancelled() throws Exception {
        Visit v = seedContiguousVisit("cancel-slot-release", 2, futureStart());
        UUID leg0 = itemIds(v.id()).get(0);
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT master_service_id FROM bookings WHERE id = ?", UUID.class, leg0);

        ResponseEntity<String> cancelResp = cancel(v.clientToken(), v.id(), leg0,
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");
        assertThat(cancelResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String otherClientEmail = "item-cancel-slot-release-other-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(otherClientEmail, "CLIENT", null);
        String otherClientToken = fixtures.tokenFor(otherClientEmail);
        var request = new com.beautica.booking.dto.CreateBookingRequest(
                v.masterId(), masterServiceId, futureStart().atZoneSameInstant(java.time.ZoneOffset.UTC), null, null);
        ResponseEntity<String> rebook = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(otherClientToken)), String.class);

        assertThat(rebook.getStatusCode())
                .as("the exact window vacated by the cancelled leg must be re-bookable — body: %s", rebook.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Visit(UUID id, UUID masterId, String clientToken, String masterToken) {}

    private Visit seedContiguousVisit(String tag, int itemCount, OffsetDateTime firstStart) throws Exception {
        String masterEmail = "item-cancel-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "item-cancel-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID[] serviceIds = new UUID[itemCount];
        for (int i = 0; i < itemCount; i++) {
            serviceIds[i] = fixtures.createIndependentMasterService(masterId);
        }
        addWorkingHoursForEveryDay(masterId);
        UUID appointmentId = seedVisitRows(clientId, masterId, null, serviceIds, firstStart);
        return new Visit(appointmentId, masterId, fixtures.tokenFor(clientEmail), fixtures.tokenFor(masterEmail));
    }

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

    private List<UUID> itemIds(UUID appointmentId) {
        return jdbcTemplate.query(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                appointmentId);
    }

    private ResponseEntity<String> cancel(String token, UUID appointmentId, UUID bookingId, String body) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String dbCancellationReason(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String dbClientCancellationNote(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_cancellation_note FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    private String appointmentCancellationReason(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    private String appointmentClientCancellationNote(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_cancellation_note FROM appointments WHERE id = ?", String.class, appointmentId);
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
