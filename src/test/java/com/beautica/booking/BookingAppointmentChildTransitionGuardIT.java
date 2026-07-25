package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BE-4 consistency guard: the four SINGLE-booking transition endpoints
 * ({@code PATCH /bookings/{id}/cancel|decline|complete|not-complete}) must REFUSE to act on a
 * booking that is one item of a multi-service visit ({@code appointment_id != null}). Transitioning
 * a single child independently would desync the {@code appointments} header from its sibling items;
 * a multi-service visit must be moved only through the {@code /appointments/{id}/...} lockstep
 * endpoints (covered by {@link AppointmentTransitionIT}).
 *
 * <p>Each guard test proves the child booking (a) is rejected with 409 by the service-layer guard —
 * the controller {@code @PreAuthorize} role/ownership gate passes first, so this is not an authz
 * rejection — and (b) is left byte-for-byte unchanged: still {@code CONFIRMED}, still bound to its
 * appointment header. The legacy tests prove a standalone booking ({@code appointment_id = null})
 * still transitions exactly as before through all four endpoints.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Single-booking transitions refuse a multi-service visit child (BE-4 guard)")
class BookingAppointmentChildTransitionGuardIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
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

    // ── the guard: a visit child cannot be transitioned via the single-booking endpoints ──────────

    @Test
    @DisplayName("PATCH /cancel on a visit CHILD returns 409 and the child stays CONFIRMED + still "
            + "bound to its appointment (client owns it, so the controller gate passes — the "
            + "service-layer guard is what refuses)")
    void should_return409AndKeepChildUnchanged_when_clientCancelsAVisitChild() throws Exception {
        Visit visit = createTwoServiceVisit("cancel");
        UUID child = firstChildOf(visit.id());

        ResponseEntity<String> resp = patch(visit.clientToken(), child, "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertGuardRejected(resp, child, visit.id());
    }

    @Test
    @DisplayName("PATCH /decline on a visit CHILD returns 409 and the child stays CONFIRMED + still "
            + "bound to its appointment")
    void should_return409AndKeepChildUnchanged_when_providerDeclinesAVisitChild() throws Exception {
        Visit visit = createTwoServiceVisit("decline");
        UUID child = firstChildOf(visit.id());

        ResponseEntity<String> resp = patch(visit.masterToken(), child, "decline",
                "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}");

        assertGuardRejected(resp, child, visit.id());
    }

    @Test
    @DisplayName("PATCH /complete on a visit CHILD returns 409 and the child stays CONFIRMED + still "
            + "bound to its appointment")
    void should_return409AndKeepChildUnchanged_when_providerCompletesAVisitChild() throws Exception {
        Visit visit = createTwoServiceVisit("complete");
        UUID child = firstChildOf(visit.id());

        ResponseEntity<String> resp = patch(visit.masterToken(), child, "complete", null);

        assertGuardRejected(resp, child, visit.id());
    }

    @Test
    @DisplayName("PATCH /not-complete on a visit CHILD returns 409 and the child stays CONFIRMED + "
            + "still bound to its appointment")
    void should_return409AndKeepChildUnchanged_when_providerMarksVisitChildNotComplete() throws Exception {
        Visit visit = createTwoServiceVisit("noshow");
        UUID child = firstChildOf(visit.id());

        ResponseEntity<String> resp = patch(visit.masterToken(), child, "not-complete",
                "{\"cancellationReason\":\"CLIENT_NO_SHOW\"}");

        assertGuardRejected(resp, child, visit.id());
    }

    // ── the per-child endpoint SUCCEEDS where the single-booking path is refused ───────────────────

    @Test
    @DisplayName("the NEW per-child endpoint PATCH /appointments/{id}/services/{child}/decline succeeds "
            + "(204) on the exact visit child that the single-booking PATCH /bookings/{child}/decline "
            + "refuses (409) — declining ONE service leaves the sibling CONFIRMED and the header CONFIRMED")
    void should_succeedViaPerChildEndpoint_whereSingleBookingPath409d() throws Exception {
        Visit visit = createTwoServiceVisit("per-child-vs-legacy");
        UUID child0 = firstChildOf(visit.id());
        UUID child1 = secondChildOf(visit.id());

        // The OLD single-booking path is refused for a visit child (the existing guard contract).
        ResponseEntity<String> viaBooking = patch(visit.masterToken(), child0, "decline",
                "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}");
        assertThat(viaBooking.getStatusCode())
                .as("the single-booking decline path must still refuse a visit child — body: %s", viaBooking.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(child0)).as("refused path left the child CONFIRMED").isEqualTo("CONFIRMED");

        // The NEW per-child appointment path succeeds on the very same child.
        ResponseEntity<String> viaAppointment = patchAppointmentServiceDecline(visit.masterToken(), visit.id(), child0,
                "{\"providerComment\":\"скасовуємо одну послугу\"}");
        assertThat(viaAppointment.getStatusCode())
                .as("the per-child appointment decline path must succeed on the same child — body: %s",
                        viaAppointment.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(child0)).as("the targeted child is now DECLINED").isEqualTo("DECLINED");
        assertThat(dbStatus(child1)).as("the sibling stays CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(visit.id()))
                .as("the header stays CONFIRMED while a sibling remains").isEqualTo("CONFIRMED");
        assertThat(childAppointmentId(child0))
                .as("the declined child stays bound to its appointment header").isEqualTo(visit.id());
    }

    // ── legacy standalone booking (appointment_id NULL) still transitions normally ────────────────

    @Test
    @DisplayName("PATCH /cancel on a LEGACY standalone booking (appointment_id NULL) still returns "
            + "204 and moves it to CANCELLED — the guard leaves the null path untouched")
    void should_return204_when_clientCancelsLegacyStandaloneBooking() throws Exception {
        Standalone booking = createLegacyStandaloneBooking("cancel");

        ResponseEntity<String> resp = patch(booking.clientToken(), booking.bookingId(), "cancel",
                "{\"cancellationReason\":\"CLIENT_CANCELLED\"}");

        assertThat(resp.getStatusCode())
                .as("legacy standalone cancel must still succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(booking.bookingId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("PATCH /decline on a LEGACY standalone booking still returns 204 and moves it to "
            + "DECLINED")
    void should_return204_when_providerDeclinesLegacyStandaloneBooking() throws Exception {
        Standalone booking = createLegacyStandaloneBooking("decline");

        ResponseEntity<String> resp = patch(booking.masterToken(), booking.bookingId(), "decline",
                "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}");

        assertThat(resp.getStatusCode())
                .as("legacy standalone decline must still succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(booking.bookingId())).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("PATCH /complete on a LEGACY standalone booking still returns 204 and moves it to "
            + "COMPLETED")
    void should_return204_when_providerCompletesLegacyStandaloneBooking() throws Exception {
        Standalone booking = createLegacyStandaloneBooking("complete");

        ResponseEntity<String> resp = patch(booking.masterToken(), booking.bookingId(), "complete", null);

        assertThat(resp.getStatusCode())
                .as("legacy standalone complete must still succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(booking.bookingId())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("PATCH /not-complete on a LEGACY standalone booking still returns 204 and moves it "
            + "to NOT_COMPLETED")
    void should_return204_when_providerMarksLegacyStandaloneBookingNotComplete() throws Exception {
        Standalone booking = createLegacyStandaloneBooking("noshow");

        ResponseEntity<String> resp = patch(booking.masterToken(), booking.bookingId(), "not-complete",
                "{\"cancellationReason\":\"CLIENT_NO_SHOW\"}");

        assertThat(resp.getStatusCode())
                .as("legacy standalone not-complete must still succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(booking.bookingId())).isEqualTo("NOT_COMPLETED");
    }

    // ── shared assertions ─────────────────────────────────────────────────────────────────────────

    /** Asserts the single-booking transition was refused with 409 and the child row is unchanged. */
    private void assertGuardRejected(ResponseEntity<String> resp, UUID child, UUID appointmentId) {
        assertThat(resp.getStatusCode())
                .as("a visit child must be refused with 409 (use /appointments) — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(child))
                .as("the rejected transition must not change the child's status")
                .isEqualTo("CONFIRMED");
        assertThat(childAppointmentId(child))
                .as("the rejected transition must not detach the child from its appointment header")
                .isEqualTo(appointmentId);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    /** A created two-service CONFIRMED visit plus the client and owning-master tokens to act on it. */
    private record Visit(UUID id, String clientToken, String masterToken) {}

    /** A legacy standalone CONFIRMED booking (appointment_id NULL) plus both actors' tokens. */
    private record Standalone(UUID bookingId, String clientToken, String masterToken) {}

    /**
     * Creates an INDEPENDENT_MASTER + CLIENT and posts a two-service CONFIRMED visit via
     * {@code POST /appointments}, returning its id and both actors' tokens. Mirrors
     * {@code AppointmentTransitionIT}'s local helper of the same name (kept local per the house
     * convention already applied there and in {@code AppointmentCreateIT}).
     */
    private Visit createTwoServiceVisit(String tag) throws Exception {
        String masterEmail = "child-guard-visit-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "child-guard-visit-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);
        String masterToken = fixtures.tokenFor(masterEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        String body = """
                {"masterId":"%s","masterServiceIds":["%s","%s"],"startsAt":"%s"}
                """.formatted(masterId, serviceA, serviceB, startsAt.toOffsetDateTime());
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = restTemplate.exchange(
                APPOINTMENTS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode())
                .as("visit setup must succeed — body: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(created.getBody()).path("data");
        return new Visit(UUID.fromString(data.path("id").asText()), clientToken, masterToken);
    }

    /**
     * Seeds a CONFIRMED single-service standalone booking directly (appointment_id stays NULL —
     * the legacy path), owned by a fresh CLIENT and served by a fresh INDEPENDENT_MASTER.
     *
     * <p>FUTURE by default so the client-cancel path's elapsed-booking guard (endsAt-based) passes.
     * {@code "complete"} and {@code "noshow"} are the two exceptions: {@code completeBooking}
     * requires {@code now >= startsAt} (Phase 27.1), so those two fixtures are seeded ELAPSED
     * instead — {@code notCompleteBooking} has no temporal guard, but the "noshow" fixture stays
     * elapsed anyway as the conventional no-show default. {@code declineBooking} has no temporal
     * guard either, so the "decline" fixture's FUTURE default is likewise just convention.
     */
    private Standalone createLegacyStandaloneBooking(String tag) throws Exception {
        String masterEmail = "child-guard-legacy-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "child-guard-legacy-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);

        OffsetDateTime startsAt = ("complete".equals(tag) || "noshow".equals(tag))
                ? OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
                : OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, startsAt, startsAt.plusHours(1));
        return new Standalone(bookingId, fixtures.tokenFor(clientEmail), fixtures.tokenFor(masterEmail));
    }

    private ResponseEntity<String> patch(String token, UUID bookingId, String action, String body) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/" + action, HttpMethod.PATCH, entity, String.class);
    }

    private UUID firstChildOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at LIMIT 1",
                UUID.class, appointmentId);
    }

    private UUID secondChildOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at OFFSET 1 LIMIT 1",
                UUID.class, appointmentId);
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    /** PATCH the per-service decline route {@code /appointments/{id}/services/{bookingId}/decline}. */
    private ResponseEntity<String> patchAppointmentServiceDecline(
            String token, UUID appointmentId, UUID bookingId, String body) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/decline",
                HttpMethod.PATCH, entity, String.class);
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private UUID childAppointmentId(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT appointment_id FROM bookings WHERE id = ?", UUID.class, bookingId);
    }

    /**
     * Open-ended weekly schedule with all seven ISO weekdays 08:00–20:00 so a near-future visit can
     * be booked on any day. Mirrors {@code AppointmentTransitionIT}'s local helper of the same name.
     */
    private void addWorkingHoursForEveryDay(UUID masterId) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                        + "VALUES (?, ?, DATE '2020-01-01', NULL)",
                scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, '08:00', '20:00')",
                    UUID.randomUUID(), scheduleId, day);
        }
    }
}
