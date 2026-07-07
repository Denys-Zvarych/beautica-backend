package com.beautica.review;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.service.BookingService;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.PushNotificationService;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.event.ReviewCreatedEvent;
import com.beautica.review.service.ReviewService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * T3 (drain → mail/push delivery) + T4 (loop closure with {@code ReviewService}) of Phase 18.8,
 * plus the audit-fix guest-path pin — the point of the whole track: a completed booking prompts the
 * CLIENT to review, and that same booking then satisfies {@code ReviewService.createReview}'s
 * COMPLETED gate.
 *
 * <p>Runs the <b>real</b> {@code NotificationService} + drain worker so the {@code REVIEW_REQUESTED}
 * outbox row is dispatched for real; the two <b>transports</b> are mocked — {@code emailNotificationService}
 * (inherited {@code @MockBean}, whose review-request body render is already unit-pinned by
 * {@code EmailNotificationServiceTest} Phase 18.5) and {@link PushNotificationService}. Delivery is
 * verified at the transport seam: correct client recipient, a scheme-valid {@code /bookings/{id}/review}
 * URL, and a {@code type=REVIEW_REQUESTED} push.
 *
 * <p>The drain is invoked deterministically via a direct {@link NotificationOutboxDrainWorker#drain()}
 * call — never a wall-clock wait on the 5s scheduler.
 */
@Import(TestSecurityConfig.class)
@RecordApplicationEvents
@DisplayName("Review loop — drain delivery + ReviewService closure (T3, T4)")
class ReviewLoopIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    // Mirrors app.frontend.base-url in application-test.yml — the review deep-link origin.
    private static final String FRONTEND_BASE = "http://localhost:3000";

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ApplicationEvents applicationEvents;

    @MockBean
    private PushNotificationService pushService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ── T3: drain dispatches the review prompt to the client (email + push) ──────

    @Test
    @DisplayName("draining a completed booking sends the client a review-request email with the /bookings/{id}/review URL and a REVIEW_REQUESTED push; the entry ends SENT")
    @SuppressWarnings("unchecked")
    void should_dispatchReviewRequestEmailAndPushToClient_when_outboxDrained() {
        UUID masterId = createIndependentMaster("loop-t3-master-" + System.nanoTime() + "@beautica.test");
        UUID masterUserId = masterUserId(masterId);
        UUID masterServiceId = createIndependentMasterService(masterId);
        String clientEmail = "loop-t3-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createClient(clientEmail);
        UUID bookingId = insertConfirmedAppBooking(clientId, masterId, masterServiceId);

        bookingService.completeBooking(masterUserId, bookingId);
        UUID reviewEntryId = reviewRequestedEntryId(bookingId);

        drainWorker.drain();

        assertThat(outboxStatus(reviewEntryId))
                .as("the REVIEW_REQUESTED entry must end SENT after a successful drain")
                .isEqualTo("SENT");

        ArgumentCaptor<String> reviewUrl = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationService, times(1))
                .sendReviewRequestEmail(eq(clientEmail), any(Booking.class), reviewUrl.capture());
        assertThat(reviewUrl.getValue())
                .as("review email must carry the booking-scoped review deep link")
                .isEqualTo(FRONTEND_BASE + "/bookings/" + bookingId + "/review");

        ArgumentCaptor<Map<String, String>> pushData = ArgumentCaptor.forClass(Map.class);
        verify(pushService, times(1))
                .sendToUser(eq(clientId), anyString(), anyString(), pushData.capture());
        assertThat(pushData.getValue())
                .as("push payload must target the client with a REVIEW_REQUESTED type + bookingId")
                .containsEntry("type", "REVIEW_REQUESTED")
                .containsEntry("bookingId", bookingId.toString());
    }

    @Test
    @DisplayName("a review-request whose email transport keeps failing retries (PENDING) up to MAX_ATTEMPTS then goes DEAD; the drain loop never throws")
    void should_retryThenMarkDead_when_reviewEmailDispatchKeepsFailing() {
        UUID masterId = createIndependentMaster("loop-dead-master-" + System.nanoTime() + "@beautica.test");
        UUID masterUserId = masterUserId(masterId);
        UUID masterServiceId = createIndependentMasterService(masterId);
        UUID clientId = createClient("loop-dead-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedAppBooking(clientId, masterId, masterServiceId);

        // Stub the failure BEFORE the review row exists so no drain (background or manual) can ever
        // dispatch it successfully — every attempt throws, driving the row deterministically to DEAD.
        doThrow(new RuntimeException("SMTP transport unavailable"))
                .when(emailNotificationService).sendReviewRequestEmail(anyString(), any(Booking.class), anyString());

        bookingService.completeBooking(masterUserId, bookingId);
        UUID reviewEntryId = reviewRequestedEntryId(bookingId);

        // Three drains guarantee DEAD from a fresh row; extra background drains only no-op once DEAD.
        for (int i = 0; i < 3; i++) {
            drainWorker.drain(); // must never throw despite the dispatch failure
        }

        assertThat(outboxStatus(reviewEntryId))
                .as("a persistently failing review dispatch must land in DEAD after MAX_ATTEMPTS")
                .isEqualTo("DEAD");
        assertThat(outboxAttempts(reviewEntryId))
                .as("attempts must cap at MAX_ATTEMPTS (3)")
                .isEqualTo(3);
    }

    // ── T4: the completed booking closes the loop through ReviewService ──────────

    @Test
    @DisplayName("after completion, ReviewService.createReview succeeds (COMPLETED gate satisfied) and publishes exactly one ReviewCreatedEvent for the master")
    void should_allowReview_afterCompletion_and_publishReviewCreatedEvent() {
        UUID masterId = createIndependentMaster("loop-t4-master-" + System.nanoTime() + "@beautica.test");
        UUID masterUserId = masterUserId(masterId);
        UUID masterServiceId = createIndependentMasterService(masterId);
        UUID clientId = createClient("loop-t4-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedAppBooking(clientId, masterId, masterServiceId);

        bookingService.completeBooking(masterUserId, bookingId);

        ReviewResponse response = reviewService.createReview(clientId, new CreateReviewRequest(bookingId, 5, "Great"));

        assertThat(response).as("createReview must return the persisted review").isNotNull();
        assertThat(reviewCountForBooking(bookingId))
                .as("exactly one review row must exist for the completed booking")
                .isEqualTo(1L);
        assertThat(applicationEvents.stream(ReviewCreatedEvent.class).count())
                .as("createReview must publish exactly one ReviewCreatedEvent")
                .isEqualTo(1L);
        assertThat(applicationEvents.stream(ReviewCreatedEvent.class).findFirst().orElseThrow().masterId())
                .as("the event must carry the reviewed master id")
                .isEqualTo(masterId);
    }

    @Test
    @DisplayName("a second createReview on the same completed booking is rejected with 409 (one-review-per-booking)")
    void should_rejectSecondReview_withConflict_forSameBooking() {
        UUID masterId = createIndependentMaster("loop-dup-master-" + System.nanoTime() + "@beautica.test");
        UUID masterUserId = masterUserId(masterId);
        UUID masterServiceId = createIndependentMasterService(masterId);
        UUID clientId = createClient("loop-dup-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedAppBooking(clientId, masterId, masterServiceId);

        bookingService.completeBooking(masterUserId, bookingId);
        reviewService.createReview(clientId, new CreateReviewRequest(bookingId, 4, null));

        assertThatThrownBy(() -> reviewService.createReview(clientId, new CreateReviewRequest(bookingId, 3, null)))
                .as("second review for the same booking must be a 409 conflict")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("a booking completed by a SALON_ADMIN satisfies the review gate identically — trigger source does not change the COMPLETED gate")
    void should_allowReview_when_bookingCompletedBySalonAdmin() {
        String ownerEmail = "loop-admincompl-owner-" + System.nanoTime() + "@beautica.test";
        UUID salonId = createSalonWithOwnerAndMaster(ownerEmail);
        UUID masterId = jdbcTemplate.queryForObject("SELECT id FROM masters WHERE salon_id = ? LIMIT 1", UUID.class, salonId);
        String adminEmail = "loop-admincompl-admin-" + System.nanoTime() + "@beautica.test";
        createUser(adminEmail, "SALON_ADMIN", salonId);
        UUID masterServiceId = createSalonService(salonId, masterId);
        String clientEmail = "loop-admincompl-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createClient(clientEmail);
        UUID bookingId = insertConfirmedSalonBooking(clientId, masterId, masterServiceId, salonId);

        // Complete over HTTP as the assigned SALON_ADMIN (Phase 18.4 relaxation), then close the loop.
        ResponseEntity<Void> completeResp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(tokenFor(adminEmail))), Void.class);
        assertThat(completeResp.getStatusCode())
                .as("admin completion must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        ReviewResponse response = reviewService.createReview(clientId, new CreateReviewRequest(bookingId, 5, null));

        assertThat(response).as("review must be accepted for an admin-completed booking").isNotNull();
        assertThat(applicationEvents.stream(ReviewCreatedEvent.class).count())
                .as("the admin-completed booking must still publish a ReviewCreatedEvent")
                .isEqualTo(1L);
    }

    // ── guest path (audit-fix pin): an account-less booking never yields a review prompt ─

    /**
     * End-to-end truth for the account-less (LINK) path — and a discrepancy this regression net
     * surfaced. The completion service loads the booking via
     * {@code BookingRepository.findByIdWithFullGraph}, whose JPQL {@code JOIN FETCH b.client} is an
     * INNER join. A guest booking has {@code client_id = NULL}, so it is not returned — completion
     * 404s before the 18.3 {@code if (saved.getClient() != null)} guard is ever reached. The guard is
     * therefore unreachable via this path (see QA finding). The guarantee that matters end-to-end
     * still holds and is what this test pins: an account-less booking produces ZERO
     * {@code REVIEW_REQUESTED} rows and no client review email — no account, no prompt, no mail.
     */
    @Test
    @DisplayName("completing an account-less guest (LINK) booking never produces a REVIEW_REQUESTED row or a client review email (the null-client path yields no prompt)")
    void should_yieldNoReviewPrompt_forAccountLessGuestBooking() {
        UUID masterId = createIndependentMaster("loop-guest-master-" + System.nanoTime() + "@beautica.test");
        UUID masterUserId = masterUserId(masterId);
        UUID masterServiceId = createIndependentMasterService(masterId);
        UUID bookingId = insertConfirmedGuestBooking(masterId, masterServiceId);

        // The row really exists — this is not a trivial insert failure.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE id = ? AND client_id IS NULL AND booking_source = 'LINK'",
                Long.class, bookingId))
                .as("the guest LINK booking must be persisted with a null client")
                .isEqualTo(1L);

        // The completion full-graph fetch inner-joins the client, so a null-client booking is not
        // loadable → NotFoundException. (Documents the 18.3 guard's unreachability via this path.)
        assertThatThrownBy(() -> bookingService.completeBooking(masterUserId, bookingId))
                .as("guest booking is not loadable by the completion full-graph fetch (INNER JOIN client)")
                .isInstanceOf(NotFoundException.class);

        assertThat(countOutboxByType("REVIEW_REQUESTED", bookingId))
                .as("an account-less booking must never enqueue a review prompt")
                .isZero();

        drainWorker.drain();

        verify(emailNotificationService, never())
                .sendReviewRequestEmail(anyString(), any(Booking.class), anyString());
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    private String tokenFor(String email) {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readValue(resp.getBody(),
                    new TypeReference<ApiResponse<AuthResponse>>() {}).data().accessToken();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse login response for " + email, e);
        }
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ── seeding fixtures (local by house convention — see BookingIntegrationTest) ─

    private UUID createIndependentMaster(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID createSalonWithOwnerAndMaster(String ownerEmail) {
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);
        UUID masterUserId = createUser("loop-salonmaster-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return salonId;
    }

    private UUID createSalonService(UUID salonId, UUID masterId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'SALON', ?, 'Test Service', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID createClient(String email) {
        return createUser(email, "CLIENT", null);
    }

    private UUID insertConfirmedAppBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        return insertConfirmedSalonBooking(clientId, masterId, masterServiceId, null);
    }

    private UUID insertConfirmedSalonBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', NOW() - interval '2 hours', NOW() - interval '1 hour', " +
                        "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);
        return bookingId;
    }

    private UUID insertConfirmedGuestBooking(UUID masterId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, guest_name, guest_phone, cancel_token, created_at, updated_at) " +
                        "VALUES (?, NULL, ?, ?, NULL, 'CONFIRMED', NOW() - interval '2 hours', NOW() - interval '1 hour', " +
                        "500.00, 60, 0, 'LINK', 'Guest', '+380501234567', ?, NOW(), NOW())",
                bookingId, masterId, masterServiceId, UUID.randomUUID());
        return bookingId;
    }

    private UUID masterUserId(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private UUID reviewRequestedEntryId(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM notification_outbox WHERE event_type = 'REVIEW_REQUESTED' AND aggregate_id = ?",
                UUID.class, bookingId);
    }

    private String outboxStatus(UUID entryId) {
        return jdbcTemplate.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String.class, entryId);
    }

    private int outboxAttempts(UUID entryId) {
        return jdbcTemplate.queryForObject("SELECT attempts FROM notification_outbox WHERE id = ?", Integer.class, entryId);
    }

    private long countOutboxByType(String eventType, UUID aggregateId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = ? AND aggregate_id = ?",
                Long.class, eventType, aggregateId);
    }

    private long reviewCountForBooking(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reviews WHERE booking_id = ?", Long.class, bookingId);
    }
}
