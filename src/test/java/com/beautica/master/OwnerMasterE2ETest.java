package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.dashboard.dto.RevenueResponse;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.entity.PriceType;
import com.beautica.service.dto.ServiceDefinitionResponse;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12.7 — end-to-end happy-path test for the owner-as-master feature.
 *
 * <p>Exercises the full booking lifecycle through real HTTP calls against a Testcontainers
 * PostgreSQL instance:
 * <ol>
 *   <li>Owner registers (JDBC insert) and logs in.</li>
 *   <li>Owner creates a salon via POST /api/v1/salons.</li>
 *   <li>Owner enables their owner-master profile via POST /api/v1/salons/{id}/master.</li>
 *   <li>Owner sets working hours via PATCH /api/v1/masters/{id}/working-hours.</li>
 *   <li>Owner creates a service definition via POST /api/v1/salons/{id}/services.</li>
 *   <li>Owner assigns the service to the owner-master via POST /api/v1/salons/{id}/masters/{id}/services.</li>
 *   <li>Client books the owner-master via POST /api/v1/bookings.</li>
 *   <li>Owner confirms the booking via PATCH /api/v1/bookings/{id}/confirm.</li>
 *   <li>Owner completes the booking via PATCH /api/v1/bookings/{id}/complete.</li>
 *   <li>Owner checks the dashboard revenue — completed booking appears with positive revenue.</li>
 * </ol>
 *
 * <p>Cleanup is handled by {@link AbstractIntegrationTest#cleanDb()} ({@code @AfterEach}).
 */
@Import(TestSecurityConfig.class)
@DisplayName("Owner-as-Master — full booking lifecycle E2E (Phase 12.7)")
class OwnerMasterE2ETest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OwnerMasterE2ETest.class);

    private static final String SALONS_URL        = "/api/v1/salons";
    private static final String BOOKINGS_URL      = "/api/v1/bookings";
    private static final String DASHBOARD_URL     = "/api/v1/dashboard/revenue";
    private static final String TEST_PASSWORD     = "Str0ngP@ss1!";
    private static final ZoneId KYIV              = ZoneId.of("Europe/Kyiv");

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

    // ── E2E happy path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Full lifecycle: owner enables master → client books → owner confirms+completes → revenue appears in dashboard")
    void should_completeFullBookingLifecycle_when_ownerOperatesAsOwnMaster() throws Exception {

        // ── Step 1: register an owner and a client via JDBC ──────────────────
        String ownerEmail = "e2e-owner-" + System.nanoTime() + "@beautica.test";
        String ownerHash  = passwordEncoder.encode(TEST_PASSWORD);
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, ownerEmail, ownerHash);

        String clientEmail = "e2e-client-" + System.nanoTime() + "@beautica.test";
        String clientHash  = passwordEncoder.encode(TEST_PASSWORD);
        UUID clientId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'CLIENT', true, true)",
                clientId, clientEmail, clientHash);

        String ownerToken  = loginAndGetToken(ownerEmail);
        String clientToken = loginAndGetToken(clientEmail);

        log.debug("Step 1 complete — owner={}, client={}", ownerEmail, clientEmail);

        // ── Step 2: create salon ──────────────────────────────────────────────
        var createSalonReq = new CreateSalonRequest("E2E Owner Studio", null, "Kyiv", null, null, null, null, null, null, null, null, null);
        ResponseEntity<String> salonResp = restTemplate.exchange(
                SALONS_URL, HttpMethod.POST,
                new HttpEntity<>(createSalonReq, bearerHeaders(ownerToken)),
                String.class);
        assertThat(salonResp.getStatusCode())
                .as("create salon must return 201")
                .isEqualTo(HttpStatus.CREATED);
        var salonBody = objectMapper.readValue(salonResp.getBody(), new TypeReference<ApiResponse<SalonResponse>>() {});
        UUID salonId = salonBody.data().id();
        log.debug("Step 2 complete — salonId={}", salonId);

        // ── Step 3: enable owner-master ───────────────────────────────────────
        ResponseEntity<String> enableResp = restTemplate.exchange(
                SALONS_URL + "/" + salonId + "/master", HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);
        assertThat(enableResp.getStatusCode())
                .as("enable owner-master must return 200")
                .isEqualTo(HttpStatus.OK);
        var enableBody = objectMapper.readValue(enableResp.getBody(),
                new TypeReference<ApiResponse<MasterDetailResponse>>() {});
        UUID masterId = enableBody.data().masterId();
        assertThat(enableBody.data().masterType().name())
                .as("enabled master must have type SALON_OWNER")
                .isEqualTo("SALON_OWNER");
        log.debug("Step 3 complete — masterId={}", masterId);

        // ── Step 4: set working hours for every day of the week ───────────────
        // PATCH /api/v1/masters/{masterId}/working-hours accepts a JSON array of WorkingHoursRequest
        List<WorkingHoursRequest> workingHours = List.of(
                new WorkingHoursRequest(1, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0), true),
                new WorkingHoursRequest(2, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0), true),
                new WorkingHoursRequest(3, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0), true),
                new WorkingHoursRequest(4, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0), true),
                new WorkingHoursRequest(5, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0), true),
                new WorkingHoursRequest(6, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0), true),
                new WorkingHoursRequest(7, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0), true)
        );
        ResponseEntity<String> hoursResp = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/working-hours", HttpMethod.PATCH,
                new HttpEntity<>(workingHours, bearerHeaders(ownerToken)),
                String.class);
        assertThat(hoursResp.getStatusCode())
                .as("upsert working hours must return 200")
                .isEqualTo(HttpStatus.OK);
        log.debug("Step 4 complete — working hours set for masterId={}", masterId);

        // ── Step 5: create a service definition ───────────────────────────────
        var createSvcReq = new CreateServiceDefinitionRequest(
                "E2E Hair Treatment", null, "HAIRCUT", 60, 0,
                PriceType.FIXED, new BigDecimal("450.00"), null, null, null);
        ResponseEntity<String> svcResp = restTemplate.exchange(
                SALONS_URL + "/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(createSvcReq, bearerHeaders(ownerToken)),
                String.class);
        assertThat(svcResp.getStatusCode())
                .as("create service definition must return 201")
                .isEqualTo(HttpStatus.CREATED);
        var svcBody = objectMapper.readValue(svcResp.getBody(),
                new TypeReference<ApiResponse<ServiceDefinitionResponse>>() {});
        UUID serviceDefId = svcBody.data().id();
        log.debug("Step 5 complete — serviceDefId={}", serviceDefId);

        // ── Step 6: assign the service to the owner-master ────────────────────
        var assignReq = new AssignServiceToMasterRequest(serviceDefId, null, null);
        ResponseEntity<String> assignResp = restTemplate.exchange(
                SALONS_URL + "/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(assignReq, bearerHeaders(ownerToken)),
                String.class);
        assertThat(assignResp.getStatusCode())
                .as("assign service to owner-master must return 201")
                .isEqualTo(HttpStatus.CREATED);

        // Retrieve the master_services UUID from the database so we can pass it to CreateBookingRequest
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? AND service_def_id = ?",
                UUID.class, masterId, serviceDefId);
        assertThat(masterServiceId).isNotNull();
        log.debug("Step 6 complete — masterServiceId={}", masterServiceId);

        // ── Step 7: client books the owner-master ─────────────────────────────
        // Use Kyiv timezone explicitly so slot arithmetic matches the server's configured zone
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        var bookingReq = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null);
        ResponseEntity<String> bookingResp = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(bookingReq, bearerHeaders(clientToken)),
                String.class);
        assertThat(bookingResp.getStatusCode())
                .as("client booking must return 201")
                .isEqualTo(HttpStatus.CREATED);
        var bookingBody = objectMapper.readValue(bookingResp.getBody(),
                new TypeReference<ApiResponse<BookingResponse>>() {});
        UUID bookingId = bookingBody.data().id();
        assertThat(bookingBody.data().masterId())
                .as("booking masterId must match the owner-master")
                .isEqualTo(masterId);
        log.debug("Step 7 complete — bookingId={}", bookingId);

        // ── Step 8: owner confirms the booking ────────────────────────────────
        ResponseEntity<Void> confirmResp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/confirm", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);
        assertThat(confirmResp.getStatusCode())
                .as("confirm must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        String statusAfterConfirm = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(statusAfterConfirm)
                .as("booking must be CONFIRMED in DB after owner confirms")
                .isEqualTo("CONFIRMED");
        log.debug("Step 8 complete — booking CONFIRMED");

        // ── Step 9: owner completes the booking ───────────────────────────────
        ResponseEntity<Void> completeResp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);
        assertThat(completeResp.getStatusCode())
                .as("complete must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        String statusAfterComplete = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(statusAfterComplete)
                .as("booking must be COMPLETED in DB after owner completes")
                .isEqualTo("COMPLETED");
        log.debug("Step 9 complete — booking COMPLETED");

        // ── Step 10: verify completed booking appears in dashboard revenue ─────
        // Narrow the date range to a window that includes the booking's creation date.
        // The dashboard service uses the booking's starts_at field in the revenue query.
        LocalDate from = LocalDate.now(KYIV).minusDays(1);
        LocalDate to   = LocalDate.now(KYIV).plusDays(7);
        String dashUrl = DASHBOARD_URL + "?from=" + from + "&to=" + to;

        log.debug("Step 10: GET {} as SALON_OWNER — completed booking must appear in revenue", dashUrl);
        ResponseEntity<String> dashResp = restTemplate.exchange(
                dashUrl, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);
        assertThat(dashResp.getStatusCode())
                .as("dashboard revenue request must return 200")
                .isEqualTo(HttpStatus.OK);

        var dashBody = objectMapper.readValue(dashResp.getBody(),
                new TypeReference<ApiResponse<RevenueResponse>>() {});
        assertThat(dashBody.success()).isTrue();
        assertThat(dashBody.data().totalCompletedBookings())
                .as("dashboard must show at least 1 completed booking for the owner-master")
                .isGreaterThanOrEqualTo(1L);
        assertThat(dashBody.data().estimatedRevenue())
                .as("dashboard revenue must be positive after one completed booking")
                .isGreaterThan(BigDecimal.ZERO);

        log.debug("Step 10 complete — revenue={} totalBookings={}",
                dashBody.data().estimatedRevenue(), dashBody.data().totalCompletedBookings());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
