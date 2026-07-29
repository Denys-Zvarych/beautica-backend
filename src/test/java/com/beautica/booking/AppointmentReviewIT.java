package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Full-HTTP-stack smoke suite for BE-6 — the ONE-review-per-completed-visit contract over real
 * Postgres. Proves: a client leaves exactly one review for a completed multi-service visit (201, one
 * {@code reviews} row carrying {@code appointment_id} + {@code booking_id} = the first item); a second
 * visit review is rejected (409); a review submitted against an individual child booking of the visit
 * is rejected (409, directing to the visit endpoint); the legacy single-booking review path is
 * unaffected (201); and visit completion enqueues EXACTLY ONE {@code REVIEW_REQUESTED} prompt, never
 * one per item.
 */
@Import(TestSecurityConfig.class)
@DisplayName("BE-6 — appointment(visit)-level reviews")
class AppointmentReviewIT extends AbstractIntegrationTest {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final String REVIEWS_URL = "/api/v1/reviews";
    private static final String MASTERS_URL = "/api/v1/masters";

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
    @DisplayName("client leaves ONE review for a completed 2-service visit — 201, a single reviews "
            + "row with appointment_id set and booking_id = the first item, and completion enqueued "
            + "exactly ONE REVIEW_REQUESTED prompt (never one per item)")
    void should_createOneVisitReview_when_clientReviewsCompletedVisit() throws Exception {
        Visit visit = createCompletedTwoServiceVisit("ok");

        ResponseEntity<String> resp = postAppointmentReview(visit.clientToken(), visit.id(), 5, "Чудовий візит");

        assertThat(resp.getStatusCode())
                .as("first visit review must return 201 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT appointment_id, booking_id, rating FROM reviews WHERE appointment_id = ?",
                visit.id());
        assertThat(row.get("appointment_id")).as("review is mapped to the visit").isEqualTo(visit.id());
        assertThat(row.get("booking_id"))
                .as("booking_id points at the visit's first child booking")
                .isEqualTo(firstItemId(visit.id()));
        assertThat(((Number) row.get("rating")).intValue()).isEqualTo(5);
        assertThat(reviewRequestedCount(visit.id()))
                .as("exactly ONE REVIEW_REQUESTED per visit completion, not one per item")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a second review for the same completed visit is rejected with 409 and no second "
            + "reviews row is written")
    void should_return409_when_secondVisitReviewSubmitted() throws Exception {
        Visit visit = createCompletedTwoServiceVisit("dup");
        assertThat(postAppointmentReview(visit.clientToken(), visit.id(), 5, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = postAppointmentReview(visit.clientToken(), visit.id(), 4, null);

        assertThat(second.getStatusCode())
                .as("a duplicate visit review must return 409 — same envelope as the single-booking path")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(visitReviewCount(visit.id()))
                .as("still exactly one review for the visit")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("reviewing an individual child booking of a visit via POST /reviews is rejected with "
            + "409 — a visit collects one review, never N per-item reviews")
    void should_return409_when_reviewingAChildBookingOfAVisit() throws Exception {
        Visit visit = createCompletedTwoServiceVisit("child");
        UUID childBookingId = firstItemId(visit.id());

        ResponseEntity<String> resp = postSingleBookingReview(visit.clientToken(), childBookingId, 5);

        assertThat(resp.getStatusCode())
                .as("a per-child review of a visit item must be refused with 409 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE booking_id = ?", Long.class, childBookingId))
                .as("no review row may be written against a visit child booking")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("legacy single-booking review still works — POST /reviews on a standalone completed "
            + "booking returns 201 with appointment_id NULL (byte-for-byte unchanged)")
    void should_createLegacyReview_when_reviewingSingleBooking() throws Exception {
        String masterEmail = "appt-rev-legacy-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        String clientEmail = "appt-rev-legacy-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);
        UUID clientId = userIdOf(clientEmail);
        UUID bookingId = insertCompletedSingleBooking(clientId, masterId, masterServiceId);

        ResponseEntity<String> resp = postSingleBookingReview(clientToken, bookingId, 4);

        assertThat(resp.getStatusCode())
                .as("legacy single-booking review must still return 201 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT appointment_id, booking_id FROM reviews WHERE booking_id = ?", bookingId);
        assertThat(row.get("appointment_id"))
                .as("a legacy review carries no appointment_id")
                .isNull();
        assertThat(row.get("booking_id")).isEqualTo(bookingId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Point 1 (render + aggregate) — the visit review is visible on the master's
    //  public reviews list and recomputes the master's persisted aggregate rating.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a completed-visit review renders in the master's public reviews list (INNER-JOIN "
            + "graph path survives with booking_id = the first item) AND recomputes the master's "
            + "persisted aggregate rating / review_count")
    void should_renderInMasterListAndRecomputeAggregate_when_clientReviewsCompletedVisit() throws Exception {
        Visit visit = createCompletedTwoServiceVisit("render");

        ResponseEntity<String> resp = postAppointmentReview(visit.clientToken(), visit.id(), 5, "Топ сервіс");
        assertThat(resp.getStatusCode())
                .as("visit review must return 201 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        UUID reviewId = UUID.fromString(
                objectMapper.readTree(resp.getBody()).path("data").path("id").asText());

        // Render: the visit review appears on GET /masters/{id}/reviews (public, no auth). This is the
        // load-bearing INNER-JOIN assertion — under the rejected XOR shape a null-booking_id visit
        // review would be silently DROPPED by the JOIN FETCH r.booking hydrate query and vanish here.
        JsonNode list = objectMapper.readTree(
                restTemplate.getForEntity(MASTERS_URL + "/" + visit.masterId() + "/reviews", String.class)
                        .getBody());
        JsonNode rows = list.path("data").path("data");
        assertThat(rows).as("the master's review list must contain exactly the one visit review").hasSize(1);
        assertThat(rows.get(0).path("id").asText())
                .as("the rendered row is the review just created for the visit")
                .isEqualTo(reviewId.toString());
        assertThat(rows.get(0).path("rating").asInt()).isEqualTo(5);
        assertThat(rows.get(0).path("serviceName").asText())
                .as("serviceName hydrates from the first child booking's service definition")
                .isEqualTo("Test Service");

        // Aggregate: recomputed synchronously by ReviewEventListener (AFTER_COMMIT, not @Async).
        Map<String, Object> agg = jdbcTemplate.queryForMap(
                "SELECT avg_rating, review_count FROM masters WHERE id = ?", visit.masterId());
        assertThat(((Number) agg.get("review_count")).intValue())
                .as("the master's review_count must reflect the one visit review").isEqualTo(1);
        assertThat(((Number) agg.get("avg_rating")).doubleValue())
                .as("the master's avg_rating must equal the single 5-star visit review").isEqualTo(5.0);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Point 3 (foreign booking) — the per-item guard sits AFTER ownership, so a
    //  foreign single booking still 403s (no existence oracle from the 409 path).
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reviewing a FOREIGN single booking via POST /reviews still returns 403 (ownership is "
            + "checked before the per-item 409 guard — no existence oracle)")
    void should_return403_when_reviewingForeignSingleBooking() throws Exception {
        String masterEmail = "appt-rev-foreign-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        // Owner of the booking.
        String ownerClientEmail = "appt-rev-foreign-owner-" + System.nanoTime() + "@beautica.test";
        UUID ownerClientId = fixtures.createUser(ownerClientEmail, "CLIENT", null);
        UUID bookingId = insertCompletedSingleBooking(ownerClientId, masterId, masterServiceId);
        // A DIFFERENT client attempts the review.
        String otherClientEmail = "appt-rev-foreign-other-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(otherClientEmail, "CLIENT", null);
        String otherToken = fixtures.tokenFor(otherClientEmail);

        ResponseEntity<String> resp = postSingleBookingReview(otherToken, bookingId, 5);

        assertThat(resp.getStatusCode())
                .as("a foreign booking must 403 (ownership before per-item guard) — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE booking_id = ?", Long.class, bookingId))
                .as("no review row may be written for a foreign booking").isEqualTo(0L);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Point 4 (canReview lifecycle) — GET /appointments/{id}.canReview is false
    //  while CONFIRMED, true after COMPLETED with no review, false once reviewed.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /appointments/{id}.canReview transitions false (CONFIRMED) → true (COMPLETED, no "
            + "review yet) → false (after a review exists)")
    void should_reflectCanReviewLifecycle_when_visitProgresses() throws Exception {
        Visit visit = createConfirmedTwoServiceVisit("canreview");

        assertThat(getCanReview(visit.clientToken(), visit.id()))
                .as("a freshly-created CONFIRMED visit is not yet reviewable").isFalse();

        completeVisit(visit);
        assertThat(getCanReview(visit.clientToken(), visit.id()))
                .as("a COMPLETED visit with no review is reviewable").isTrue();

        assertThat(postAppointmentReview(visit.clientToken(), visit.id(), 5, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(getCanReview(visit.clientToken(), visit.id()))
                .as("once a review exists the visit is no longer reviewable").isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Point 5 (authZ / status) — every rejection collapses to a uniform envelope.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a client who does not own the completed visit is refused with 403, and no review row "
            + "is written")
    void should_return403_when_nonOwningClientReviewsVisit() throws Exception {
        Visit visit = createCompletedTwoServiceVisit("nonowner");
        String otherClientEmail = "appt-rev-nonowner-other-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(otherClientEmail, "CLIENT", null);
        String otherToken = fixtures.tokenFor(otherClientEmail);

        ResponseEntity<String> resp = postAppointmentReview(otherToken, visit.id(), 5, null);

        assertThat(resp.getStatusCode())
                .as("a foreign visit must 403 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(visitReviewCount(visit.id()))
                .as("no review may be written for a non-owned visit").isEqualTo(0L);
    }

    @Test
    @DisplayName("reviewing a guest (account-less) completed visit is refused with 403 (client_id NULL "
            + "collapses to the same 403 a foreign visit gets)")
    void should_return403_when_reviewingGuestVisit() throws Exception {
        UUID guestAppointmentId = insertCompletedGuestAppointment();
        String clientEmail = "appt-rev-guest-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ResponseEntity<String> resp = postAppointmentReview(clientToken, guestAppointmentId, 5, null);

        assertThat(resp.getStatusCode())
                .as("a guest visit has no owning account — must 403 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(visitReviewCount(guestAppointmentId)).isEqualTo(0L);
    }

    @Test
    @DisplayName("reviewing a still-CONFIRMED (non-completed) visit is refused with 400, and no review "
            + "row is written")
    void should_return400_when_reviewingNonCompletedVisit() throws Exception {
        Visit visit = createConfirmedTwoServiceVisit("notdone");

        ResponseEntity<String> resp = postAppointmentReview(visit.clientToken(), visit.id(), 5, null);

        assertThat(resp.getStatusCode())
                .as("a CONFIRMED visit is not reviewable — must 400 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(visitReviewCount(visit.id())).isEqualTo(0L);
    }

    @Test
    @DisplayName("reviewing a missing visit id returns 404")
    void should_return404_when_reviewingMissingVisit() throws Exception {
        String clientEmail = "appt-rev-missing-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ResponseEntity<String> resp = postAppointmentReview(clientToken, UUID.randomUUID(), 5, null);

        assertThat(resp.getStatusCode())
                .as("an unknown visit id must 404 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Point 8 (validation) — same DTO constraints as the legacy single-booking body.
    // ══════════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "rating={0}")
    @ValueSource(ints = {0, 6})
    @DisplayName("a rating outside 1..5 is rejected with 400 before any review row is written")
    void should_return400_when_ratingOutOfBounds(int rating) throws Exception {
        Visit visit = createCompletedTwoServiceVisit("rating" + rating);

        ResponseEntity<String> resp = postAppointmentReviewRaw(
                visit.clientToken(), visit.id(), "{\"rating\":" + rating + "}");

        assertThat(resp.getStatusCode())
                .as("rating %d is out of bounds — must 400 — body: %s", rating, resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(visitReviewCount(visit.id())).isEqualTo(0L);
    }

    @Test
    @DisplayName("a comment carrying a control character is rejected with 400 (same @Pattern as the "
            + "legacy body)")
    void should_return400_when_commentHasControlChar() throws Exception {
        Visit visit = createCompletedTwoServiceVisit("ctrl");

        //  is a valid JSON escape on the wire (well-formed body — this exercises the DTO's
        // @Pattern, not Jackson's parser), decoded to a BEL control char the pattern must reject.
        ResponseEntity<String> resp = postAppointmentReviewRaw(
                visit.clientToken(), visit.id(), "{\"rating\":5,\"comment\":\"bad\\u0007text\"}");

        assertThat(resp.getStatusCode())
                .as("a control-char comment must 400 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(visitReviewCount(visit.id())).isEqualTo(0L);
    }

    @Test
    @DisplayName("an oversized comment (>2000 chars) is rejected with 400")
    void should_return400_when_commentOversized() throws Exception {
        Visit visit = createCompletedTwoServiceVisit("oversize");
        String oversized = "a".repeat(2001);

        ResponseEntity<String> resp = postAppointmentReviewRaw(
                visit.clientToken(), visit.id(),
                objectMapper.writeValueAsString(Map.of("rating", 5, "comment", oversized)));

        assertThat(resp.getStatusCode())
                .as("a 2001-char comment must 400 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(visitReviewCount(visit.id())).isEqualTo(0L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A two-service visit (CONFIRMED or later) plus the tokens/ids needed to drive and inspect it. */
    private record Visit(UUID id, String clientToken, UUID masterId, String masterToken) {}

    /**
     * Creates an INDEPENDENT_MASTER + CLIENT, posts a two-service CONFIRMED visit, then completes it
     * through {@code PATCH /appointments/{id}/complete} so it is reviewable.
     */
    private Visit createCompletedTwoServiceVisit(String tag) throws Exception {
        Visit visit = createConfirmedTwoServiceVisit(tag);
        completeVisit(visit);
        return visit;
    }

    /** Creates the INDEPENDENT_MASTER + CLIENT and posts a two-service CONFIRMED visit (not completed). */
    private Visit createConfirmedTwoServiceVisit(String tag) throws Exception {
        String masterEmail = "appt-rev-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-rev-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
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
        UUID appointmentId = UUID.fromString(data.path("id").asText());

        return new Visit(appointmentId, clientToken, masterId, masterToken);
    }

    /** Provider completes the whole visit (no elapse guard on complete) so it becomes reviewable. */
    private void completeVisit(Visit visit) {
        HttpHeaders providerHeaders = fixtures.bearerHeaders(visit.masterToken());
        ResponseEntity<String> completed = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + visit.id() + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(providerHeaders), String.class);
        assertThat(completed.getStatusCode())
                .as("visit completion must succeed — body: %s", completed.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** Reads {@code canReview} off {@code GET /appointments/{id}} as the given actor. */
    private boolean getCanReview(String token, UUID appointmentId) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("the owning client must read the visit — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data").path("canReview").asBoolean();
    }

    /**
     * Inserts a standalone COMPLETED guest (LINK) visit header with {@code client_id = NULL} — enough
     * to exercise the review path's null-owner guard, which fires before any item lookup. The FK-less
     * header alone is sufficient (the guard 403s before the visit's items are read).
     */
    private UUID insertCompletedGuestAppointment() {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments "
                        + "(id, client_id, salon_id, status, booking_source, guest_name, guest_surname, "
                        + "guest_phone, cancel_token, created_at, updated_at) "
                        + "VALUES (?, NULL, NULL, 'COMPLETED', 'LINK', 'Guest', 'Visitor', '+380501234567', "
                        + "?, NOW(), NOW())",
                appointmentId, UUID.randomUUID());
        return appointmentId;
    }

    private ResponseEntity<String> postAppointmentReview(
            String token, UUID appointmentId, int rating, String comment) throws Exception {
        String body = comment == null
                ? "{\"rating\":%d}".formatted(rating)
                : "{\"rating\":%d,\"comment\":\"%s\"}".formatted(rating, comment);
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/review", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    /** POSTs a caller-supplied raw JSON body to the visit-review endpoint (for validation cases). */
    private ResponseEntity<String> postAppointmentReviewRaw(String token, UUID appointmentId, String rawBody) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/review", HttpMethod.POST,
                new HttpEntity<>(rawBody, headers), String.class);
    }

    private ResponseEntity<String> postSingleBookingReview(String token, UUID bookingId, int rating) {
        String body = "{\"bookingId\":\"%s\",\"rating\":%d}".formatted(bookingId, rating);
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                REVIEWS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private UUID firstItemId(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at LIMIT 1",
                UUID.class, appointmentId);
    }

    private Long visitReviewCount(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE appointment_id = ?", Long.class, appointmentId);
    }

    private Long reviewRequestedCount(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'REVIEW_REQUESTED' "
                        + "AND aggregate_id IN (SELECT id FROM bookings WHERE appointment_id = ?)",
                Long.class, appointmentId);
    }

    private UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    /**
     * Inserts a standalone COMPLETED booking (no appointment_id) directly — the legacy single-service
     * path. starts_at/ends_at are in the past so no overlap-exclusion constraint fires. Mirrors
     * {@code ReviewIntegrationTest#createCompletedBooking}.
     */
    private UUID insertCompletedSingleBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings "
                        + "(id, client_id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', "
                        + "NOW() - interval '1 hour', 500.00, 60, 0, NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId);
        return bookingId;
    }

    /**
     * Open-ended weekly schedule with all seven ISO weekdays 08:00–20:00 so a near-future visit can be
     * booked on any day. Mirrors {@code AppointmentTransitionIT}'s local helper of the same name.
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
