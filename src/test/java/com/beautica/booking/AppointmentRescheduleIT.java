package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Full-HTTP-stack suite for {@code PATCH /appointments/{id}/reschedule} (track 27.x) — the
 * visit-level analogue of {@code PATCH /bookings/{id}/reschedule} (Phase 27.2): moves EVERY
 * chained item of a multi-service visit lockstep to a new contiguous back-to-back block, preserving
 * item order and each item's frozen duration/buffer/price snapshot.
 *
 * <p><b>Visits are seeded directly via SQL</b> (header + N chained CONFIRMED items), the established
 * house pattern for this family (see {@link AppointmentTransitionMatrixIT}): deterministic, no
 * working-hours/slot plumbing needed for the ORIGINAL state, and lets a past ("elapsed") visit exist
 * at all — the create path rejects past starts. Working hours are still seeded for every master
 * (08:00–20:00 daily) because the endpoint under test unconditionally re-validates the NEW target
 * time against the master's schedule ({@code assertVisitStartsOnAvailableSlot}).
 * {@code NotificationOutboxService} is NOT mocked, so {@code BOOKING_RESCHEDULED} rows are real and
 * their {@code initiatedBy} payload is asserted directly.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /appointments/{id}/reschedule — visit reschedule")
class AppointmentRescheduleIT extends AbstractIntegrationTest {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final String BOOKINGS_URL = "/api/v1/bookings";

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

    @Test
    @DisplayName("provider reschedules own visit — 200, ALL items move lockstep to a new contiguous "
            + "block preserving order + frozen 60-min durations")
    void should_moveAllItemsToNewContiguousBlock_when_providerReschedulesOwnVisit() throws Exception {
        Visit v = seedIndependentVisit("provider-ok", 3, futureStart());

        OffsetDateTime newStart = futureStart().plusDays(1).withHour(14);
        ResponseEntity<String> resp = reschedule(v.masterToken(), v.id(), newStart);

        assertThat(resp.getStatusCode())
                .as("provider reschedule of their own visit must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        assertVisitStatus(v.id(), "CONFIRMED");
        List<ItemWindow> windows = itemWindows(v.id());
        assertThat(windows).hasSize(3);
        assertThat(windows.get(0).startsAt()).isEqualTo(newStart);
        assertThat(windows.get(0).endsAt()).isEqualTo(newStart.plusHours(1));
        assertThat(windows.get(1).startsAt()).isEqualTo(newStart.plusHours(1));
        assertThat(windows.get(1).endsAt()).isEqualTo(newStart.plusHours(2));
        assertThat(windows.get(2).startsAt()).isEqualTo(newStart.plusHours(2));
        assertThat(windows.get(2).endsAt()).isEqualTo(newStart.plusHours(3));
    }

    @Test
    @DisplayName("client reschedules own visit — 200, all items move to the new time")
    void should_succeed_when_clientReschedulesOwnVisit() throws Exception {
        Visit v = seedIndependentVisit("client-ok", 2, futureStart());

        OffsetDateTime newStart = futureStart().plusDays(1).withHour(9);
        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), newStart);

        assertThat(resp.getStatusCode())
                .as("client reschedule of their own visit must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        List<ItemWindow> windows = itemWindows(v.id());
        assertThat(windows.get(0).startsAt()).isEqualTo(newStart);
        assertThat(windows.get(1).startsAt()).isEqualTo(newStart.plusHours(1));
    }

    @Test
    @DisplayName("a foreign provider (unrelated independent master) rescheduling someone else's visit "
            + "is denied with 403 and nothing moves")
    void should_return403AndMoveNothing_when_foreignProviderReschedules() throws Exception {
        Visit v = seedIndependentVisit("foreign", 2, futureStart());
        String foreignEmail = "appt-resched-foreign-" + System.nanoTime() + "@beautica.test";
        fixtures.createIndependentMaster(foreignEmail);
        String foreignToken = fixtures.tokenFor(foreignEmail);

        ResponseEntity<String> resp = reschedule(foreignToken, v.id(), futureStart().plusDays(1));

        assertThat(resp.getStatusCode())
                .as("a provider with no authority over the visit's master must be forbidden")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertOriginalWindowUnchanged(v.id(), futureStart(), 2);
    }

    // ── anti-oracle pin: provider reschedule of a NONEXISTENT visit denies UNIFORMLY (403, not 404) ──
    // Final audit pass: resolveVisitForProviderReschedule loaded via loadVisitOrThrow → 404 for a missing
    // appointment id BEFORE enforceCanRescheduleBooking — an existence oracle a valid provider could
    // probe. It now collapses a missing id to the SAME uniform 403 a foreign visit yields (above),
    // matching the single-service provider reschedule. Do NOT weaken this to expect 404.
    @Test
    @DisplayName("PATCH /appointments/{id}/reschedule — a valid provider hitting a NONEXISTENT appointmentId "
            + "must be denied with 403 (uniform with the foreign-visit denials), NOT 404 — anti existence-oracle pin")
    void should_return403_not404_when_providerReschedulesNonexistentVisit() throws Exception {
        // A fully-valid INDEPENDENT_MASTER — the role gate passes and the actor routes to the provider
        // reschedule path, so only the service-layer existence/ownership handling decides the status.
        String providerEmail = "appt-resched-oracle-master-" + System.nanoTime() + "@beautica.test";
        fixtures.createIndependentMaster(providerEmail);
        String providerToken = fixtures.tokenFor(providerEmail);
        UUID nonexistentAppointmentId = UUID.randomUUID();

        ResponseEntity<String> resp = reschedule(providerToken, nonexistentAppointmentId, futureStart().plusDays(1));

        assertThat(resp.getStatusCode())
                .as("a nonexistent appointment id must be indistinguishable from a foreign one — both 403, "
                        + "never a 404 that confirms the id does not exist (existence oracle)")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a read-only SALON_MASTER attempting to reschedule a visit is denied with 403 "
            + "(role-only gate — SALON_MASTER is excluded from the provider role list)")
    void should_return403_when_salonMasterAttemptsReschedule() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("appt-resched-salon-owner-" + System.nanoTime() + "@beautica.test");
        String clientEmail = "appt-resched-salon-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceB = fixtures.createSalonService(salon.salonId(), salon.masterId());
        addWorkingHoursForEveryDay(salon.masterId());
        OffsetDateTime firstStart = futureStart();
        UUID appointmentId = seedVisitRows(clientId, salon.masterId(), salon.salonId(),
                new UUID[]{serviceA, serviceB}, firstStart);
        String salonMasterToken = fixtures.tokenFor(salon.masterEmail());

        ResponseEntity<String> resp = reschedule(salonMasterToken, appointmentId, futureStart().plusDays(1));

        assertThat(resp.getStatusCode())
                .as("SALON_MASTER (read-only calendar access) must never reschedule a visit")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertVisitStatus(appointmentId, "CONFIRMED");
        assertOriginalWindowUnchanged(appointmentId, firstStart, 2);
    }

    @Test
    @DisplayName("a foreign client (not the visit's owner) rescheduling someone else's visit is "
            + "denied with 403 and nothing moves (IDOR — resolveVisitForClientReschedule's "
            + "ownership filter)")
    void should_return403AndMoveNothing_when_foreignClientReschedules() throws Exception {
        Visit v = seedIndependentVisit("foreign-client", 2, futureStart());
        String foreignClientEmail = "appt-resched-foreign-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(foreignClientEmail, "CLIENT", null);
        String foreignClientToken = fixtures.tokenFor(foreignClientEmail);

        ResponseEntity<String> resp = reschedule(foreignClientToken, v.id(), futureStart().plusDays(1));

        assertThat(resp.getStatusCode())
                .as("a client with no ownership over the visit must be forbidden — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertOriginalWindowUnchanged(v.id(), futureStart(), 2);
    }

    @Test
    @DisplayName("client reschedule of an ELAPSED visit — 409, nothing moves")
    void should_return409_when_clientReschedulesElapsedVisit() throws Exception {
        // Past 2-item visit: items run [-3h,-2h) and [-2h,-1h) so the LAST item's endsAt (-1h) is elapsed.
        OffsetDateTime originalFirstStart = pastStart();
        Visit v = seedIndependentVisit("elapsed", 2, originalFirstStart);

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), futureStart());

        assertThat(resp.getStatusCode())
                .as("rescheduling an already-elapsed visit must be rejected with 409 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertVisitStatus(v.id(), "CONFIRMED");
        assertOriginalWindowUnchanged(v.id(), originalFirstStart, 2);
    }

    @Test
    @DisplayName("provider reschedule of a visit that has ALREADY STARTED (first item's startsAt "
            + "elapsed, but its LAST item has not ended yet) is denied with 409, nothing moves — "
            + "the provider-side temporal guard (assertCurrentNotElapsedForReschedule) is START-based, "
            + "distinct from the client path's END-based assertVisitNotElapsedForClient")
    void should_return409_when_providerReschedulesVisitThatHasAlreadyStarted() throws Exception {
        // 2-item visit starting 1h ago: item0 runs [-1h,0) (already under way), item1 runs [0,+1h)
        // (not yet ended) — the visit's END has NOT passed, so the CLIENT elapsed guard would NOT
        // fire here; only the provider's start-based guard should reject this reschedule.
        OffsetDateTime originalFirstStart = inProgressStart();
        Visit v = seedIndependentVisit("provider-in-progress", 2, originalFirstStart);

        ResponseEntity<String> resp = reschedule(v.masterToken(), v.id(), futureStart());

        assertThat(resp.getStatusCode())
                .as("a provider must not reschedule a visit whose current start has already elapsed "
                        + "— body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertVisitStatus(v.id(), "CONFIRMED");
        assertOriginalWindowUnchanged(v.id(), originalFirstStart, 2);
    }

    @Test
    @DisplayName("409 when rescheduling a visit that is no longer CONFIRMED (already DECLINED) — "
            + "nothing moves (status guard in resolveVisitForClientReschedule)")
    void should_return409_when_reschedulingAlreadyDeclinedVisit() throws Exception {
        Visit v = seedIndependentVisit("already-declined", 2, futureStart());
        jdbcTemplate.update("UPDATE appointments SET status = 'DECLINED' WHERE id = ?", v.id());

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), futureStart().plusDays(1));

        assertThat(resp.getStatusCode())
                .as("a non-CONFIRMED visit must never be reschedulable — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertVisitStatus(v.id(), "DECLINED");
        assertOriginalWindowUnchanged(v.id(), futureStart(), 2);
    }

    @Test
    @DisplayName("400 when the new start is inside the 15-minute lead-time floor")
    void should_return400_when_newStartIsTooSoon() throws Exception {
        Visit v = seedIndependentVisit("too-soon", 2, futureStart());

        OffsetDateTime tooSoon = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), tooSoon);

        assertThat(resp.getStatusCode())
                .as("a new time inside the 15-min lead-time floor must be rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertOriginalWindowUnchanged(v.id(), futureStart(), 2);
    }

    @Test
    @DisplayName("400 when the new start is beyond the 180-day max window")
    void should_return400_when_newStartIsTooFarAhead() throws Exception {
        Visit v = seedIndependentVisit("too-far", 2, futureStart());

        OffsetDateTime tooFar = OffsetDateTime.now(ZoneOffset.UTC).plusDays(200);
        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), tooFar);

        assertThat(resp.getStatusCode())
                .as("a new time beyond the 180-day cap must be rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertOriginalWindowUnchanged(v.id(), futureStart(), 2);
    }

    @Test
    @DisplayName("409 and NOTHING moves when the new block conflicts with another CONFIRMED booking "
            + "on the same master (atomicity)")
    void should_return409AndMoveNothing_when_newTimeConflictsWithAnotherBooking() throws Exception {
        Visit v = seedIndependentVisit("conflict", 2, futureStart());
        OffsetDateTime newStart = futureStart().plusDays(1).withHour(13);
        // A standalone (non-appointment) CONFIRMED booking for the SAME master, overlapping the
        // requested new block [newStart, newStart+2h) — appointment_id is NULL, so it is NOT
        // excluded by existsOverlapExcludingAppointment.
        seedStandaloneConflictingBooking(v.masterId(), newStart.plusMinutes(30));

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), newStart);

        assertThat(resp.getStatusCode())
                .as("a new block conflicting with another booking must be rejected with 409 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertOriginalWindowUnchanged(v.id(), futureStart(), 2);
    }

    @Test
    @DisplayName("provider-initiated reschedule enqueues a CLIENT-addressed BOOKING_RESCHEDULED row")
    void should_enqueueClientAddressedOutboxRow_when_providerInitiatesReschedule() throws Exception {
        Visit v = seedIndependentVisit("outbox-provider", 2, futureStart());

        ResponseEntity<String> resp = reschedule(v.masterToken(), v.id(), futureStart().plusDays(1));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rescheduledOutboxInitiatedBy(v.id()))
                .as("a provider-initiated reschedule must address the notification to the CLIENT")
                .isEqualTo("PROVIDER");
    }

    @Test
    @DisplayName("client-initiated reschedule enqueues a PROVIDER-addressed BOOKING_RESCHEDULED row")
    void should_enqueueProviderAddressedOutboxRow_when_clientInitiatesReschedule() throws Exception {
        Visit v = seedIndependentVisit("outbox-client", 2, futureStart());

        ResponseEntity<String> resp = reschedule(v.clientToken(), v.id(), futureStart().plusDays(1));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rescheduledOutboxInitiatedBy(v.id()))
                .as("a client-initiated reschedule must address the notification to the PROVIDER")
                .isEqualTo("CLIENT");
    }

    @Test
    @DisplayName("a single-service (legacy, non-appointment) booking is unaffected — this endpoint is "
            + "appointments-only, so its own id is never a valid appointmentId")
    void should_leaveLegacyBookingUnaffected_when_pathIdIsABookingNotAnAppointment() throws Exception {
        UUID masterId = fixtures.createIndependentMaster("appt-resched-legacy-master-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientEmail = "appt-resched-legacy-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        OffsetDateTime startsAt = futureStart();
        UUID bookingId = createLegacyBooking(clientToken, masterId, masterServiceId, startsAt);

        ResponseEntity<String> resp = reschedule(clientToken, bookingId, futureStart().plusDays(1));

        assertThat(resp.getStatusCode())
                .as("a legacy booking id is never a valid appointmentId — uniform 403, no existence oracle")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .as("the legacy booking must be completely untouched by this appointments-only endpoint")
                .isEqualTo("CONFIRMED");
        OffsetDateTime dbStartsAt = jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, bookingId);
        assertThat(dbStartsAt.toInstant()).isEqualTo(startsAt.toInstant());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A seeded independent-master CONFIRMED visit plus the owning client/master tokens and master id. */
    private record Visit(UUID id, UUID masterId, String clientToken, String masterToken) {}

    private record ItemWindow(OffsetDateTime startsAt, OffsetDateTime endsAt) {}

    private Visit seedIndependentVisit(String tag, int itemCount, OffsetDateTime firstStart) throws Exception {
        String masterEmail = "appt-resched-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-resched-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);

        UUID[] serviceIds = new UUID[itemCount];
        for (int i = 0; i < itemCount; i++) {
            serviceIds[i] = fixtures.createIndependentMasterService(masterId);
        }
        addWorkingHoursForEveryDay(masterId);
        UUID appointmentId = seedVisitRows(clientId, masterId, null, serviceIds, firstStart);
        return new Visit(appointmentId, masterId, fixtures.tokenFor(clientEmail), fixtures.tokenFor(masterEmail));
    }

    /**
     * Inserts the {@code appointments} header (APP, CONFIRMED) and one CONFIRMED {@code bookings} row
     * per service, chained back-to-back in one-hour blocks from {@code firstStart} — mirrors
     * {@code AppointmentTransitionMatrixIT#seedVisitRows}.
     */
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

    /** A standalone (non-appointment) CONFIRMED booking for {@code masterId}, one hour long. */
    private void seedStandaloneConflictingBooking(UUID masterId, OffsetDateTime startsAt) {
        UUID clientId = fixtures.createUser(
                "appt-resched-conflict-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, masterServiceId, startsAt, startsAt.plusHours(1));
    }

    private UUID createLegacyBooking(String clientToken, UUID masterId, UUID masterServiceId,
            OffsetDateTime startsAt) throws Exception {
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt.atZoneSameInstant(java.time.ZoneOffset.UTC), null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(resp.getStatusCode()).as("legacy booking setup must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<BookingResponse>>() {});
        return body.data().id();
    }

    private ResponseEntity<String> reschedule(String token, UUID appointmentId, OffsetDateTime newStartsAt) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        String body = "{\"newStartsAt\":\"" + newStartsAt + "\"}";
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, headers), String.class);
    }

    private void assertVisitStatus(UUID appointmentId, String expected) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
        assertThat(status).as("appointment header status").isEqualTo(expected);
    }

    private List<ItemWindow> itemWindows(UUID appointmentId) {
        return jdbcTemplate.query(
                "SELECT starts_at, ends_at FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                (rs, rowNum) -> new ItemWindow(
                        rs.getObject("starts_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class)),
                appointmentId);
    }

    private void assertOriginalWindowUnchanged(UUID appointmentId, OffsetDateTime originalFirstStart, int itemCount) {
        List<ItemWindow> windows = itemWindows(appointmentId);
        assertThat(windows).as("a rejected reschedule must move nothing").hasSize(itemCount);
        assertThat(windows.get(0).startsAt().toInstant())
                .as("the FIRST item must stay at its original start after a rejected reschedule")
                .isEqualTo(originalFirstStart.toInstant());
    }

    private String rescheduledOutboxInitiatedBy(UUID appointmentId) throws Exception {
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM notification_outbox WHERE event_type = 'BOOKING_RESCHEDULED' "
                        + "AND aggregate_id IN (SELECT id FROM bookings WHERE appointment_id = ?) "
                        + "ORDER BY created_at DESC LIMIT 1",
                String.class, appointmentId);
        JsonNode node = objectMapper.readTree(payload);
        return node.path("initiatedBy").asText();
    }

    private static OffsetDateTime futureStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    /**
     * A past anchor so a 2-item visit runs [-3h,-2h)+[-2h,-1h): the last item's endsAt (-1h) is
     * elapsed. Truncated to whole seconds (matching {@link #futureStart()}) so the value round-trips
     * exactly through {@code timestamptz}'s microsecond precision — an untruncated
     * {@code OffsetDateTime.now()} carries nanosecond precision that Postgres rounds on write,
     * breaking an exact-instant re-fetch comparison.
     */
    private static OffsetDateTime pastStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(3).withNano(0);
    }

    /**
     * An anchor 1h in the past so a 2-item visit runs [-1h,0)+[0,+1h): the FIRST item's startsAt has
     * elapsed but the LAST item's endsAt has not — isolates the provider-side start-based elapsed
     * guard from the client-side end-based one. Truncated to whole seconds for the same
     * timestamptz round-trip reason as {@link #pastStart()}.
     */
    private static OffsetDateTime inProgressStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).withNano(0);
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
