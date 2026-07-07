package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.service.BookingService;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1 (transition matrix) + T5 (transaction atomicity) of Phase 18.8.
 *
 * <p>Pins the {@code CONFIRMED → COMPLETED} transition at the <b>service</b> layer over a real
 * Testcontainers Postgres, together with the outbox side effect the completion writes: exactly
 * one {@code STATUS_CHANGED} row AND one {@code REVIEW_REQUESTED} row, both keyed to the completed
 * booking. Every other start status is rejected with a {@link BusinessException} and writes ZERO
 * outbox rows, and the review prompt is at-most-once across repeated completion attempts.
 *
 * <p>The seam is an <b>INDEPENDENT_MASTER</b> booking so {@code enforceCanCompleteBooking} resolves
 * authority via {@code master.user.id == actorUserId} — no {@code SecurityContext} priming is
 * required to call the service method directly (the salon path reads the security context, which is
 * covered end-to-end over HTTP in {@link BookingCompletionSecurityIT}).
 *
 * <p>{@code NotificationService} is mocked so the always-on {@code @Scheduled} drain worker cannot
 * fire real email/push while these assertions run; it may still flip the seeded rows to {@code SENT}
 * (a no-op against the mock), so all assertions here count rows / event types (drain-invariant),
 * never their {@code status}.
 *
 * <p>Fixture helpers are intentionally local, mirroring {@link BookingIntegrationTest} /
 * {@code NotificationOutboxIntegrationTest} — extraction to the shared base is the standing backlog
 * follow-up already flagged there; not widened in this pass to keep the 40-class base untouched.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Booking completion — transition matrix + outbox atomicity (T1, T5)")
class BookingCompletionIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private BookingService bookingService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager txManager;

    // Mocked so the background @Scheduled drain never fires real SMTP/push against seeded rows.
    @MockBean
    private NotificationService notificationService;

    // ── T1: happy path writes exactly the two expected outbox rows ───────────────

    @Test
    @DisplayName("CONFIRMED → COMPLETED: booking is COMPLETED and exactly one STATUS_CHANGED + one REVIEW_REQUESTED row are written (both keyed to the booking)")
    void should_writeStatusChangedAndReviewRequestedRows_when_confirmedBookingCompleted() {
        UUID masterId = createIndependentMaster("compl-happy-master-" + System.nanoTime() + "@beautica.test");
        UUID masterUserId = masterUserId(masterId);
        UUID masterServiceId = createMasterService(masterId);
        UUID clientId = createClient("compl-happy-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertBooking(clientId, masterId, masterServiceId, null, BookingStatus.CONFIRMED);

        assertThat(countOutbox()).as("outbox empty before completion").isZero();

        bookingService.completeBooking(masterUserId, bookingId);

        assertThat(bookingStatus(bookingId))
                .as("booking must be COMPLETED after completeBooking")
                .isEqualTo("COMPLETED");
        assertThat(countOutbox())
                .as("exactly two outbox rows must be written on completion")
                .isEqualTo(2L);
        assertThat(countOutboxByTypeForAggregate("STATUS_CHANGED", bookingId))
                .as("exactly one STATUS_CHANGED row keyed to the booking")
                .isEqualTo(1L);
        assertThat(countOutboxByTypeForAggregate("REVIEW_REQUESTED", bookingId))
                .as("exactly one REVIEW_REQUESTED row keyed to the booking")
                .isEqualTo(1L);
    }

    // ── T1: every illegal start status is rejected and writes no outbox row ──────

    @ParameterizedTest(name = "start status {0} → BusinessException, status unchanged, zero outbox rows")
    @EnumSource(value = BookingStatus.class,
            names = {"PENDING", "DECLINED", "CANCELLED", "NOT_COMPLETED", "COMPLETED"})
    @DisplayName("completing from any non-CONFIRMED start status throws, leaves status unchanged, and writes ZERO outbox rows")
    void should_throwAndWriteNoOutbox_when_completingFromIllegalStartStatus(BookingStatus startStatus) {
        UUID masterId = createIndependentMaster("compl-illegal-master-" + startStatus + "-" + System.nanoTime() + "@beautica.test");
        UUID masterUserId = masterUserId(masterId);
        UUID masterServiceId = createMasterService(masterId);
        UUID clientId = createClient("compl-illegal-client-" + startStatus + "-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertBooking(clientId, masterId, masterServiceId, null, startStatus);

        assertThatThrownBy(() -> bookingService.completeBooking(masterUserId, bookingId))
                .as("completing from %s must be rejected", startStatus)
                .isInstanceOf(BusinessException.class);

        assertThat(bookingStatus(bookingId))
                .as("status must be unchanged (%s) after a rejected completion", startStatus)
                .isEqualTo(startStatus.name());
        assertThat(countOutbox())
                .as("a rejected completion must write no outbox row")
                .isZero();
    }

    // ── T1: at-most-once review prompt across repeated completions ───────────────

    @Test
    @DisplayName("re-completing a COMPLETED booking throws and the REVIEW_REQUESTED row count stays exactly 1 (at-most-once prompt)")
    void should_keepExactlyOneReviewRequestedRow_when_completedBookingReCompleted() {
        UUID masterId = createIndependentMaster("compl-once-master-" + System.nanoTime() + "@beautica.test");
        UUID masterUserId = masterUserId(masterId);
        UUID masterServiceId = createMasterService(masterId);
        UUID clientId = createClient("compl-once-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertBooking(clientId, masterId, masterServiceId, null, BookingStatus.CONFIRMED);

        bookingService.completeBooking(masterUserId, bookingId);
        assertThat(countOutboxByTypeForAggregate("REVIEW_REQUESTED", bookingId))
                .as("first completion enqueues one review prompt")
                .isEqualTo(1L);

        assertThatThrownBy(() -> bookingService.completeBooking(masterUserId, bookingId))
                .as("second completion of an already-COMPLETED booking must be rejected")
                .isInstanceOf(BusinessException.class);

        assertThat(countOutboxByTypeForAggregate("REVIEW_REQUESTED", bookingId))
                .as("review prompt must remain at-most-once across repeated completion attempts")
                .isEqualTo(1L);
    }

    // ── T5: both outbox rows roll back with the completeBooking transaction ──────

    @Test
    @DisplayName("rolling back completeBooking's transaction persists ZERO outbox rows and leaves the booking CONFIRMED (STATUS_CHANGED + REVIEW_REQUESTED roll back atomically)")
    void should_persistZeroOutboxRows_when_completeBookingTransactionRollsBack() {
        UUID masterId = createIndependentMaster("compl-rollback-master-" + System.nanoTime() + "@beautica.test");
        UUID masterUserId = masterUserId(masterId);
        UUID masterServiceId = createMasterService(masterId);
        UUID clientId = createClient("compl-rollback-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertBooking(clientId, masterId, masterServiceId, null, BookingStatus.CONFIRMED);

        // completeBooking is @Transactional(REQUIRED); it joins this outer transaction, so marking
        // the outer as rollback-only aborts the whole unit — the status mutation AND both outbox
        // INSERTs (STATUS_CHANGED + the MANDATORY-propagation REVIEW_REQUESTED) never commit. This
        // reproduces the effect of any save failure inside the completion transaction.
        new TransactionTemplate(txManager).execute(status -> {
            bookingService.completeBooking(masterUserId, bookingId);
            status.setRollbackOnly();
            return null;
        });

        assertThat(countOutbox())
                .as("no outbox row may survive a rolled-back completion transaction")
                .isZero();
        assertThat(bookingStatus(bookingId))
                .as("booking must remain CONFIRMED — the COMPLETED mutation rolled back too")
                .isEqualTo("CONFIRMED");
    }

    // ── fixtures (local by house convention — see class Javadoc) ─────────────────

    private UUID createIndependentMaster(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID createClient(String email) {
        UUID clientId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'CLIENT', true, true)",
                clientId, email, passwordEncoder.encode(TEST_PASSWORD));
        return clientId;
    }

    /** Inserts an APP booking in the given status with past times (no overlap constraint at play). */
    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId, BookingStatus status) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, NOW() - interval '2 hours', NOW() - interval '1 hour', " +
                        "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status.name());
        return bookingId;
    }

    private UUID masterUserId(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private String bookingStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private long countOutbox() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_outbox", Long.class);
    }

    private long countOutboxByTypeForAggregate(String eventType, UUID aggregateId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = ? AND aggregate_id = ?",
                Long.class, eventType, aggregateId);
    }
}
