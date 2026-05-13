package com.beautica.review.controller;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("Review — IDOR security regression")
class ReviewSecurityTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ReviewSecurityTest.class);
    private static final String REVIEWS_URL   = "/api/v1/reviews";
    private static final String TEST_PASSWORD = "password123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private NotificationOutboxService notificationOutboxService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /reviews — 403 when client B submits a review for client A's completed booking")
    void should_return403_when_clientBSubmitsReviewForClientABooking() throws Exception {
        UUID masterId        = createIndependentMaster("sec-im-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);

        // Client A owns the booking
        String clientAEmail  = "sec-cli-a-" + System.nanoTime() + "@beautica.test";
        createClientAndGetToken(clientAEmail);
        UUID clientAId = resolveUserIdByEmail(clientAEmail);
        UUID bookingId = createCompletedBooking(clientAId, masterId, masterServiceId);

        // Client B has no relationship to this booking
        String clientBEmail  = "sec-cli-b-" + System.nanoTime() + "@beautica.test";
        String clientBToken  = createClientAndGetToken(clientBEmail);

        log.debug("Act: client B posts review on client A's bookingId={} — must return 403", bookingId);
        ResponseEntity<String> response = postReview(clientBToken, bookingId, 5);

        assertThat(response.getStatusCode())
                .as("client B reviewing client A's booking must be rejected with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    /**
     * Inserts a user with role INDEPENDENT_MASTER and a matching masters row.
     * No salon_id — INDEPENDENT_MASTERs have no salon affiliation.
     */
    private UUID createIndependentMaster(String email) {
        String hash   = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId   = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true)",
                userId, email, hash);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    /**
     * Inserts a service_definition owned by the master's user (owner_type=INDEPENDENT_MASTER)
     * and a master_services row linking it to the master. Returns masterServiceId.
     */
    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                "(id, owner_type, owner_id, name, base_duration_minutes, base_price, " +
                "buffer_minutes_after, is_active, created_at, updated_at) " +
                "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId);

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return masterServiceId;
    }

    /**
     * Inserts a booking directly with status=COMPLETED.
     * starts_at/ends_at are set in the past so no overlap-exclusion constraint is triggered.
     * salon_id is omitted — INDEPENDENT_MASTER bookings have no salon (V18 schema, nullable).
     */
    private UUID createCompletedBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings " +
                "(id, client_id, master_id, master_service_id, status, " +
                "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, " +
                "buffer_minutes_at_booking, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED', " +
                "NOW() - interval '2 hours', NOW() - interval '1 hour', " +
                "500.00, 60, 0, NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId);
        return bookingId;
    }

    private String createClientAndGetToken(String email) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'CLIENT', true)",
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

    /**
     * Resolves the UUID for a user by their exact email address.
     * Querying by email is race-condition safe regardless of insertion order.
     */
    private UUID resolveUserIdByEmail(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private ResponseEntity<String> postReview(String token, UUID bookingId, int rating) {
        String body = """
                {"bookingId":"%s","rating":%d}
                """.formatted(bookingId, rating);
        return restTemplate.exchange(
                REVIEWS_URL, HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(token)),
                String.class);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
