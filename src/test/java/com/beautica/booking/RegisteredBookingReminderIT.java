package com.beautica.booking;

import com.beautica.auth.Role;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.job.BookingReminderJob;
import com.beautica.booking.job.RegisteredBookingReminderSweep;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.service.BookingService;
import com.beautica.common.TimeZones;
import com.beautica.notification.BookingReminderEmailExpectedCopy;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.ReminderKind;
import com.beautica.support.MutableTestClock;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.thymeleaf.spring6.SpringTemplateEngine;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static com.beautica.support.MimeMessageBodies.htmlBodyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 494 (NTF-47) — registered (APP) clients get a reminder e-mail 24 h and 2 h before each booking, through the
 * real sweeps, the real outbox drain and the real {@code email/booking-reminder} template over a mocked SMTP
 * transport; never an SMS (NTF-D04). Guest reminders (WireMock Turbosms) ride the same sweeps unchanged.
 *
 * <p>Same {@code @TestPropertySource} + clock {@code @Import} as {@link GuestTwoHourReminderIT}, so the three
 * reminder ITs share one cached context. The clock is pinned to 12:00 Kyiv on the real current date (the base
 * fixture seeds the master's schedule from the real date).
 */
@TestPropertySource(properties = {"app.booking.sms.enabled=true", "app.booking.reminder-2h.enabled=true"})
@Import(GuestTwoHourReminderIT.ClockConfig.class)
@DisplayName("RegisteredBookingReminderIT — phase 494 registered-client reminder e-mails")
class RegisteredBookingReminderIT extends AbstractWalkInBookingSmsIT {

    private static final Instant NOW = LocalDate.now(TimeZones.KYIV)
            .atTime(12, 0).atZone(TimeZones.KYIV).toInstant();
    private static final String GUEST_REMINDER_MARKER = "Beautica: Нагадуємо, що ";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    /** Well under any lock wait the old blocking claim would sit in, well over a skip-locked sweep. */
    private static final Duration LOCK_WAIT_BUDGET = Duration.ofSeconds(10);

    @Autowired private BookingReminderJob reminderJob;
    @Autowired private BookingService bookingService;
    @Autowired private NotificationOutboxDrainWorker drainWorker;
    @Autowired private SpringTemplateEngine templateEngine;
    @Autowired private MutableTestClock clock;
    @Autowired private DataSource dataSource;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private RegisteredBookingReminderSweep registeredSweep;
    @Autowired private PlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    @Value("${app.invite.from-email:noreply@beautica.app}")
    private String fromAddress;

    private final List<MimeMessage> sentMails = new ArrayList<>();
    private SeededUser client;

    @BeforeEach
    void wireRealReminderEmailAndClient() {
        clock.set(NOW);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        Session session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));
        doAnswer(inv -> sentMails.add(inv.getArgument(0))).when(mailSender).send(any(MimeMessage.class));
        EmailNotificationService real = new EmailNotificationService(mailSender, templateEngine, fromAddress);
        doAnswer(inv -> {
            real.sendBookingReminderEmail(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(emailNotificationService).sendBookingReminderEmail(anyString(), any(), any());
        client = insertUser("CLIENT", null);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void restoreClock() {
        clock.set(NOW);
    }

    @Test
    @DisplayName("APP booking entering the 24 h window → exactly one 24 h e-mail to the client, flag set, no SMS")
    void should_emailClientOnce_when_appBookingEnters24hWindow() throws Exception {
        // Arrange
        OffsetDateTime start = at(NOW.plus(Duration.ofHours(24)));
        UUID bookingId = insertAppBooking(start, NOW.minus(Duration.ofDays(2)));

        // Act
        reminderJob.sendReminders();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).singleElement().satisfies(mail -> {
            assertThat(mail.getSubject()).isEqualTo(BookingReminderEmailExpectedCopy.subject(ReminderKind.H24, kyiv(start)));
            assertThat(mail.getAllRecipients()).extracting(Object::toString).containsExactly(client.email());
            assertThat(htmlBodyOf(mail)).contains(BookingReminderEmailExpectedCopy.leadLine(ReminderKind.H24),
                    SERVICE_NAME, MASTER_NAME, fixtureSalonName(salon.salonId()));
        });
        assertThat(flag("reminder_sent", bookingId)).isTrue();
        assertThat(reminderOutboxStatuses()).containsExactly("SENT");
        assertThat(sentTexts()).as("NTF-D04: never an SMS to a registered client").isEmpty();
    }

    @Test
    @DisplayName("the 24 h sweep running again (next hour, same window) never e-mails twice")
    void should_notEmailTwice_when_sweepRunsAgain() {
        // Arrange
        insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));

        // Act
        reminderJob.sendReminders();
        drainWorker.drain();
        clock.set(NOW.plus(Duration.ofMinutes(30)));
        reminderJob.sendReminders();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).hasSize(1);
        assertThat(reminderOutboxStatuses()).hasSize(1);
    }

    @Test
    @DisplayName("daytime, flag on, booked ≥3 h ahead → one 2 h e-mail with the «сьогодні» copy")
    void should_email2h_when_flagOnAndDaytime() throws Exception {
        // Arrange
        OffsetDateTime start = at(NOW.plus(Duration.ofMinutes(110)));
        UUID bookingId = insertAppBooking(start, NOW.minus(Duration.ofDays(1)));

        // Act
        reminderJob.sweepTwoHour();
        reminderJob.sweepTwoHour();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).singleElement().satisfies(mail -> {
            assertThat(mail.getSubject()).isEqualTo(BookingReminderEmailExpectedCopy.subject(ReminderKind.H2, kyiv(start)));
            assertThat(htmlBodyOf(mail)).contains(BookingReminderEmailExpectedCopy.leadLine(ReminderKind.H2));
        });
        assertThat(flag("reminder_2h_sent", bookingId)).isTrue();
        assertThat(sentTexts()).isEmpty();
    }

    @Test
    @DisplayName("booked less than 3 h before the start → no 2 h e-mail, flag untouched")
    void should_skip2h_when_bookedWithin3h() {
        // Arrange
        OffsetDateTime start = at(NOW.plus(Duration.ofMinutes(110)));
        UUID bookingId = insertAppBooking(start, NOW.minus(Duration.ofMinutes(30)));

        // Act
        reminderJob.sweepTwoHour();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).isEmpty();
        assertThat(reminderOutboxStatuses()).isEmpty();
        assertThat(flag("reminder_2h_sent", bookingId)).isFalse();
    }

    @Test
    @DisplayName("Kyiv night (22:00) → no 2 h e-mail, flag untouched")
    void should_skip2h_when_kyivNight() {
        // Arrange
        Instant night = NOW.plus(Duration.ofHours(10)); // 22:00 Kyiv
        clock.set(night);
        UUID bookingId = insertAppBooking(at(night.plus(Duration.ofMinutes(110))), NOW.minus(Duration.ofDays(1)));

        // Act
        reminderJob.sweepTwoHour();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).isEmpty();
        assertThat(flag("reminder_2h_sent", bookingId)).isFalse();
    }

    @Test
    @DisplayName("cancelled between the claim and the drain → no e-mail, outbox row SENT")
    void should_sendNothingAndMarkSent_when_cancelledBeforeDrain() {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED', cancellation_reason = 'CLIENT_CANCELLED' "
                + "WHERE id = ?", bookingId);

        // Act
        drainWorker.drain();

        // Assert
        assertThat(sentMails).isEmpty();
        assertThat(reminderOutboxStatuses()).containsExactly("SENT");
    }

    @Test
    @DisplayName("client account deactivated before the drain → no e-mail, outbox row SENT")
    void should_sendNothingAndMarkSent_when_clientInactive() {
        // Arrange
        insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE id = ?", client.id());

        // Act
        drainWorker.drain();

        // Assert
        assertThat(sentMails).isEmpty();
        assertThat(reminderOutboxStatuses()).containsExactly("SENT");
    }

    @Test
    @DisplayName("24 h e-mail sent, provider moves the booking +2 days → a second 24 h e-mail for the new time (B493)")
    void should_sendAgain_when_rescheduledAfterReminder() throws Exception {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        drainWorker.drain();
        OffsetDateTime newStart = at(NOW.plus(Duration.ofDays(3)));
        bookingService.rescheduleBooking(salon.ownerId(), Role.SALON_OWNER, bookingId,
                new RescheduleBookingRequest(newStart, false));
        assertThat(flag("reminder_sent", bookingId)).as("re-armed by the reschedule").isFalse();
        sentMails.clear();

        // Act
        clock.set(NOW.plus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        reminderJob.sendReminders();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).singleElement().satisfies(mail -> assertThat(mail.getSubject())
                .isEqualTo(BookingReminderEmailExpectedCopy.subject(ReminderKind.H24, kyiv(newStart))));
        assertThat(flag("reminder_sent", bookingId)).isTrue();
    }

    @Test
    @DisplayName("mixed batch: the guest still gets exactly its SMS reminder, the APP client only its e-mail")
    void should_leaveGuestRemindersUnchanged_when_mixedBatch() throws Exception {
        // Arrange
        UUID guestId = insertGuestLinkBooking(LINK_PHONE, at(NOW.plus(Duration.ofHours(24))), Duration.ofMinutes(10),
                NOW.minus(Duration.ofDays(2)));
        UUID appId = insertAppBooking(at(NOW.plus(Duration.ofMinutes(23 * 60 + 15))), NOW.minus(Duration.ofDays(2)));

        // Act
        reminderJob.sendReminders();
        drainWorker.drain();

        // Assert
        assertThat(sentTexts()).singleElement().satisfies(text -> assertThat(text).startsWith(GUEST_REMINDER_MARKER));
        assertThat(sentMails).singleElement().satisfies(mail -> assertThat(mail.getAllRecipients())
                .extracting(Object::toString).containsExactly(client.email()));
        assertThat(flag("reminder_sent", guestId)).isTrue();
        assertThat(flag("reminder_sent", appId)).isTrue();
        assertThat(jdbcTemplate.queryForList("SELECT aggregate_id FROM notification_outbox "
                + "WHERE event_type = 'BOOKING_REMINDER'", UUID.class)).containsExactly(appId);
    }

    // ── QA additions (backend-qa, phase 494) ──────────────────────────────────

    @Test
    @DisplayName("24 h window is [now+23h, now+25h]: both bounds e-mailed, one minute outside either bound never claimed")
    void should_claimOnlyInsideWindow_when_bookingsStraddleBothBounds() throws Exception {
        // Arrange
        Duration oneMinute = Duration.ofMinutes(1);
        Instant booked = NOW.minus(Duration.ofDays(2));
        OffsetDateTime lowerBound = at(NOW.plus(Duration.ofHours(23)));
        OffsetDateTime upperBound = at(NOW.plus(Duration.ofHours(25)));
        UUID tooEarly = insertAppBooking(lowerBound.minus(oneMinute), booked, oneMinute, null);
        UUID onLower = insertAppBooking(lowerBound, booked, oneMinute, null);
        UUID onUpper = insertAppBooking(upperBound, booked, oneMinute, null);
        UUID tooLate = insertAppBooking(upperBound.plus(oneMinute), booked, oneMinute, null);

        // Act
        reminderJob.sendReminders();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).extracting(MimeMessage::getSubject).containsExactlyInAnyOrder(
                BookingReminderEmailExpectedCopy.subject(ReminderKind.H24, kyiv(lowerBound)),
                BookingReminderEmailExpectedCopy.subject(ReminderKind.H24, kyiv(upperBound)));
        assertThat(flag("reminder_sent", onLower)).as("lower bound inclusive").isTrue();
        assertThat(flag("reminder_sent", onUpper)).as("upper bound inclusive").isTrue();
        assertThat(flag("reminder_sent", tooEarly)).as("22h59 is outside the window").isFalse();
        assertThat(flag("reminder_sent", tooLate)).as("25h01 is outside the window").isFalse();
        assertThat(reminderOutboxAggregates()).containsExactlyInAnyOrder(onLower, onUpper);
    }

    @Test
    @DisplayName("NTF-D11: a two-service APP visit → one reminder e-mail per booking, never one per visit")
    void should_emailOncePerBooking_when_visitHasTwoBookings() {
        // Arrange
        UUID appointmentId = insertAppAppointment();
        OffsetDateTime first = at(NOW.plus(Duration.ofHours(24)));
        UUID firstItem = insertAppBooking(first, NOW.minus(Duration.ofDays(2)), Duration.ofMinutes(30), appointmentId);
        UUID secondItem = insertAppBooking(first.plusMinutes(30), NOW.minus(Duration.ofDays(2)), Duration.ofMinutes(30),
                appointmentId);

        // Act
        reminderJob.sendReminders();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).as("one e-mail per booking of the visit").hasSize(2);
        assertThat(reminderOutboxAggregates()).containsExactlyInAnyOrder(firstItem, secondItem);
        assertThat(sentTexts()).isEmpty();
    }

    @Test
    @DisplayName("an APP booking already CANCELLED / DECLINED at sweep time is never claimed nor enqueued")
    void should_notClaim_when_appBookingNotConfirmedAtSweep() {
        // Arrange
        UUID cancelled = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED', cancellation_reason = 'CLIENT_CANCELLED' "
                + "WHERE id = ?", cancelled);
        UUID declined = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        jdbcTemplate.update("UPDATE bookings SET status = 'DECLINED' WHERE id = ?", declined);

        // Act
        reminderJob.sendReminders();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).isEmpty();
        assertThat(reminderOutboxStatuses()).isEmpty();
        assertThat(flag("reminder_sent", cancelled)).isFalse();
        assertThat(flag("reminder_sent", declined)).isFalse();
    }

    @Test
    @DisplayName("client account deleted (booking detached) before the sweep → never claimed, no outbox row, no e-mail")
    void should_notClaim_when_clientDetachedBeforeSweep() {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        detachClientAndDeleteAccount(bookingId);

        // Act
        reminderJob.sendReminders();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).isEmpty();
        assertThat(reminderOutboxStatuses()).isEmpty();
        assertThat(flag("reminder_sent", bookingId)).isFalse();
        assertThat(sentTexts()).as("a detached booking's sentinel name never routes it to the guest SMS leg").isEmpty();
    }

    @Test
    @DisplayName("client account deleted (booking detached) between claim and drain → no e-mail, outbox row SENT")
    void should_sendNothingAndMarkSent_when_clientDeletedBeforeDrain() {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        detachClientAndDeleteAccount(bookingId);

        // Act
        drainWorker.drain();

        // Assert
        assertThat(sentMails).isEmpty();
        assertThat(reminderOutboxStatuses()).containsExactly("SENT");
    }

    @Test
    @DisplayName("rescheduled between claim and drain → no e-mail for the old time (row SENT), then one for the new time")
    void should_skipOldTimeAndRemindNewTime_when_rescheduledBetweenClaimAndDrain() throws Exception {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        OffsetDateTime newStart = at(NOW.plus(Duration.ofDays(3)));
        bookingService.rescheduleBooking(salon.ownerId(), Role.SALON_OWNER, bookingId,
                new RescheduleBookingRequest(newStart, false));

        // Act
        drainWorker.drain();

        // Assert
        assertThat(sentMails).as("the claimed row's start moved out of its window").isEmpty();
        assertThat(reminderOutboxStatuses()).containsExactly("SENT");

        // Act — the re-armed flag lets the sweep two days later remind about the new time
        clock.set(NOW.plus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).singleElement().satisfies(mail -> assertThat(mail.getSubject())
                .isEqualTo(BookingReminderEmailExpectedCopy.subject(ReminderKind.H24, kyiv(newStart))));
        assertThat(reminderOutboxStatuses()).containsExactly("SENT", "SENT");
    }

    @Test
    @DisplayName("2 h e-mail sent, booking moved a day later → re-armed, a second 2 h e-mail for the new time (B493)")
    void should_sendTwoHourAgain_when_rescheduledAfterTwoHourReminder() throws Exception {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofMinutes(110))), NOW.minus(Duration.ofDays(2)));
        reminderJob.sweepTwoHour();
        drainWorker.drain();
        assertThat(sentMails).hasSize(1);
        OffsetDateTime newStart = at(NOW.plus(Duration.ofDays(1)).plus(Duration.ofHours(2))); // 14:00 Kyiv, on-grid
        bookingService.rescheduleBooking(salon.ownerId(), Role.SALON_OWNER, bookingId,
                new RescheduleBookingRequest(newStart, false));
        assertThat(flag("reminder_2h_sent", bookingId)).as("re-armed by the reschedule").isFalse();
        sentMails.clear();

        // Act
        clock.set(NOW.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(10))); // start is now+110 min
        reminderJob.sweepTwoHour();
        reminderJob.sweepTwoHour();
        drainWorker.drain();

        // Assert
        assertThat(sentMails).singleElement().satisfies(mail -> assertThat(mail.getSubject())
                .isEqualTo(BookingReminderEmailExpectedCopy.subject(ReminderKind.H2, kyiv(newStart))));
        assertThat(flag("reminder_2h_sent", bookingId)).isTrue();
        assertThat(sentTexts()).isEmpty();
    }

    @Test
    @DisplayName("audit-fix: a 24 h booking row locked by another transaction is skipped (no wait), the rest are "
            + "claimed, and the skipped row is claimed on the next tick once released")
    void should_skipLockedRowAndClaimRest_when_dayBeforeBookingRowLocked() throws Exception {
        // Arrange
        UUID locked = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        UUID free = insertAppBooking(at(NOW.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(30))),
                NOW.minus(Duration.ofDays(2)));

        // Act — another transaction holds the row lock for the whole sweep
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM bookings WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, locked);
                lock.executeQuery().close();
            }
            assertTimeoutPreemptively(LOCK_WAIT_BUDGET, () -> reminderJob.sendReminders(),
                    "the 24 h claim must skip a locked row, never wait on it");
            holder.rollback();
        }

        // Assert
        assertThat(reminderOutboxAggregates()).containsExactly(free);
        assertThat(flag("reminder_sent", locked)).isFalse();
        assertThat(flag("reminder_sent", free)).isTrue();

        // Act — next tick, lock released
        clock.set(NOW.plus(Duration.ofMinutes(30)));
        reminderJob.sendReminders();

        // Assert
        assertThat(reminderOutboxAggregates()).containsExactlyInAnyOrder(free, locked);
        assertThat(flag("reminder_sent", locked)).isTrue();
    }

    @Test
    @DisplayName("audit-fix cycle 2: the registered claims' 3s lock_timeout is scoped to the claim — the transaction's "
            + "previous lock_timeout is back in force for the guest leg that follows (24 h and 2 h)")
    void should_restorePreviousLockTimeout_when_registeredClaimsRunBeforeGuestLeg() {
        // Arrange — one due row per leg; a sentinel lock_timeout distinct from both the session default and 3s
        UUID dayBefore = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        UUID twoHour = insertAppBooking(at(NOW.plus(Duration.ofHours(2))), NOW.minus(Duration.ofDays(2)));

        // Act — the same transaction the guest leg would continue in
        List<String> observed = transactionTemplate.execute(tx -> {
            jdbcTemplate.queryForObject("SELECT set_config('lock_timeout', '7s', true)", String.class);
            List<String> settings = new ArrayList<>();
            settings.add(String.valueOf(registeredSweep.remindDayBefore(at(NOW))));
            settings.add(jdbcTemplate.queryForObject("SELECT current_setting('lock_timeout')", String.class));
            settings.add(String.valueOf(registeredSweep.remindTwoHoursBefore(at(NOW), Duration.ofHours(3))));
            settings.add(jdbcTemplate.queryForObject("SELECT current_setting('lock_timeout')", String.class));
            return settings;
        });

        // Assert — each leg claimed its row (so the 3s really was applied), and 7s survived both claims
        assertThat(observed).containsExactly("1", "7s", "1", "7s");
        assertThat(flag("reminder_sent", dayBefore)).isTrue();
        assertThat(flag("reminder_2h_sent", twoHour)).isTrue();
    }

    @Test
    @DisplayName("audit-fix: the 24 h finder applies its Limit in SQL, earliest first; the overflow stays unclaimed and "
            + "is selected by the next call")
    void should_capDayBeforeFinderAndLeaveOverflowForNextTick_when_moreDueThanLimit() {
        // Arrange — a cap of 1 stands in for REGISTERED_DAY_BEFORE_REMINDER_BATCH_CAP (the sweep passes the constant;
        // BookingReminderJobTest pins that), so the test needs two rows, not 501
        UUID first = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        UUID overflow = insertAppBooking(at(NOW.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(30))),
                NOW.minus(Duration.ofDays(2)));
        OffsetDateTime from = at(NOW.plus(ReminderKind.H24.windowStart()));
        OffsetDateTime to = at(NOW.plus(ReminderKind.H24.windowEnd()));

        // Act
        List<UUID> tick1 = transactionTemplate.execute(tx -> bookingRepository.claimRegisteredRemindersByIds(
                ids(bookingRepository.findRegisteredBookingsForReminder(from, to, Limit.of(1)))));
        List<UUID> tick2 = transactionTemplate.execute(tx -> bookingRepository.claimRegisteredRemindersByIds(
                ids(bookingRepository.findRegisteredBookingsForReminder(from, to, Limit.of(1)))));

        // Assert
        assertThat(tick1).containsExactly(first);
        assertThat(tick2).containsExactly(overflow);
        assertThat(flag("reminder_sent", overflow)).isTrue();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** A CONFIRMED APP booking of {@link #client} against the fixture salon master, 30 min, explicit created_at. */
    private UUID insertAppBooking(OffsetDateTime startsAt, Instant createdAt) {
        return insertAppBooking(startsAt, createdAt, Duration.ofMinutes(30), null);
    }

    /** As {@link #insertAppBooking(OffsetDateTime, Instant)} with an explicit length and optional visit header. */
    private UUID insertAppBooking(OffsetDateTime startsAt, Instant createdAt, Duration length, UUID appointmentId) {
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, client_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, appointment_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, ?, 0, 'APP', ?, ?, ?)",
                bookingId, salon.masterId(), salon.masterServiceId(), salon.salonId(), client.id(), startsAt,
                startsAt.plus(length), (int) length.toMinutes(), appointmentId, created, created);
        return bookingId;
    }

    /** A CONFIRMED APP visit header for {@link #client}; items attach through {@code bookings.appointment_id}. */
    private UUID insertAppAppointment() {
        UUID appointmentId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC);
        jdbcTemplate.update("INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'CONFIRMED', 'APP', ?, ?)",
                appointmentId, client.id(), salon.salonId(), created, created);
        return appointmentId;
    }

    /**
     * The account self-delete's documented detach (V162 order of operations): one UPDATE nulls {@code client_id},
     * writes the sentinel name and stamps {@code client_detached_at}, then the users row is deleted.
     */
    private void detachClientAndDeleteAccount(UUID bookingId) {
        jdbcTemplate.update("UPDATE bookings SET client_id = NULL, guest_name = 'Видалений клієнт', "
                + "guest_surname = NULL, client_detached_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), bookingId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", client.id());
    }

    private List<UUID> reminderOutboxAggregates() {
        return jdbcTemplate.queryForList(
                "SELECT aggregate_id FROM notification_outbox WHERE event_type = 'BOOKING_REMINDER'", UUID.class);
    }

    private List<String> reminderOutboxStatuses() {
        return jdbcTemplate.queryForList(
                "SELECT status FROM notification_outbox WHERE event_type = 'BOOKING_REMINDER'", String.class);
    }

    private static List<UUID> ids(List<BookingRepository.RegisteredReminderCandidate> candidates) {
        return candidates.stream().map(BookingRepository.RegisteredReminderCandidate::getId).toList();
    }

    private boolean flag(String column, UUID bookingId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM bookings WHERE id = ?", Boolean.class, bookingId));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String kyiv(OffsetDateTime start) {
        return start.atZoneSameInstant(TimeZones.KYIV).format(HH_MM);
    }
}
