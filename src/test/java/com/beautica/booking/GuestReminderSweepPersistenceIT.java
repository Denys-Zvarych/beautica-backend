package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.job.BookingReminderJob;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.notification.sms.SmsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratification net for dropping the explicit {@code bookingRepository.saveAll(singles)} from
 * {@link BookingReminderJob#sendReminders()}.
 *
 * <p>The claim being tested is that the rows are MANAGED — they are loaded by
 * {@code findGuestBookingsForReminder} inside the job's own {@code @Transactional} method, so Hibernate's
 * dirty check flushes {@code reminderSent = true} at commit and the {@code saveAll} was a redundant merge
 * pass over entities already in the persistence context.
 *
 * <p>That claim is the one change in this PR with real regression potential, and the unit test that
 * accompanies it ({@code should_notCallSaveAll_when_bookingsDue}) cannot detect a failure: it drives the
 * job with a MOCK repository, so there is no persistence context, no flush and no commit — the flag is
 * asserted on a detached POJO. If dirty checking did not in fact persist the column, every hourly sweep
 * would re-select the same bookings and the guest would receive the same reminder every hour until their
 * appointment. Only a real transaction against a real database can tell the two apart, so this test reads
 * the column back with {@link org.springframework.jdbc.core.JdbcTemplate} — outside the job's persistence
 * context — and then re-runs the sweep to prove the query no longer re-selects the row.
 *
 * <p>{@link SmsService} is mocked so the sweep's after-commit dispatcher cannot attempt a real Turbosms
 * call. Nothing is asserted about it: delivery is asynchronous by design, so a call-count assertion there
 * would only be a timing race (Anti-Bug §M).
 */
@DisplayName("Guest reminder sweep — reminderSent persists by dirty checking, with no explicit save")
class GuestReminderSweepPersistenceIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final String GUEST_PHONE = "+380501234567";

    @Autowired
    private BookingReminderJob bookingReminderJob;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private Clock clock;

    @MockBean
    private SmsService smsService;

    @Test
    @DisplayName("the sweep must persist reminderSent=true to the DATABASE, not just to the in-memory entity")
    void should_persistReminderSentToTheDatabase_when_theSweepRunsWithoutAnExplicitSave() {
        UUID bookingId = seedGuestBookingDueForReminder();

        assertThat(reminderSent(bookingId))
                .as("fixture sanity: the row must start un-reminded or the assertion below proves nothing")
                .isFalse();

        bookingReminderJob.sendReminders();

        assertThat(reminderSent(bookingId))
                .as("reminderSent was never flushed — with saveAll removed, dirty checking is the ONLY "
                        + "thing that persists this column, and a guest whose flag never lands is "
                        + "re-reminded by every subsequent hourly sweep")
                .isTrue();
    }

    /**
     * The consequence, asserted at the seam the flag exists for: once the sweep has run, the reminder
     * query must no longer re-select the row. Asserted against the production query rather than against
     * {@code SmsService} call counts, because delivery is asynchronous by design and a call-count
     * assertion there could only be made time-dependent (Anti-Bug §M).
     */
    @Test
    @DisplayName("a second sweep must re-select nothing — the persisted flag is what makes the reminder once-only")
    void should_reSelectNothingOnASecondSweep_when_theFlagPersisted() {
        seedGuestBookingDueForReminder();
        OffsetDateTime now = OffsetDateTime.now(clock);

        assertThat(bookingRepository.findGuestBookingsForReminder(now.plusHours(23), now.plusHours(25)))
                .as("fixture sanity: the seeded booking must be due, or the assertion below is vacuous")
                .hasSize(1);

        bookingReminderJob.sendReminders();

        assertThat(bookingRepository.findGuestBookingsForReminder(now.plusHours(23), now.plusHours(25)))
                .as("the sweep re-selects an already-reminded booking — every hourly tick would send the "
                        + "guest the same reminder again")
                .isEmpty();
    }

    // ── fixture ─────────────────────────────────────────────────────────────────

    /**
     * A CONFIRMED guest (LINK) booking starting 24 h from now — inside the job's [now+23h, now+25h]
     * window on every run, without pinning a literal date. Guest identity + cancel token are mandatory
     * for LINK rows (V89/V91 {@code chk_bookings_guest_fields}); {@code client_id} must be NULL.
     */
    private UUID seedGuestBookingDueForReminder() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', 'Марія', 'Левченко', true, true)",
                userId, "guest-reminder-master-" + System.nanoTime() + "@beautica.test",
                passwordEncoder.encode(TEST_PASSWORD));

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Манікюр', ?, 60, 350.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveUnusedServiceTypeId("INDEPENDENT_MASTER", userId));

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "guest_name, guest_surname, guest_phone, cancel_token, reminder_sent, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, 'CONFIRMED', NOW() + interval '24 hours', "
                        + "NOW() + interval '25 hours', 350.00, 60, 0, 'LINK', 'Олена', 'Коваль', ?, ?, FALSE, "
                        + "NOW(), NOW())",
                bookingId, masterId, masterServiceId, GUEST_PHONE, UUID.randomUUID());
        return bookingId;
    }

    /** Reads the column through a separate connection, so an unflushed in-memory value cannot satisfy it. */
    private boolean reminderSent(UUID bookingId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT reminder_sent FROM bookings WHERE id = ?", Boolean.class, bookingId));
    }
}
