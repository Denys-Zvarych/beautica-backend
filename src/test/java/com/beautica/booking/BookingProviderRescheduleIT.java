package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.repository.NotificationOutboxRepository;
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

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 27.2 — full-stack guard chain for the widened {@code PATCH /bookings/{id}/reschedule}
 * endpoint (REVERSES the previously-locked "reschedule is client-only" decision), plus the Phase
 * 27.3 provider-initiated notification payload.
 *
 * <p>Deliberately NOT re-covered here (already asserted elsewhere): the pre-existing client
 * reschedule slot/overlap/lead-time guard chain ({@link BookingRescheduleGuardChainIT}); the
 * CLIENT-elapsed reschedule guard ({@link BookingElapsedClientGuardIT}); the @WebMvcTest-level
 * {@code @PreAuthorize} SpEL wiring ({@code BookingControllerTest}).
 */
@Import(TestSecurityConfig.class)
@DisplayName("Booking provider-initiated reschedule (Phase 27.2/27.3)")
class BookingProviderRescheduleIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    private BookingTestFixtures fixtures;

    // Mocked so a background outbox drain never performs real SMTP/push against seeded rows —
    // no test here calls drainWorker.drain() directly, so this is a defensive precaution only.
    @MockBean
    private com.beautica.notification.service.NotificationService notificationService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("PATCH /reschedule — 200 when the SALON_OWNER reschedules their own client's booking, "
            + "and enqueues a PROVIDER-initiated outbox row")
    void should_return200_when_authorizedOwnerReschedulesOwnClientsBooking() throws Exception {
        var salon = fixtures.createSalon("provresched-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        addWorkingHoursForEveryDay(salon.masterId());

        UUID clientId = fixtures.createUser(
                "provresched-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = insertConfirmedBooking(clientId, salon.masterId(), masterServiceId, salon.salonId(), startsAt);

        ZonedDateTime newStartsAt = startsAt.withHour(12);
        String body = "{\"newStartsAt\":\"" + newStartsAt.toOffsetDateTime() + "\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, fixtures.bearerHeaders(fixtures.tokenFor(salon.ownerEmail()))),
                String.class);

        assertThat(resp.getStatusCode())
                .as("an authorized SALON_OWNER rescheduling their own booking must return 200 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Europe/Kyiv', 'HH24:MI') FROM bookings WHERE id = ?",
                String.class, bookingId))
                .isEqualTo("12:00");

        List<NotificationOutboxEntry> rows = outboxRepository.findAll();
        assertThat(rows)
                .as("the reschedule must enqueue exactly one BOOKING_RESCHEDULED row")
                .hasSize(1);
        NotificationOutboxEntry entry = rows.get(0);
        assertThat(entry.getEventType().name()).isEqualTo("BOOKING_RESCHEDULED");
        assertThat(entry.getPayload())
                .as("a provider-initiated reschedule must record initiatedBy=PROVIDER in the outbox payload")
                .contains("\"initiatedBy\":\"PROVIDER\"");
    }

    @Test
    @DisplayName("PATCH /reschedule — a CLIENT-initiated reschedule still enqueues a CLIENT-addressed "
            + "(initiatedBy=CLIENT) outbox row (regression guard for the payload branch)")
    void should_enqueueClientInitiatedPayload_when_clientReschedules() throws Exception {
        var salon = fixtures.createSalon("provresched-clientinit-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        addWorkingHoursForEveryDay(salon.masterId());

        String clientEmail = "provresched-clientinit-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV).plusDays(2)
                .withHour(9).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = insertConfirmedBooking(clientId, salon.masterId(), masterServiceId, salon.salonId(), startsAt);

        ZonedDateTime newStartsAt = startsAt.withHour(11);
        String body = "{\"newStartsAt\":\"" + newStartsAt.toOffsetDateTime() + "\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, fixtures.bearerHeaders(fixtures.tokenFor(clientEmail))),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<NotificationOutboxEntry> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPayload())
                .as("a client-initiated reschedule must record initiatedBy=CLIENT")
                .contains("\"initiatedBy\":\"CLIENT\"");
    }

    @Test
    @DisplayName("PATCH /reschedule — 409 when a provider reschedules a booking that has ALREADY STARTED "
            + "(Phase 27.1 assertCurrentNotElapsedForReschedule)")
    void should_return409_when_providerReschedulesElapsedCurrentBooking() throws Exception {
        var salon = fixtures.createSalon("provresched-elapsed-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        addWorkingHoursForEveryDay(salon.masterId());

        UUID clientId = fixtures.createUser(
                "provresched-elapsed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        // Current booking starts 1 hour AGO — already underway.
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV).minusHours(1);
        UUID bookingId = insertConfirmedBooking(clientId, salon.masterId(), masterServiceId, salon.salonId(), startsAt);

        ZonedDateTime newStartsAt = ZonedDateTime.now(KYIV).plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        String body = "{\"newStartsAt\":\"" + newStartsAt.toOffsetDateTime() + "\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, fixtures.bearerHeaders(fixtures.tokenFor(salon.ownerEmail()))),
                String.class);

        assertThat(resp.getStatusCode())
                .as("rescheduling an already-started booking as the provider must be rejected with 409 — body: %s",
                        resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /reschedule — 403 when a SALON_ADMIN assigned to a DIFFERENT salon attempts to reschedule")
    void should_return403_when_unassignedAdminAttemptsReschedule() throws Exception {
        var salonA = fixtures.createSalon("provresched-unassigned-a-" + System.nanoTime() + "@beautica.test");
        var salonB = fixtures.createSalon("provresched-unassigned-b-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createSalonService(salonA.salonId(), salonA.masterId());
        addWorkingHoursForEveryDay(salonA.masterId());

        String unassignedAdminEmail = "provresched-unassigned-admin-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(unassignedAdminEmail, "SALON_ADMIN", salonB.salonId());

        UUID clientId = fixtures.createUser(
                "provresched-unassigned-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV).plusDays(2).withHour(11).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = insertConfirmedBooking(clientId, salonA.masterId(), masterServiceId, salonA.salonId(), startsAt);

        String body = "{\"newStartsAt\":\"" + startsAt.withHour(13).toOffsetDateTime() + "\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, fixtures.bearerHeaders(fixtures.tokenFor(unassignedAdminEmail))),
                String.class);

        assertThat(resp.getStatusCode())
                .as("SALON_ADMIN assigned to a different salon must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /reschedule — 403 when a SALON_MASTER (read-only role) attempts to reschedule")
    void should_return403_when_salonMasterAttemptsReschedule() throws Exception {
        var salon = fixtures.createSalon("provresched-sm-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        addWorkingHoursForEveryDay(salon.masterId());

        UUID clientId = fixtures.createUser(
                "provresched-sm-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV).plusDays(2).withHour(11).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = insertConfirmedBooking(clientId, salon.masterId(), masterServiceId, salon.salonId(), startsAt);

        String body = "{\"newStartsAt\":\"" + startsAt.withHour(13).toOffsetDateTime() + "\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, fixtures.bearerHeaders(fixtures.tokenFor(salon.masterEmail()))),
                String.class);

        assertThat(resp.getStatusCode())
                .as("SALON_MASTER (read-only) must be denied rescheduling with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /reschedule — 200 when the provider reschedules a GUEST (LINK, null-client) "
            + "booking — no client account exists to lock/conflict-check or notify, so this pins the "
            + "no-client branch (resolveBookingForProviderReschedule's canReview null-guard, the "
            + "acquireAdvisoryLockWithTimeout-only lock path, and NotificationService#notifyBookingRescheduledClient's "
            + "guest guard) does not NPE")
    void should_return200_when_providerReschedulesGuestBookingWithNoClientAccount() throws Exception {
        var salon = fixtures.createSalon("provresched-guest-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        addWorkingHoursForEveryDay(salon.masterId());

        ZonedDateTime startsAt = ZonedDateTime.now(KYIV).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = insertConfirmedGuestBooking(salon.masterId(), masterServiceId, salon.salonId(), startsAt);

        ZonedDateTime newStartsAt = startsAt.withHour(14);
        String body = "{\"newStartsAt\":\"" + newStartsAt.toOffsetDateTime() + "\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, fixtures.bearerHeaders(fixtures.tokenFor(salon.ownerEmail()))),
                String.class);

        assertThat(resp.getStatusCode())
                .as("rescheduling a guest booking as the provider must succeed with 200 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Europe/Kyiv', 'HH24:MI') FROM bookings WHERE id = ?",
                String.class, bookingId))
                .isEqualTo("14:00");

        List<NotificationOutboxEntry> rows = outboxRepository.findAll();
        assertThat(rows)
                .as("a provider-initiated reschedule of a guest booking must still enqueue a BOOKING_RESCHEDULED row "
                        + "(the null-client guard lives in the notification drain path, not the outbox enqueue)")
                .hasSize(1);
        assertThat(rows.get(0).getPayload()).contains("\"initiatedBy\":\"PROVIDER\"");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    /** Open-ended weekly schedule, 08:00-20:00 every day — mirrors BookingRescheduleGuardChainIT. */
    private void addWorkingHoursForEveryDay(UUID masterId) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) VALUES (?, ?, DATE '2020-01-01', NULL)",
                scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, '08:00'::time, '20:00'::time)",
                    UUID.randomUUID(), scheduleId, day);
        }
    }

    private UUID insertConfirmedBooking(
            UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId, ZonedDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime startsAtOdt = startsAt.toOffsetDateTime();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, startsAtOdt, startsAtOdt.plusHours(1));
        return bookingId;
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    /**
     * Guest (LINK) booking, CONFIRMED, client_id NULL. Unlike {@code ClientReviewIT
     * #insertGuestBooking} (seeded straight to a TERMINAL status), this fixture is CONFIRMED — an
     * active, non-terminal status — so {@code chk_bookings_guest_fields} (V91) additionally
     * requires a non-null {@code cancel_token} on the row; only CANCELLED/COMPLETED/NOT_COMPLETED/
     * DECLINED are exempt from that clause.
     */
    private UUID insertConfirmedGuestBooking(
            UUID masterId, UUID masterServiceId, UUID salonId, ZonedDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime startsAtOdt = startsAt.toOffsetDateTime();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, cancel_token, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'LINK', "
                        + "'Гість', 'Тестовий', '+380509998877', ?, NOW(), NOW())",
                bookingId, masterId, masterServiceId, salonId, startsAtOdt, startsAtOdt.plusHours(1),
                UUID.randomUUID());
        return bookingId;
    }
}
