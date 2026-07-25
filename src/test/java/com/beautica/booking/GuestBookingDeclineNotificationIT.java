package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 25.7 — a declined GUEST (LINK, {@code client_id IS NULL}) booking was previously told
 * NOTHING (the null-client guard in {@code NotificationService.notifyBookingStatusChanged}
 * blanket-skipped every status). This proves the new DECLINED-only SMS branch end-to-end through
 * the real outbox drain, complementing the mock-level coverage in
 * {@code NotificationServiceTest}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Guest (LINK) booking decline notification — SMS (Phase 25.7)")
class GuestBookingDeclineNotificationIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final String GUEST_PHONE = "+380509998877";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

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
    @DisplayName("declining a guest (LINK) booking sends exactly one SMS carrying the service, "
            + "time, and the (possibly truncated) provider note")
    void should_sendExactlyOneSms_when_guestBookingDeclined() {
        Salon salon = createSalon("guest-decline-sms-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedGuestSalonBooking(salon);

        ResponseEntity<Void> resp = patchDecline(bookingId, tokenFor(salon.ownerEmail),
                "Майстер захворів і не може прийняти сьогодні");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        drainWorker.drain();

        verify(smsService, timeout(5_000).times(1)).send(eq(GUEST_PHONE), anyString());
    }

    @Test
    @DisplayName("declining an APP (registered-client) booking sends zero SMS")
    void should_sendZeroSms_when_appBookingDeclined() {
        Salon salon = createSalon("app-decline-sms-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("app-decline-sms-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = createSalonService(salon.salonId);
        UUID bookingId = insertConfirmedBooking(clientId, salon.masterId, masterServiceId, salon.salonId);

        ResponseEntity<Void> resp = patchDecline(bookingId, tokenFor(salon.ownerEmail),
                "Ваше замовлення на жаль скасовано провайдером");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        drainWorker.drain();

        verifyNoInteractions(smsService);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<Void> patchDecline(UUID bookingId, String token, String comment) {
        String body = "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\",\"comment\":\""
                + comment.replace("\"", "\\\"") + "\"}";
        return restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(token)),
                Void.class);
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

    // ── seeding fixtures (mirrors BookingProviderCancelAuthorizationIT) ──────────

    private record Salon(UUID salonId, String ownerEmail, UUID masterId) {}

    private Salon createSalon(String ownerEmail) {
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);

        String masterEmail = "guest-decline-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new Salon(salonId, ownerEmail, masterId);
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

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    /**
     * {@code starts_at} is in the FUTURE — this fixture backs a {@code /decline} test only.
     * Decline has no temporal guard, so a future fixture is just the conventional default here.
     */
    private UUID insertConfirmedBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', NOW() + interval '2 hours', NOW() + interval '3 hours', " +
                        "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);
        return bookingId;
    }

    /**
     * Seeds a CONFIRMED guest (LINK) booking directly against the salon's master: {@code
     * client_id} NULL, {@code booking_source = 'LINK'}, guest fields populated, and a non-null
     * {@code cancel_token} (V91 CHECK requires it for any ACTIVE — i.e. CONFIRMED — LINK row).
     */
    private UUID insertConfirmedGuestSalonBooking(Salon salon) {
        UUID masterServiceId = createSalonService(salon.salonId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, guest_name, guest_surname, guest_phone, cancel_token, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 'CONFIRMED', NOW() + interval '1 day', NOW() + interval '1 day 1 hour', " +
                        "500.00, 60, 0, 'LINK', 'Гість', 'Тестовий', ?, ?, NOW(), NOW())",
                bookingId, salon.masterId, masterServiceId, salon.salonId, GUEST_PHONE, UUID.randomUUID());
        return bookingId;
    }
}
