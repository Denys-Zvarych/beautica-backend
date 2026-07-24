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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 24.7, Risk R7 (HIGHEST-RISK) — track 24.x deleted the {@code CONFIRMED → PENDING} revert
 * on reschedule, which used to be the provider's last chance to catch a bad reschedule via
 * re-approval. With no re-approval backstop, the slot/overlap/lead-time/schedule guards inside
 * {@code BookingService#rescheduleBooking} are the ONLY thing standing between a client and a
 * double-booked or off-schedule slot. This class proves the guards that were previously only
 * unit-tested (against a mocked {@code SlotCalculationService}/{@code Clock}) still hold when
 * driven over the real HTTP stack against a real Postgres.
 *
 * <p><b>Deliberately NOT re-covered here</b> (already asserted at the integration layer in
 * {@link BookingIntegrationTest} — no duplication): reschedule onto a slot occupied by another
 * booking on the same master (409); reschedule onto a slot conflicting with the client's own
 * other-master booking (409 {@code CLIENT_BOOKING_CONFLICT}); a successful reschedule leaves the
 * booking {@code CONFIRMED} at the new time; the booking's own row is excluded from the overlap
 * check.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Booking reschedule — guard-chain regression (Risk R7, no re-approval backstop)")
class BookingRescheduleGuardChainIT extends AbstractIntegrationTest {

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

    @Test
    @DisplayName("PATCH /reschedule — 409 'Slot not available' when the new time is OUTSIDE the master's "
            + "working hours (no re-approval backstop — the schedule oracle is the only guard)")
    void should_return409_when_rescheduleTargetsTimeOutsideWorkingHours() throws Exception {
        UUID masterId = createSalonOwnerSalonAndMaster("resched-guard-hours-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        // Master works 09:00–17:00 only (not addWorkingHoursForEveryDay's default 08:00–20:00),
        // so a reschedule request for 20:00 falls cleanly outside the schedule.
        addNarrowWorkingHoursForEveryDay(masterId, "09:00", "17:00");

        String clientToken = createClientAndGetToken("resched-guard-hours-client-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId, startsAt);

        ZonedDateTime offScheduleStart = startsAt.withHour(20);
        String body = "{\"newStartsAt\":\"" + offScheduleStart.toOffsetDateTime() + "\"}";
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientToken)), String.class);

        assertThat(response.getStatusCode())
                .as("rescheduling onto a time outside the master's working hours must be rejected with 409")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Europe/Kyiv', 'HH24:MI') FROM bookings WHERE id = ?",
                String.class, bookingId))
                .as("booking must remain at its original 10:00 slot after a rejected reschedule")
                .isEqualTo("10:00");
    }

    @Test
    @DisplayName("PATCH /reschedule — 400 when the new start time is inside the minimum lead-time window "
            + "(below the 15-minute floor)")
    void should_return400_when_rescheduleTargetsTimeInsideLeadTimeWindow() throws Exception {
        UUID masterId = createSalonOwnerSalonAndMaster("resched-guard-lead-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("resched-guard-lead-client-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId, startsAt);

        // 5 minutes from now is below BookingWindow.MIN_MINUTES_AHEAD (15) — must be rejected
        // before ever consulting the schedule/overlap oracle.
        ZonedDateTime tooSoon = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusMinutes(5);
        String body = "{\"newStartsAt\":\"" + tooSoon.toOffsetDateTime() + "\"}";
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientToken)), String.class);

        assertThat(response.getStatusCode())
                .as("rescheduling inside the minimum lead-time window must be rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Europe/Kyiv', 'HH24:MI') FROM bookings WHERE id = ?",
                String.class, bookingId))
                .as("booking must remain at its original 10:00 slot after a rejected reschedule")
                .isEqualTo("10:00");
    }

    @Test
    @DisplayName("PATCH /reschedule — 403 (IDOR) when client B attempts to reschedule client A's booking")
    void should_return403_when_differentClientReschedulesAnotherClientsBooking() throws Exception {
        UUID masterId = createSalonOwnerSalonAndMaster("resched-guard-idor-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientAToken = createClientAndGetToken("resched-guard-idor-a-" + System.nanoTime() + "@beautica.test");
        String clientBToken = createClientAndGetToken("resched-guard-idor-b-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2)
                .withHour(11).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createBooking(clientAToken, masterId, masterServiceId, startsAt);

        ZonedDateTime newStartsAt = startsAt.plusHours(1);
        String body = "{\"newStartsAt\":\"" + newStartsAt.toOffsetDateTime() + "\"}";
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientBToken)), String.class);

        assertThat(response.getStatusCode())
                .as("client B rescheduling client A's booking must be denied with 403 (IDOR)")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Europe/Kyiv', 'HH24:MI') FROM bookings WHERE id = ?",
                String.class, bookingId))
                .as("booking must remain at its original 11:00 slot after a denied cross-client reschedule")
                .isEqualTo("11:00");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String createClientAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
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
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, ownerEmail, hash);

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + ownerId);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "resched-guard-master-" + System.nanoTime() + "@beautica.test";
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
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());

        UUID masterServiceId = UUID.randomUUID();
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

    private void addWorkingHoursForEveryDay(UUID masterId) {
        addNarrowWorkingHoursForEveryDay(masterId, "08:00", "20:00");
    }

    /** Same weekly_schedules/working_intervals shape as {@link BookingIntegrationTest}, with a caller-supplied window. */
    private void addNarrowWorkingHoursForEveryDay(UUID masterId, String startTime, String endTime) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) VALUES (?, ?, DATE '2020-01-01', NULL)",
                scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            // Explicit ::time casts — unlike a literal ('08:00') embedded in the SQL text (which
            // Postgres coerces implicitly), a bind parameter arrives via the extended protocol with
            // no inferred type and Postgres refuses the implicit varchar→time coercion (42804,
            // translated by Spring into BadSqlGrammarException) without it.
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) VALUES (?, ?, ?, ?::time, ?::time)",
                    UUID.randomUUID(), scheduleId, day, startTime, endTime);
        }
    }

    private UUID createBooking(String clientToken, UUID masterId, UUID masterServiceId,
                               ZonedDateTime startsAt) throws Exception {
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null);

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<BookingResponse>>() {});
        return body.data().id();
    }

    private org.springframework.http.HttpHeaders bearerHeaders(String token) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }
}
