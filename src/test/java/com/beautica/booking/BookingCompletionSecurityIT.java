package com.beautica.booking;

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

/**
 * T2 of Phase 18.8 — the completion authorization matrix exercised over the full HTTP stack
 * (real Spring Security {@code @PreAuthorize} + real {@link com.beautica.common.security.AuthorizationService}
 * over a seeded Testcontainers Postgres).
 *
 * <p>Track 24.x retired {@code PATCH /bookings/{id}/confirm} entirely (bookings are now
 * auto-confirmed at creation) and, per Decisions D1/D2, widened the assigned {@code SALON_ADMIN}
 * to ALL provider-authority transitions on a booking — complete, decline, and not-complete alike
 * share {@link com.beautica.common.security.AuthorizationService#hasProviderAuthorityOverBooking}
 * via {@code enforceCanCompleteBooking}/{@code enforceCanCancelBooking}. Cross-tenant actors and a
 * non-existent booking id both return an identical 403 (no 403-vs-404 existence oracle).
 *
 * <p>{@link NotificationOutboxService} is mocked so a successful completion has no dispatch side
 * effect — this suite asserts HTTP status codes only, never business side effects (those are T1/T3).
 */
@Import(TestSecurityConfig.class)
@DisplayName("Booking completion — authorization matrix over HTTP (T2)")
class BookingCompletionSecurityIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

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

    // ── positive: the three admitted actors each get 204 ─────────────────────────

    @Test
    @DisplayName("PATCH /complete — SALON_OWNER completing a CONFIRMED booking at their own salon returns 204")
    void should_return204_when_ownerCompletesConfirmedBooking() {
        Salon salon = createSalon("compl-sec-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);

        ResponseEntity<Void> resp = patchComplete(bookingId, tokenFor(salon.ownerEmail));

        assertThat(resp.getStatusCode())
                .as("salon owner completing own-salon booking must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("PATCH /complete — SALON_ADMIN assigned to the booking's salon returns 204 (Phase 18.4 relaxation)")
    void should_return204_when_assignedAdminCompletesConfirmedBooking() {
        Salon salon = createSalon("compl-sec-admin-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "compl-sec-admin-" + System.nanoTime() + "@beautica.test";
        createUser(adminEmail, "SALON_ADMIN", salon.salonId);
        UUID bookingId = insertConfirmedSalonBooking(salon);

        ResponseEntity<Void> resp = patchComplete(bookingId, tokenFor(adminEmail));

        assertThat(resp.getStatusCode())
                .as("assigned SALON_ADMIN completing a booking must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("PATCH /complete — INDEPENDENT_MASTER completing their own CONFIRMED booking returns 204")
    void should_return204_when_independentMasterCompletesOwnConfirmedBooking() {
        String masterEmail = "compl-sec-im-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        UUID clientId = createUser("compl-sec-im-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = createIndependentMasterService(masterId);
        UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, null);

        ResponseEntity<Void> resp = patchComplete(bookingId, tokenFor(masterEmail));

        assertThat(resp.getStatusCode())
                .as("independent master completing own booking must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("COMPLETED");
    }

    // ── negative: role gate ──────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /complete — SALON_MASTER (read-only role) is denied with 403")
    void should_return403_when_salonMasterAttemptsComplete() {
        Salon salon = createSalon("compl-sec-sm-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);

        ResponseEntity<Void> resp = patchComplete(bookingId, tokenFor(salon.masterEmail));

        assertThat(resp.getStatusCode())
                .as("SALON_MASTER must be denied completion with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /complete — CLIENT is denied with 403")
    void should_return403_when_clientAttemptsComplete() {
        Salon salon = createSalon("compl-sec-cli-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);
        String clientEmail = "compl-sec-cli-" + System.nanoTime() + "@beautica.test";
        createUser(clientEmail, "CLIENT", null);

        ResponseEntity<Void> resp = patchComplete(bookingId, tokenFor(clientEmail));

        assertThat(resp.getStatusCode())
                .as("CLIENT must be denied completion with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /complete — anonymous (no token) is rejected with 401")
    void should_return401_when_anonymousAttemptsComplete() {
        Salon salon = createSalon("compl-sec-anon-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(headers), Void.class);

        assertThat(resp.getStatusCode())
                .as("anonymous completion must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    // ── negative: cross-tenant + no existence oracle ─────────────────────────────

    @Test
    @DisplayName("PATCH /complete — SALON_OWNER of salon B completing a salon-A booking is denied with 403")
    void should_return403_when_ownerOfAnotherSalonCompletesForeignBooking() {
        Salon salonA = createSalon("compl-sec-xt-a-" + System.nanoTime() + "@beautica.test");
        Salon salonB = createSalon("compl-sec-xt-b-" + System.nanoTime() + "@beautica.test");
        UUID bookingA = insertConfirmedSalonBooking(salonA);

        ResponseEntity<Void> resp = patchComplete(bookingA, tokenFor(salonB.ownerEmail));

        assertThat(resp.getStatusCode())
                .as("owner of a different salon must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingA)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /complete — a non-existent booking id returns the SAME 403 as a cross-tenant booking (no 403-vs-404 existence oracle)")
    void should_return403_identically_when_bookingDoesNotExist() {
        Salon salonB = createSalon("compl-sec-oracle-b-" + System.nanoTime() + "@beautica.test");
        UUID nonExistent = UUID.randomUUID();

        ResponseEntity<Void> resp = patchComplete(nonExistent, tokenFor(salonB.ownerEmail));

        assertThat(resp.getStatusCode())
                .as("a non-existent booking must be indistinguishable from a foreign one — both 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PATCH /complete — INDEPENDENT_MASTER completing another master's booking is denied with 403")
    void should_return403_when_independentMasterCompletesAnotherMastersBooking() {
        // NB: emails MUST be lowercase — AuthService.login lowercases the address before findByEmail,
        // so an uppercase char in a directly-seeded email would 401 (mismatch) on login.
        UUID masterA = createIndependentMaster("compl-sec-im-a-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceA = createIndependentMasterService(masterA);
        UUID clientId = createUser("compl-sec-im-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingA = insertConfirmedBooking(clientId, masterA, masterServiceA, null);

        String masterBEmail = "compl-sec-im-b-" + System.nanoTime() + "@beautica.test";
        createIndependentMaster(masterBEmail);

        ResponseEntity<Void> resp = patchComplete(bookingA, tokenFor(masterBEmail));

        assertThat(resp.getStatusCode())
                .as("independent master B must not complete master A's booking — 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingA)).isEqualTo("CONFIRMED");
    }

    // ── SALON_ADMIN is admitted on decline/not-complete too (Decisions D1/D2) ─────

    @Test
    @DisplayName("PATCH /decline — SALON_ADMIN assigned to the booking's salon returns 204 (Decision D1)")
    void should_return204_when_assignedAdminDeclinesConfirmedBooking() {
        Salon salon = createSalon("compl-sec-contain-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "compl-sec-contain-admin-" + System.nanoTime() + "@beautica.test";
        createUser(adminEmail, "SALON_ADMIN", salon.salonId);
        UUID bookingId = insertConfirmedSalonBooking(salon);

        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(
                        "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\",\"comment\":\"Салон закритий сьогодні\"}",
                        bearerHeaders(tokenFor(adminEmail))),
                Void.class);

        assertThat(resp.getStatusCode())
                .as("assigned SALON_ADMIN declining a booking must return 204 (Decision D1)")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("PATCH /not-complete — SALON_ADMIN assigned to the booking's salon returns 204 (Decision D2)")
    void should_return204_when_assignedAdminMarksConfirmedBookingNotComplete() {
        Salon salon = createSalon("compl-sec-contain-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "compl-sec-contain-admin-" + System.nanoTime() + "@beautica.test";
        createUser(adminEmail, "SALON_ADMIN", salon.salonId);
        UUID bookingId = insertConfirmedSalonBooking(salon);

        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>(
                        "{\"cancellationReason\":\"CLIENT_NO_SHOW\",\"comment\":\"Клієнт не прийшов на прийом\"}",
                        bearerHeaders(tokenFor(adminEmail))),
                Void.class);

        assertThat(resp.getStatusCode())
                .as("assigned SALON_ADMIN marking not-complete must return 204 (Decision D2)")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("NOT_COMPLETED");
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<Void> patchComplete(UUID bookingId, String token) {
        return restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(token)), Void.class);
    }

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

    // ── seeding fixtures ─────────────────────────────────────────────────────────

    /** Bundle of the seeded salon graph so tests can address the owner, its salon, and its master. */
    private record Salon(UUID salonId, String ownerEmail, UUID masterId, String masterEmail) {}

    private Salon createSalon(String ownerEmail) {
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);

        String masterEmail = "compl-sec-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new Salon(salonId, ownerEmail, masterId, masterEmail);
    }

    private UUID createIndependentMaster(String email) {
        UUID userId = createUser(email, "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID createSalonService(UUID salonId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        UUID masterId = jdbcTemplate.queryForObject("SELECT id FROM masters WHERE salon_id = ? LIMIT 1", UUID.class, salonId);
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /**
     * Resolves a real, selectable {@code service_types.id} (V111 made this column NOT NULL).
     */
    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    private UUID insertConfirmedSalonBooking(Salon salon) {
        UUID clientId = createUser("compl-sec-book-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = createSalonService(salon.salonId);
        return insertConfirmedBooking(clientId, salon.masterId, masterServiceId, salon.salonId);
    }

    private UUID insertConfirmedBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
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

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }
}
