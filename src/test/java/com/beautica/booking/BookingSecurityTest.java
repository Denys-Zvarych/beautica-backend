package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
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

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("Booking — IDOR security regression")
class BookingSecurityTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BookingSecurityTest.class);
    private static final String BOOKINGS_URL = "/api/v1/bookings";
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

    @Test
    @DisplayName("GET booking — 403 when client B reads client A's booking")
    void should_return403_when_clientBReadsClientABooking() throws Exception {
        String clientAToken = createClientAndGetToken("client-a-idor-" + System.nanoTime() + "@beautica.test");
        String clientBToken = createClientAndGetToken("client-b-idor-" + System.nanoTime() + "@beautica.test");

        UUID masterId = createSalonOwnerSalonAndMaster("idor-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        UUID bookingAId = createBooking(clientAToken, masterId, masterServiceId);

        log.debug("Act: GET {}/{} with Client B token targeting Client A booking — must be denied", BOOKINGS_URL, bookingAId);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingAId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(clientBToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 403 when client B reads client A's booking, bookingId=%s", bookingAId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PATCH confirm — 403 when SALON_MASTER attempts to confirm (not a manager role)")
    void should_return403_when_salonMasterAttemptsToConfirmBooking() throws Exception {
        // Arrange: create a salon with a master; CLIENT books a slot
        String ownerEmail = "sm-confirm-owner-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("sm-confirm-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId);

        // Resolve the SALON_MASTER user that was inserted by createSalonOwnerSalonAndMaster
        String masterEmail = jdbcTemplate.queryForObject(
                """
                SELECT u.email FROM users u
                JOIN masters m ON m.user_id = u.id
                WHERE m.id = ?
                """,
                String.class, masterId);
        String salonMasterToken = loginAndGetToken(masterEmail);

        // Act: SALON_MASTER attempts to confirm — must be rejected with 403
        log.debug("Act: PATCH {}/{}/confirm as SALON_MASTER — must return 403", BOOKINGS_URL, bookingId);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/confirm", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(salonMasterToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("SALON_MASTER confirming booking must return 403, bookingId=%s", bookingId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PATCH confirm — 403 when owner B attempts to confirm a booking from salon A")
    void should_return403_when_ownerOfSalonBManagesBookingInSalonA() throws Exception {
        String ownerAEmail = "owner-a-idor-" + System.nanoTime() + "@beautica.test";
        UUID masterAId = createSalonOwnerSalonAndMaster(ownerAEmail);
        String ownerAToken = loginAndGetToken(ownerAEmail);

        UUID masterServiceId = createMasterService(masterAId);
        addWorkingHoursForEveryDay(masterAId);

        String clientToken = createClientAndGetToken("client-idor-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = createBooking(clientToken, masterAId, masterServiceId);

        String ownerBEmail = "owner-b-idor-" + System.nanoTime() + "@beautica.test";
        createSalonOwnerSalonAndMaster(ownerBEmail);
        String ownerBToken = loginAndGetToken(ownerBEmail);

        log.debug("Act: PATCH {}/{}/confirm with Owner B token targeting Owner A's booking — must be denied", BOOKINGS_URL, bookingId);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/confirm", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(ownerBToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 403 when owner B confirms booking in salon A, bookingId=%s", bookingId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String createClientAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        // email_verified = true so Phase 1.7 login gate does not return 403 EMAIL_NOT_VERIFIED
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'CLIENT', true, true)",
                UUID.randomUUID(), email, hash);
        return loginAndGetToken(email);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private UUID createSalonOwnerSalonAndMaster(String ownerEmail) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID ownerId = UUID.randomUUID();
        // email_verified = true so Phase 1.7 login gate does not return 403 EMAIL_NOT_VERIFIED
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, ownerEmail, hash);

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + ownerId);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "master-" + System.nanoTime() + "@beautica.test";
        // email_verified = true — master logs in directly in should_return403_when_salonMasterAttemptsToConfirmBooking
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)",
                masterUserId, masterEmail, hash, salonId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        return masterId;
    }

    private UUID createMasterService(UUID masterId) {
        UUID salonId = jdbcTemplate.queryForObject(
                "SELECT salon_id FROM masters WHERE id = ?", UUID.class, masterId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'SALON', ?, 'Test Service', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId);

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return masterServiceId;
    }

    private void addWorkingHoursForEveryDay(UUID masterId) {
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_hours (id, master_id, day_of_week, start_time, end_time, is_active) VALUES (?, ?, ?, '08:00', '20:00', true)",
                    UUID.randomUUID(), masterId, day);
        }
    }

    private UUID createBooking(String clientToken, UUID masterId, UUID masterServiceId) throws Exception {
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null);

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<BookingResponse>>() {});
        return body.data().id();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
