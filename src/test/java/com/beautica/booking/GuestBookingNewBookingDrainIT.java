package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.entity.Booking;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.sms.SmsService;
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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * Regression for the guest-booking {@code NEW_BOOKING} notification NPE (audit finding [HIGH] 1):
 * {@code GuestBookingService.createGuestBooking} enqueues a {@code NEW_BOOKING} outbox row for
 * EVERY guest (LINK) booking — 100% of them, since it is unconditional on the create path.
 * {@code Booking.getClient()} is null for every one of those bookings (V89
 * {@code chk_bookings_guest_fields}). Before the fix, {@code NotificationService.notifyNewBooking}
 * and {@code EmailNotificationService.sendNewBookingEmail} unconditionally dereferenced
 * {@code booking.getClient()}, so draining this row always NPE'd and the row went DEAD after
 * {@code MAX_ATTEMPTS} — the master was never notified of a single guest booking.
 *
 * <p>{@code NotificationOutboxService} and {@code NotificationService} are intentionally left
 * REAL (unlike suites that mock the outbox to keep assertions to HTTP status only) — this test
 * needs the real enqueue-then-drain path to prove the guest-identity fallback actually fires.
 * {@code EmailNotificationService} stays mocked (inherited from {@link AbstractIntegrationTest}),
 * so no real SMTP I/O happens; {@code SmsService} is mocked here for the same reason on the
 * guest-confirmation-SMS side effect. Push notifications are a documented no-op under the test
 * profile (no device tokens registered), so draining performs no real Firebase I/O either.
 */
@DisplayName("Guest booking creation — NEW_BOOKING outbox must drain without a DEAD row (audit HIGH-1)")
class GuestBookingNewBookingDrainIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Autowired
    private TestRestTemplate restTemplate;

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
    @DisplayName("guest booking creation enqueues NEW_BOOKING and it drains to SENT, never DEAD")
    void should_drainNewBookingToSentWithoutDeadRow_when_guestBookingCreated() {
        String slug = "guest-drain-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug(slug);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);
        String token = guestTokenProvider.generate("+380501112233");
        String body = """
                {"serviceId":"%s","startsAt":"%s","name":"Олена","surname":"Коваль"}
                """.formatted(masterServiceId, startsAt.toOffsetDateTime());

        ResponseEntity<String> createResp = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, guestHeaders(token)), String.class);

        assertThat(createResp.getStatusCode())
                .as("guest booking creation must succeed")
                .isEqualTo(HttpStatus.CREATED);

        List<NotificationOutboxEntry> pending = outboxRepository.findAll().stream()
                .filter(e -> e.getEventType() == OutboxEventType.NEW_BOOKING)
                .toList();
        assertThat(pending)
                .as("createGuestBooking must enqueue exactly one NEW_BOOKING row")
                .hasSize(1);
        assertThat(pending.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);

        // Drain for real — NotificationService/EmailNotificationService are exercised through
        // the real drain worker (only EmailNotificationService's actual SMTP send is mocked).
        // Without the guest-identity fallback, booking.getClient() NPEs inside dispatch and the
        // drain worker marks the row DEAD after MAX_ATTEMPTS — the master is never notified.
        drainWorker.drain();

        NotificationOutboxEntry drained = outboxRepository.findById(pending.get(0).getId()).orElseThrow();
        assertThat(drained.getStatus())
                .as("the guest-identity fallback must let the NEW_BOOKING entry drain to SENT, never DEAD")
                .isEqualTo(OutboxStatus.SENT);
        assertThat(drained.getLastError()).isNull();

        long deadRows = outboxRepository.findAll().stream()
                .filter(e -> e.getStatus() == OutboxStatus.DEAD)
                .count();
        assertThat(deadRows)
                .as("zero DEAD rows must exist anywhere in the outbox after draining a guest booking creation")
                .isZero();

        // Proves the master-facing email dispatch was actually reached — i.e. clientName
        // resolution (booking.getClient() -> guest fallback) did not throw before this call.
        verify(emailNotificationService).sendNewBookingEmail(anyString(), any(Booking.class));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID createMasterWithSlug(String slug) {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true, 'Марія', 'Левченко')",
                ownerId, "owner-" + System.nanoTime() + "@beautica.test");

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, ?, NOW(), NOW())",
                masterId, ownerId, slug);
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

    private void addWorkingHoursForEveryDay(UUID masterId) {
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_hours (id, master_id, day_of_week, start_time, end_time, is_active) "
                            + "VALUES (?, ?, ?, '08:00', '20:00', true)",
                    UUID.randomUUID(), masterId, day);
        }
    }

    private HttpHeaders guestHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
