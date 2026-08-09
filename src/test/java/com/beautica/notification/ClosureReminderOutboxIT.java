package com.beautica.notification;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 29.5 — real-Postgres (Testcontainers) proof that:
 * <ol>
 *   <li>{@code V131__widen_outbox_event_closure_reminder.sql} actually widened {@code
 *       chk_outbox_event} — {@link NotificationOutboxService#enqueueClosureReminder(UUID)} inserts
 *       successfully, and a raw JDBC insert with a bogus {@code event_type} is still rejected (the
 *       constraint was widened, not dropped);</li>
 *   <li>{@link NotificationOutboxDrainWorker} dispatches a {@code CLOSURE_REMINDER} row to {@code
 *       notifyClosureReminder} — the PROVIDER-facing method — and never to {@code
 *       notifyReviewRequested} (the mass-mail vector this whole track avoids coupling to);</li>
 *   <li>the enqueue + drain cycle never writes to {@code bookings} at all — a full {@code (status,
 *       updated_at)} snapshot is byte-identical before and after.</li>
 * </ol>
 *
 * <p>{@link NotificationService} is mocked (mirrors {@code NotificationOutboxIntegrationTest}) to
 * keep SMTP/Firebase from firing and to isolate this test to the outbox pattern itself — real
 * Thymeleaf rendering of {@code closure-reminder.html} (including the "no note fields" guarantee)
 * is covered by {@code EmailTemplateRenderingTest}, a cheaper Thymeleaf-only slice.
 */
@Import(TestSecurityConfig.class)
@DisplayName("ClosureReminderOutbox — full-stack integration (Phase 29.5)")
class ClosureReminderOutboxIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationOutboxService outboxService;

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private NotificationService notificationService;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder);
    }

    @Test
    @DisplayName("enqueueClosureReminder inserts exactly one CLOSURE_REMINDER row — proves V131 widened chk_outbox_event")
    void should_insertClosureReminderRow_when_enqueueClosureReminderCalled() {
        UUID masterId = fixtures.createIndependentMaster(
                "closure-outbox-master-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser(
                "closure-outbox-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId);

        new TransactionTemplate(txManager).execute(status -> {
            outboxService.enqueueClosureReminder(bookingId);
            return null;
        });

        List<NotificationOutboxEntry> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        NotificationOutboxEntry row = rows.get(0);
        assertThat(row.getEventType()).isEqualTo(OutboxEventType.CLOSURE_REMINDER);
        assertThat(row.getAggregateId()).isEqualTo(bookingId);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getPayload()).isNull();
    }

    @Test
    @DisplayName("a raw JDBC insert with an unknown event_type is still rejected by chk_outbox_event "
            + "— proves V131 widened rather than dropped the constraint")
    void should_rejectUnknownEventType_when_rawInsertAttempted() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO notification_outbox (id, event_type, aggregate_id, status, attempts, "
                        + "created_at, updated_at) VALUES (?, 'NOT_A_REAL_EVENT', ?, 'PENDING', 0, NOW(), NOW())",
                UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    @DisplayName("drain worker dispatches a CLOSURE_REMINDER row to notifyClosureReminder (never "
            + "notifyReviewRequested) and leaves the bookings table byte-identical")
    void should_dispatchToProvider_and_leaveBookingsUntouched_when_closureReminderDrained() {
        UUID masterId = fixtures.createIndependentMaster(
                "closure-drain-master-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser(
                "closure-drain-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId);

        Map<String, Object> before = jdbcTemplate.queryForMap(
                "SELECT status, updated_at FROM bookings WHERE id = ?", bookingId);

        new TransactionTemplate(txManager).execute(status -> {
            outboxService.enqueueClosureReminder(bookingId);
            return null;
        });

        drainWorker.drain();

        verify(notificationService, times(1)).notifyClosureReminder(any());
        verify(notificationService, times(0)).notifyReviewRequested(any());

        NotificationOutboxEntry row = outboxRepository.findAll().get(0);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);

        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT status, updated_at FROM bookings WHERE id = ?", bookingId);
        assertThat(after)
                .as("the enqueue+drain cycle must never write bookings.status or touch the row at all")
                .isEqualTo(before);
    }

    private UUID insertConfirmedBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now().minusHours(5);
        OffsetDateTime endsAt = startsAt.plusHours(1);
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, startsAt, endsAt);
        return bookingId;
    }
}
