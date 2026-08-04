package com.beautica.review;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 27.5 (REVERSES the previously deferred/out-of-scope status of master&rarr;client reviews)
 * — full-stack matrix for {@code POST /api/v1/client-reviews}: a provider rating the CLIENT of a
 * completed booking. Mirrors the shape of the existing {@code reviews} integration coverage
 * ({@code ReviewIntegrationTest}/{@code ReviewSecurityTest}), swapping the authority direction.
 */
@Import(TestSecurityConfig.class)
@DisplayName("POST /client-reviews — provider reviews the client (Phase 27.5)")
class ClientReviewIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/client-reviews";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("201 when the owning SALON_OWNER reviews the client of a COMPLETED booking, and the "
            + "client's users.avg_rating/review_count are recalculated after commit")
    void should_return201_and_recalculateRating_when_ownerReviewsClientOfCompletedBooking() throws Exception {
        Salon salon = createSalon("clirev-happy-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-happy-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":5,\"comment\":\"Чудовий клієнт\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.ownerEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("owner reviewing the client of their own completed booking must return 201 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(data.path("bookingId").asText()).isEqualTo(bookingId.toString());
        assertThat(data.path("clientId").asText()).isEqualTo(clientId.toString());
        assertThat(data.path("rating").asInt()).isEqualTo(5);
        assertThat(data.path("comment").asText()).isEqualTo("Чудовий клієнт");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isEqualTo(1);

        BigDecimal avgRating = jdbcTemplate.queryForObject(
                "SELECT avg_rating FROM users WHERE id = ?", BigDecimal.class, clientId);
        Integer reviewCount = jdbcTemplate.queryForObject(
                "SELECT review_count FROM users WHERE id = ?", Integer.class, clientId);
        assertThat(avgRating).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(reviewCount).isEqualTo(1);
    }

    @Test
    @DisplayName("400 when the booking is CONFIRMED and its endsAt has NOT yet elapsed — the "
            + "important assertion the review-eligibility widening below must preserve: a still-open "
            + "booking stays unreviewable")
    void should_return400_when_bookingConfirmedAndNotYetElapsed() throws Exception {
        Salon salon = createSalon("clirev-notcompleted-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-notcompleted-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertFutureBooking(clientId, salon.masterId, salon.salonId, "CONFIRMED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.ownerEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("reviewing the client of a still-open, non-elapsed CONFIRMED booking must be "
                        + "rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isZero();
    }

    @Test
    @DisplayName("201 when the booking is CONFIRMED but its endsAt has already ELAPSED — the bug "
            + "fix: a booking the provider never closed but that aged into Past by time must be "
            + "reviewable, mirroring the client-side ReviewService widening")
    void should_return201_when_bookingConfirmedButElapsed() throws Exception {
        Salon salon = createSalon("clirev-elapsed-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-elapsed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "CONFIRMED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.ownerEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("an elapsed-but-unclosed CONFIRMED booking must be reviewable — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("400 when the booking is NOT_COMPLETED (no-show), even with an elapsed endsAt — "
            + "guards against over-widening the eligibility check beyond CONFIRMED")
    void should_return400_when_bookingNotCompletedNoShow() throws Exception {
        Salon salon = createSalon("clirev-noshow-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-noshow-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "NOT_COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.ownerEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("NOT_COMPLETED is an explicit no-show marking, never reviewable regardless of endsAt")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isZero();
    }

    @Test
    @DisplayName("409 on a duplicate client review for the same booking")
    void should_return409_when_duplicateReview() throws Exception {
        Salon salon = createSalon("clirev-dup-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-dup-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");
        String token = tokenFor(salon.ownerEmail);
        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":3}";

        ResponseEntity<String> first = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(token)), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(token)), String.class);

        assertThat(second.getStatusCode())
                .as("a second review for the same booking must be rejected with 409")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("400 when the booking is a guest (LINK, null-client) booking — no account to review")
    void should_return400_when_bookingIsGuestWithNoClient() throws Exception {
        Salon salon = createSalon("clirev-guest-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertGuestBooking(salon.masterId, salon.salonId);

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.ownerEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("reviewing a guest (null-client) booking must be rejected — no account exists to review")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("403 when a FOREIGN provider (not this booking's provider) attempts to review the client")
    void should_return403_when_foreignProviderAttemptsReview() throws Exception {
        Salon salonA = createSalon("clirev-foreign-a-" + System.nanoTime() + "@beautica.test");
        Salon salonB = createSalon("clirev-foreign-b-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-foreign-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salonA.masterId, salonA.salonId, "COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":2}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salonB.ownerEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("a provider with no authority over this booking must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isZero();
    }

    @Test
    @DisplayName("403 when a CLIENT attempts to POST /client-reviews (provider-only endpoint)")
    void should_return403_when_clientAttemptsToReviewAClient() throws Exception {
        Salon salon = createSalon("clirev-clientrole-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-clientrole-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");
        String otherClientEmail = "clirev-clientrole-other-" + System.nanoTime() + "@beautica.test";
        createUser(otherClientEmail, "CLIENT", null);

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(otherClientEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("CLIENT role must be denied POST /client-reviews with 403 — this is a provider-only endpoint")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("403 when a SALON_MASTER (read-only role, never admitted to provider actions) "
            + "attempts to review the client of their own salon's booking")
    void should_return403_when_salonMasterAttemptsToReviewClient() throws Exception {
        Salon salon = createSalon("clirev-sm-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-sm-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.masterEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("SALON_MASTER must be denied POST /client-reviews with 403 — the @PreAuthorize "
                        + "role fast path (hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER')) "
                        + "never admits it, same as decline/complete/reschedule")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", Integer.class, bookingId))
                .isZero();
    }

    @Test
    @DisplayName("400 when rating is out of range (0)")
    void should_return400_when_ratingOutOfRange() throws Exception {
        Salon salon = createSalon("clirev-badrating-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("clirev-badrating-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId, salon.salonId, "COMPLETED");

        String body = "{\"bookingId\":\"" + bookingId + "\",\"rating\":0}";
        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(tokenFor(salon.ownerEmail))), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    private String tokenFor(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ── seeding fixtures (local by house convention — mirrors BookingCompletionSecurityIT) ───────

    private record Salon(UUID salonId, String ownerEmail, UUID masterId, String masterEmail) {}

    private Salon createSalon(String ownerEmail) {
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);

        String masterEmail = "clirev-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new Salon(salonId, ownerEmail, masterId, masterEmail);
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    private UUID createSalonService(UUID salonId, UUID masterId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID insertBooking(UUID clientId, UUID masterId, UUID salonId, String status) {
        UUID masterServiceId = createSalonService(salonId, masterId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status);
        return bookingId;
    }

    /** Same shape as {@link #insertBooking}, but with a FUTURE starts_at/ends_at — for the
     * still-open, not-yet-elapsed CONFIRMED case that must stay unreviewable. */
    private UUID insertFutureBooking(UUID clientId, UUID masterId, UUID salonId, String status) {
        UUID masterServiceId = createSalonService(salonId, masterId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW() + interval '1 hour', NOW() + interval '2 hours', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status);
        return bookingId;
    }

    /** Guest (LINK) booking, COMPLETED, client_id NULL — mirrors GuestBookingLifecycleContractIT's shape. */
    private UUID insertGuestBooking(UUID masterId, UUID salonId) {
        UUID masterServiceId = createSalonService(salonId, masterId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'LINK', 'Гість', 'Тестовий', '+380509998877', NOW(), NOW())",
                bookingId, masterId, masterServiceId, salonId);
        return bookingId;
    }
}
