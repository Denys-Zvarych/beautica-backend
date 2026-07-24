package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.common.ApiResponse;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.sms.SmsService;
import com.beautica.review.dto.CreateReviewRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression net for the CLASS of guest-booking (LINK, {@code client_id IS NULL}) bugs found in
 * the track 24.7 audit — not just each individual instance.
 *
 * <p>Seven separate production bugs were found in this one change, all sharing the same root
 * cause: a code path unconditionally dereferenced {@code booking.getClient()} without accounting
 * for the V89 guest-booking model where {@code client_id} is legitimately {@code NULL}:
 * {@code BookingRepository.findByIdWithFullGraph}/{@code findAllByIdsWithGraph} (inner
 * {@code JOIN FETCH b.client} excluded every guest row), {@code findViewAccessById},
 * {@code BookingService.cancelBooking}/{@code rescheduleBooking}, {@code AuthorizationService
 * .enforceCanViewBooking}, {@code BookingDetailResponse.from}, {@code NotificationService
 * .notifyNewBooking}/{@code notifyClientCancelled} (+ the {@code EmailNotificationService}
 * equivalents), and {@code ReviewService.createReview}. Each got its own point-fix regression
 * test elsewhere ({@code BookingProviderCancelAuthorizationIT}, {@code BookingServiceTest},
 * {@code AuthorizationServiceTest}, {@code BookingDetailResponseTest}, {@code
 * NotificationServiceTest}, {@code GuestBookingNewBookingDrainIT}, {@code ReviewServiceTest}, {@code
 * ReviewSecurityTest}) — but none of those exercise the FULL actor-reachable lifecycle of a
 * single real guest booking end-to-end. This test drives ONE real guest booking, created through
 * the real public endpoint, through every actor-reachable surface in order: create → provider
 * view → foreign-client view (denied) → provider-action (complete) → notification drain →
 * foreign-client review attempt (denied). A future change that reintroduces an unguarded {@code
 * booking.getClient()} ANYWHERE on this path — including a surface not yet built when this test
 * was written — has a much better chance of tripping this test than of being caught by a new
 * isolated unit test nobody thought to write.
 */
@DisplayName("Guest booking (null-client) full lifecycle — class-level regression net (track 24.7 audit)")
class GuestBookingLifecycleContractIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private GuestTokenProvider guestTokenProvider;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @MockBean
    private SmsService smsService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("create -> provider view -> foreign-client view denied -> complete -> drain clean -> "
            + "foreign-client review denied, all against ONE real null-client guest booking")
    void should_surviveFullActorReachableLifecycle_when_bookingIsGuestWithNullClient() throws Exception {
        String slug = "guest-lifecycle-" + Long.toString(System.nanoTime(), 36);
        String masterEmail = "lifecycle-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createLoginableIndependentMaster(masterEmail, slug);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String masterToken = loginAndGetToken(masterEmail);

        String foreignClientEmail = "lifecycle-foreign-client-" + System.nanoTime() + "@beautica.test";
        String foreignClientToken = createClientAndGetToken(foreignClientEmail);

        // ── 1. CREATE — real guest (LINK) booking via the public, permitAll endpoint ──────────
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(15).withMinute(0).withSecond(0).withNano(0);
        String guestToken = guestTokenProvider.generate("+380509998877");
        String createBody = """
                {"serviceId":"%s","startsAt":"%s","name":"Оксана","surname":"Мельник"}
                """.formatted(masterServiceId, startsAt.toOffsetDateTime());

        ResponseEntity<String> createResp = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(createBody, guestHeaders(guestToken)), String.class);
        assertThat(createResp.getStatusCode())
                .as("guest booking creation must succeed")
                .isEqualTo(HttpStatus.CREATED);
        GuestBookingResponse created = objectMapper.readValue(createResp.getBody(), GuestBookingResponse.class);
        UUID bookingId = created.bookingId();

        // ── 2. PROVIDER VIEW — GET as the owning master must succeed and never crash on the ──
        //        null client, falling back to the OTP-verified guest identity.
        JsonNode ownerView = getBookingAsJson(bookingId, masterToken);
        assertThat(ownerView.get("clientId").isNull())
                .as("a guest booking has no registered client account")
                .isTrue();
        assertThat(ownerView.get("clientFirstName").asText())
                .as("owner must see the OTP-verified guest name, not a crash or a blank field")
                .isEqualTo("Оксана");
        assertThat(ownerView.get("clientLastName").asText()).isEqualTo("Мельник");
        assertThat(ownerView.get("status").asText()).isEqualTo("CONFIRMED");

        // ── 3. FOREIGN-CLIENT VIEW — an unrelated authenticated CLIENT must be denied cleanly ──
        //        (403), never 500 (AuthorizationService.enforceCanViewBooking null-guard).
        ResponseEntity<String> foreignView = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(foreignClientToken)), String.class);
        assertThat(foreignView.getStatusCode())
                .as("a CLIENT with no relationship to a guest booking must get 403, never 500")
                .isEqualTo(HttpStatus.FORBIDDEN);

        // ── 4. PROVIDER ACTION — the owning master completes the booking. This is the same ──
        //        loadBookingOrThrow path that 404'd for every guest booking before the
        //        LEFT JOIN FETCH fix, exercised a second time via a different action than the
        //        decline path other tests already cover.
        ResponseEntity<Void> completeResp = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(masterToken)), Void.class);
        assertThat(completeResp.getStatusCode())
                .as("the owning master completing a guest booking must return 204, not 404")
                .isEqualTo(HttpStatus.NO_CONTENT);

        // ── 5. NOTIFICATION DRAIN — the real drain worker must clear every outbox row this ──
        //        booking generated (NEW_BOOKING at create, STATUS_CHANGED at complete) to SENT,
        //        never DEAD. completeBooking() also skips enqueueing REVIEW_REQUESTED entirely
        //        for a null-client booking (BookingService.completeBooking guard) — assert no
        //        such row exists at all, not just that it drained.
        drainWorker.drain();
        List<NotificationOutboxEntry> outboxForBooking = outboxRepository.findAll();
        assertThat(outboxForBooking)
                .as("REVIEW_REQUESTED must never be enqueued for a guest booking — no account to notify")
                .noneMatch(e -> e.getEventType().name().equals("REVIEW_REQUESTED"));
        long deadRows = outboxForBooking.stream().filter(e -> e.getStatus() == OutboxStatus.DEAD).count();
        assertThat(deadRows)
                .as("zero DEAD outbox rows after draining the full guest-booking lifecycle "
                        + "(create + complete) — a DEAD row here means an unguarded getClient() "
                        + "NPE'd somewhere in the dispatch path")
                .isZero();
        assertThat(outboxForBooking)
                .as("every enqueued row must have actually drained, not be stuck PENDING")
                .allMatch(e -> e.getStatus() == OutboxStatus.SENT);

        // ── 6. FOREIGN-CLIENT REVIEW ATTEMPT — an unrelated authenticated CLIENT posting a ──
        //        review for the (now COMPLETED) guest booking must be denied cleanly (403),
        //        never 500 (ReviewService.createReview null-guard).
        String reviewBody = objectMapper.writeValueAsString(new CreateReviewRequest(bookingId, 5, null));
        ResponseEntity<String> reviewResp = restTemplate.exchange(
                "/api/v1/reviews", HttpMethod.POST,
                new HttpEntity<>(reviewBody, bearerHeaders(foreignClientToken)), String.class);
        assertThat(reviewResp.getStatusCode())
                .as("a guest booking has no owning client — a foreign CLIENT's review attempt "
                        + "must be rejected with 403, never 500")
                .isEqualTo(HttpStatus.FORBIDDEN);

        // ── 7. FINAL STATE — canReview is false: the booking is COMPLETED but has no account ──
        //        to leave a review with (BookingService.canReview / BookingDetailResponse.from).
        JsonNode finalView = getBookingAsJson(bookingId, masterToken);
        assertThat(finalView.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(finalView.get("canReview").asBoolean())
                .as("a completed guest booking can never be reviewed — no account exists")
                .isFalse();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private JsonNode getBookingAsJson(UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).get("data");
    }

    /**
     * Inserts a real, loginable INDEPENDENT_MASTER (bcrypt password hash) with a booking slug —
     * unlike {@code GuestBookingNewBookingDrainIT}'s master fixture, this one needs to
     * authenticate as itself later in the flow (provider view + complete).
     */
    private UUID createLoginableIndependentMaster(String email, String slug) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true, 'Наталія', 'Бойко')",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, ?, NOW(), NOW())",
                masterId, userId, slug);
        return masterId;
    }

    private UUID createMasterService(UUID masterId) {
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Манікюр', ?, 60, 350.00, 0, true, NOW(), NOW())",
                serviceDefId, ownerId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    private void addWorkingHoursForEveryDay(UUID masterId) {
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_hours (id, master_id, day_of_week, start_time, end_time, is_active) "
                            + "VALUES (?, ?, ?, '08:00', '20:00', true)",
                    UUID.randomUUID(), masterId, day);
        }
    }

    private String createClientAndGetToken(String email) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'CLIENT', true, true)",
                UUID.randomUUID(), email, passwordEncoder.encode(TEST_PASSWORD));
        return loginAndGetToken(email);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode())
                .as("login must succeed for %s", email)
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private HttpHeaders guestHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
