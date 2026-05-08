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
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("Booking — full-flow integration")
class BookingIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BookingIntegrationTest.class);
    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "password123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private NotificationOutboxService notificationOutboxService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM bookings");
        jdbcTemplate.execute("DELETE FROM master_services");
        jdbcTemplate.execute("DELETE FROM service_definitions");
        jdbcTemplate.execute("DELETE FROM working_hours");
        jdbcTemplate.execute("DELETE FROM schedule_exceptions");
        jdbcTemplate.execute("DELETE FROM masters");
        jdbcTemplate.execute("DELETE FROM invite_tokens");
        jdbcTemplate.execute("DELETE FROM salons");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("POST /bookings — 201 with booking ID when client submits a valid request")
    void should_createBooking_and_return201_when_clientSubmitsValidRequest() throws Exception {
        String clientToken = createClientAndGetToken("integ-client-create-" + System.nanoTime() + "@beautica.test");
        UUID masterId = createSalonOwnerSalonAndMaster("integ-owner-create-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, null);

        log.debug("Act: POST {} with valid CLIENT token — must return 201", BOOKINGS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("valid booking creation must return 201")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<BookingResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().id()).isNotNull();
        assertThat(body.data().masterId()).isEqualTo(masterId);
        assertThat(body.data().masterServiceId()).isEqualTo(masterServiceId);
    }

    @Test
    @DisplayName("POST /bookings — 401 when no Authorization header is present")
    void should_return401_when_noTokenOnCreateBooking() {
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        var request = new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), startsAt, null);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        log.debug("Act: POST {} with no token — must return 401", BOOKINGS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("missing authorization must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("POST /bookings — 403 when salon owner attempts to create a booking")
    void should_return403_when_ownerAttemptsToCreateBooking() throws Exception {
        String ownerEmail = "integ-owner-403-" + System.nanoTime() + "@beautica.test";
        createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);

        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        var request = new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), startsAt, null);

        log.debug("Act: POST {} with SALON_OWNER token — must return 403", BOOKINGS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("salon owner creating booking must return 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/confirm — 204 when salon owner confirms a pending booking")
    void should_confirmBooking_and_return204_when_salonOwnerConfirms() throws Exception {
        String ownerEmail = "integ-owner-confirm-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);

        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-client-confirm-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId);

        log.debug("Act: PATCH {}/{}/confirm with SALON_OWNER token — must return 204", BOOKINGS_URL, bookingId);
        ResponseEntity<Void> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/confirm", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);

        assertThat(response.getStatusCode())
                .as("owner confirming booking must return 204, bookingId=%s", bookingId)
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String createClientAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'CLIENT', true)",
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
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_OWNER', true)",
                ownerId, ownerEmail, hash);

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + ownerId);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "master-" + System.nanoTime() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active) VALUES (?, ?, ?, 'SALON_MASTER', ?, true)",
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
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, null);

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
