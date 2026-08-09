package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
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
 * Full-HTTP-stack smoke suite for the review path over real Postgres.
 *
 * <p><b>1 booking = 1 feedback (locked product decision).</b> The visit-level ("appointment")
 * review write path — {@code POST /appointments/{id}/review} — is RETIRED: {@code createReview}'s
 * old visit-child guard was removed (commit {@code 17e604c}), so every booking, including a child
 * of a multi-service visit, is reviewed individually via {@code POST /reviews}. The N1-N5 group
 * below is the regression net for that change: mobile routes EVERY booking creation through
 * {@code POST /appointments} (even single-service ones), so every booking carries a non-null
 * {@code appointment_id} — proving the per-booking path is reviewable regardless of how the
 * booking was created. A smoke test at the bottom pins that the retired visit-review ROUTE itself
 * is now unmapped (404), not merely unreachable through application logic.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Booking reviews — 1 booking = 1 feedback (visit-level review endpoint retired)")
class AppointmentReviewIT extends AbstractIntegrationTest {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final String REVIEWS_URL = "/api/v1/reviews";
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
    @DisplayName("reviewing an individual child booking of a visit via POST /reviews now succeeds with "
            + "201 — 1 booking = 1 feedback (locked product decision): each booking card in the "
            + "client's Past tab gets its own review, the retired multi-service-visit 409 never fires")
    void should_createAPerBookingReview_when_reviewingAChildBookingOfAVisit() throws Exception {
        Visit visit = createCompletedTwoServiceVisit("child");
        UUID childBookingId = firstItemId(visit.id());

        ResponseEntity<String> resp = postSingleBookingReview(visit.clientToken(), childBookingId, 5);

        assertThat(resp.getStatusCode())
                .as("a per-booking review of a visit child must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT booking_id FROM reviews WHERE booking_id = ?", childBookingId);
        assertThat(row.get("booking_id"))
                .as("the review is keyed to the specific child booking reviewed")
                .isEqualTo(childBookingId);
        // reviews.appointment_id was dropped (V133) — the column no longer exists at all, so there
        // is no visit-review coexistence shape left to assert against.
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  1 booking = 1 feedback (locked product decision, revoking BE-6's one-
    //  review-per-visit rule) — N1-N5 regression net for the review-outage bug:
    //  mobile routes EVERY booking creation through POST /appointments, so every
    //  booking (even a single-service one) carries a non-null appointment_id.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("N1: the reported outage scenario end-to-end — a booking created the way mobile "
            + "creates EVERY booking (POST /appointments with a 1-element masterServiceIds), once "
            + "review-eligible, is reviewable via POST /reviews — 201, never the retired 409")
    void should_return201_when_reviewingASingleServiceAppointmentBooking() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("n1-single", 1);
        completeVisit(visit);
        UUID bookingId = childIdsOf(visit.id()).get(0);

        ResponseEntity<String> resp = postSingleBookingReview(visit.clientToken(), bookingId, 5);

        assertThat(resp.getStatusCode())
                .as("a single-service mobile booking must be reviewable — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE booking_id = ?", Long.class, bookingId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("N2: a 3-service visit yields THREE independent feedbacks — reviewing all three "
            + "children returns three 201s, three distinct reviews rows keyed on distinct booking_id "
            + "(reviews.appointment_id no longer exists at all — V133)")
    void should_createThreeIndependentReviews_when_reviewingAllChildrenOfAThreeServiceVisit() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("n2-three", 3);
        completeVisit(visit);
        List<UUID> children = childIdsOf(visit.id());
        assertThat(children).hasSize(3);

        for (UUID childId : children) {
            ResponseEntity<String> resp = postSingleBookingReview(visit.clientToken(), childId, 5);
            assertThat(resp.getStatusCode())
                    .as("reviewing child %s must succeed — body: %s", childId, resp.getBody())
                    .isEqualTo(HttpStatus.CREATED);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT booking_id FROM reviews WHERE booking_id = ANY(?)",
                (Object) children.toArray(new UUID[0]));
        assertThat(rows).as("exactly one review row per child booking").hasSize(3);
        assertThat(rows.stream().map(r -> r.get("booking_id")).distinct().count())
                .as("all three reviews are keyed on distinct booking_id").isEqualTo(3L);
    }

    @Test
    @DisplayName("N3: reviewing one child of a visit leaves sibling children's canReview TRUE on both "
            + "the batched list path (GET /bookings/me?partition=PAST) and the detail path — direct "
            + "guard against visit-aware canReview reappearing")
    void should_keepSiblingsReviewable_when_oneChildOfAVisitIsReviewed() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("n3-siblings", 3);
        completeVisit(visit);
        List<UUID> children = childIdsOf(visit.id());
        UUID reviewedChild = children.get(0);
        UUID siblingB = children.get(1);
        UUID siblingC = children.get(2);

        assertThat(postSingleBookingReview(visit.clientToken(), reviewedChild, 5).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(getBookingCanReview(visit.clientToken(), reviewedChild))
                .as("the reviewed child itself must flip to false (detail path)").isFalse();
        assertThat(getBookingCanReview(visit.clientToken(), siblingB))
                .as("sibling B must stay reviewable (detail path)").isTrue();
        assertThat(getBookingCanReview(visit.clientToken(), siblingC))
                .as("sibling C must stay reviewable (detail path)").isTrue();

        Map<UUID, Boolean> listCanReview = getMyPastBookingsCanReview(visit.clientToken());
        assertThat(listCanReview.get(reviewedChild))
                .as("the reviewed child itself must be false (list path)").isFalse();
        assertThat(listCanReview.get(siblingB))
                .as("sibling B must stay reviewable (list path)").isTrue();
        assertThat(listCanReview.get(siblingC))
                .as("sibling C must stay reviewable (list path)").isTrue();
    }

    @Test
    @DisplayName("N4: rating aggregate — reviewing all 3 children of one visit with ratings {5,3,4} "
            + "recomputes the master's avg_rating to 4.0 and review_count to 3 (full exact recompute "
            + "fired after-commit by ReviewEventListener, keyed on reviews rows, not visits)")
    void should_recomputeMasterAggregateAcrossAllThreeChildReviews_when_visitHasThreeChildren() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("n4-aggregate", 3);
        completeVisit(visit);
        List<UUID> children = childIdsOf(visit.id());
        int[] ratings = {5, 3, 4};

        for (int i = 0; i < children.size(); i++) {
            ResponseEntity<String> resp = postSingleBookingReview(visit.clientToken(), children.get(i), ratings[i]);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        Map<String, Object> agg = jdbcTemplate.queryForMap(
                "SELECT avg_rating, review_count FROM masters WHERE id = ?", visit.masterId());
        assertThat(((Number) agg.get("review_count")).intValue())
                .as("review_count must reflect all three per-booking reviews").isEqualTo(3);
        assertThat(((Number) agg.get("avg_rating")).doubleValue())
                .as("avg_rating must be the mean of 5, 3, 4").isEqualTo(4.0);
    }

    @Test
    @DisplayName("N5: per-booking dedup still enforced — reviewing the SAME child booking twice "
            + "returns 201 then 409 (V40 UNIQUE(booking_id))")
    void should_return409_when_reviewingTheSameChildBookingTwice() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("n5-dedup", 3);
        completeVisit(visit);
        UUID childId = childIdsOf(visit.id()).get(0);

        assertThat(postSingleBookingReview(visit.clientToken(), childId, 5).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> second = postSingleBookingReview(visit.clientToken(), childId, 4);

        assertThat(second.getStatusCode())
                .as("a second review of the same booking must 409 — body: %s", second.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE booking_id = ?", Long.class, childId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("legacy single-booking review still works — POST /reviews on a standalone completed "
            + "booking returns 201 (byte-for-byte unchanged)")
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
                "SELECT booking_id FROM reviews WHERE booking_id = ?", bookingId);
        assertThat(row.get("booking_id")).isEqualTo(bookingId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Foreign booking — a foreign single booking still 403s (no existence
    //  oracle from the 409 path).
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
    //  Retired-endpoint smoke — the visit-level review ROUTE itself is gone.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /api/v1/appointments/{id}/review is now UNMAPPED — 404, Spring's natural "
            + "response to a route with no handler (the visit-level review endpoint was deleted, not "
            + "merely disabled)")
    void should_return404_when_postingToRetiredVisitReviewEndpoint() throws Exception {
        Visit visit = createCompletedTwoServiceVisit("retired-route");
        HttpHeaders headers = fixtures.bearerHeaders(visit.clientToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + visit.id() + "/review", HttpMethod.POST,
                new HttpEntity<>("{\"rating\":5}", headers), String.class);

        assertThat(resp.getStatusCode())
                .as("the retired visit-review route must be unmapped — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
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

    /**
     * Provider completes the whole visit (no elapse guard on complete) so it becomes reviewable —
     * overload for {@link BookingTestFixtures.VisitFixture} (the N1-N5 fixture-driven visits),
     * mirroring {@link #completeVisit(Visit)} for the local {@link Visit} record.
     */
    private void completeVisit(BookingTestFixtures.VisitFixture visit) {
        HttpHeaders providerHeaders = fixtures.bearerHeaders(visit.masterToken());
        ResponseEntity<String> completed = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + visit.id() + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(providerHeaders), String.class);
        assertThat(completed.getStatusCode())
                .as("visit completion must succeed — body: %s", completed.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** Ordered child booking ids of a visit — mirrors {@code AppointmentTransitionIT#childIdsOf}. */
    private List<UUID> childIdsOf(UUID appointmentId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at", UUID.class, appointmentId);
    }

    /** Reads {@code canReview} off {@code GET /bookings/{id}} (the detail path) as the given actor. */
    private boolean getBookingCanReview(String token, UUID bookingId) {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("the owning client must read the booking — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return readCanReview(resp.getBody());
    }

    private boolean readCanReview(String body) {
        try {
            return objectMapper.readTree(body).path("data").path("canReview").asBoolean();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Reads {@code id -> canReview} off {@code GET /bookings/me?partition=PAST} (the batched list
     * path — {@code BookingService#getMyBookings}'s per-page {@code findReviewedBookingIds}
     * canReview computation) as the given client actor. A single page (default size) is enough
     * for the small fixtures N3 drives.
     */
    private Map<UUID, Boolean> getMyPastBookingsCanReview(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/me?partition=PAST", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("the owning client must list their PAST bookings — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode rows = objectMapper.readTree(resp.getBody()).path("data").path("data");
        Map<UUID, Boolean> result = new java.util.HashMap<>();
        for (JsonNode row : rows) {
            result.put(UUID.fromString(row.path("id").asText()), row.path("canReview").asBoolean());
        }
        return result;
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
