package com.beautica.booking;

import com.beautica.auth.Role;
import com.beautica.booking.dto.AppointmentItemRescheduleRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.job.BookingReminderJob;
import com.beautica.booking.service.AppointmentTransitionService;
import com.beautica.booking.service.BookingService;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.support.MutableTestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 493 (GST-77) — a booking moved to a new start gets its 24 h and 2 h reminders again for the new time,
 * through both real reschedule paths ({@code BookingService.rescheduleBooking},
 * {@code AppointmentTransitionService.rescheduleAppointmentItem}) and the real sweeps against WireMock Turbosms.
 *
 * <p>Same {@code @TestPropertySource} + clock {@code @Import} as {@link GuestTwoHourReminderIT}, so both share
 * one cached context. The clock is pinned to 12:00 Kyiv on the real current date (the base fixture seeds the
 * master's schedule from the real date).
 */
@TestPropertySource(properties = {"app.booking.sms.enabled=true", "app.booking.reminder-2h.enabled=true"})
@Import(GuestTwoHourReminderIT.ClockConfig.class)
@DisplayName("ReminderRearmAfterRescheduleIT — phase 493 reminders re-arm after a reschedule")
class ReminderRearmAfterRescheduleIT extends AbstractWalkInBookingSmsIT {

    private static final Instant NOW = LocalDate.now(TimeZones.KYIV)
            .atTime(12, 0).atZone(TimeZones.KYIV).toInstant();
    private static final String REMINDER_MARKER = "Beautica: Нагадуємо, що ";
    private static final String DAY_BEFORE_MARKER = "що завтра у Вас запис";
    private static final String SAME_DAY_MARKER = "що сьогодні у Вас запис";

    @Autowired private BookingReminderJob reminderJob;
    @Autowired private BookingService bookingService;
    @Autowired private AppointmentTransitionService appointmentTransitionService;
    @Autowired private MutableTestClock clock;

    @BeforeEach
    void pinClock() {
        clock.set(NOW);
    }

    @AfterEach
    void restoreClock() {
        clock.set(NOW);
    }

    @Test
    @DisplayName("24 h reminder sent, provider moves the booking +2 days → a second 24 h reminder for the new time")
    void should_sendSecond24hReminder_when_guestBookingRescheduledAfterReminder() throws Exception {
        UUID bookingId = insertLinkBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        assertThat(sentText()).contains(DAY_BEFORE_MARKER);
        OffsetDateTime newStart = at(NOW.plus(Duration.ofDays(3)));

        bookingService.rescheduleBooking(salon.ownerId(), Role.SALON_OWNER, bookingId,
                new RescheduleBookingRequest(newStart, false));
        assertThat(flag("reminder_sent", bookingId)).as("re-armed by the reschedule").isFalse();
        resetTurbosms();
        clock.set(NOW.plus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        reminderJob.sendReminders();

        assertThat(reminderTexts()).as("exactly one new 24 h reminder").hasSize(1)
                .allMatch(text -> text.contains(DAY_BEFORE_MARKER));
        assertThat(flag("reminder_sent", bookingId)).isTrue();
    }

    @Test
    @DisplayName("visit reminded, one item moved +2 days → one visit reminder for it; the sibling keeps its flag")
    void should_sendSecond24hReminder_when_visitItemRescheduled() throws Exception {
        UUID appointmentId = insertLinkVisit(at(NOW.plus(Duration.ofHours(24))), 2);
        reminderJob.sendReminders();
        assertThat(sentText()).contains(DAY_BEFORE_MARKER);
        List<UUID> items = visitItems(appointmentId);
        UUID moved = items.get(1);
        UUID sibling = items.get(0);

        asOwner(() -> appointmentTransitionService.rescheduleAppointmentItem(salon.ownerId(), Role.SALON_OWNER,
                appointmentId, moved, new AppointmentItemRescheduleRequest(at(NOW.plus(Duration.ofDays(3))), false)));
        assertThat(flag("reminder_sent", moved)).as("D2: only the moved item is re-armed").isFalse();
        assertThat(flag("reminder_sent", sibling)).isTrue();
        resetTurbosms();
        clock.set(NOW.plus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        reminderJob.sendReminders();

        assertThat(reminderTexts()).as("one reminder per visit").hasSize(1)
                .allMatch(text -> text.contains(DAY_BEFORE_MARKER));
        assertThat(flag("reminder_sent", moved)).isTrue();
    }

    @Test
    @DisplayName("visit 2 h-reminded, one item moved to tomorrow → one 2 h reminder for it tomorrow; sibling stays claimed")
    void should_resend2hReminderForMovedItemOnly_when_visitItemRescheduledAfter2hSent() throws Exception {
        UUID appointmentId = insertLinkVisit(at(NOW.plus(Duration.ofMinutes(110))), 2);
        reminderJob.sweepTwoHour();
        assertThat(sentText()).contains(SAME_DAY_MARKER);
        List<UUID> items = visitItems(appointmentId);
        UUID moved = items.get(1);
        UUID sibling = items.get(0);

        asOwner(() -> appointmentTransitionService.rescheduleAppointmentItem(salon.ownerId(), Role.SALON_OWNER,
                appointmentId, moved, new AppointmentItemRescheduleRequest(at(NOW.plus(Duration.ofHours(24))), false)));
        assertThat(flag("reminder_2h_sent", moved)).as("D2: the moved item's 2 h flag is re-armed").isFalse();
        assertThat(flag("reminder_sent", moved)).as("the moved item's 24 h flag is re-armed too").isFalse();
        assertThat(flag("reminder_2h_sent", sibling)).as("D2: the sibling keeps its 2 h flag").isTrue();
        resetTurbosms();
        clock.set(NOW.plus(Duration.ofHours(22))); // 10:00 Kyiv tomorrow — daytime, moved item at now+120m
        reminderJob.sweepTwoHour();
        reminderJob.sweepTwoHour();

        assertThat(reminderTexts()).as("one 2 h reminder for the visit, sent once").hasSize(1)
                .allMatch(text -> text.contains(SAME_DAY_MARKER));
        assertThat(flag("reminder_2h_sent", moved)).isTrue();
    }

    @Test
    @DisplayName("2 h reminder sent, booking moved to tomorrow → the 2 h reminder goes out again tomorrow")
    void should_resend2hReminder_when_rescheduledAfter2hSent() throws Exception {
        UUID bookingId = insertLinkBooking(at(NOW.plus(Duration.ofMinutes(110))), NOW.minus(Duration.ofDays(1)));
        reminderJob.sweepTwoHour();
        assertThat(sentText()).contains(SAME_DAY_MARKER);

        bookingService.rescheduleBooking(salon.ownerId(), Role.SALON_OWNER, bookingId,
                new RescheduleBookingRequest(at(NOW.plus(Duration.ofHours(24))), false));
        assertThat(flag("reminder_2h_sent", bookingId)).as("re-armed by the reschedule").isFalse();
        resetTurbosms();
        clock.set(NOW.plus(Duration.ofHours(22))); // 10:00 Kyiv tomorrow — daytime
        reminderJob.sweepTwoHour();
        reminderJob.sweepTwoHour();

        assertThat(reminderTexts()).as("exactly one new 2 h reminder").hasSize(1)
                .allMatch(text -> text.contains(SAME_DAY_MARKER));
        assertThat(flag("reminder_2h_sent", bookingId)).isTrue();
    }

    @Test
    @DisplayName("a same-start reschedule is refused (409) → both flags stay set, no repeat reminder")
    void should_keepFlagsAndNotResend_when_sameStartRescheduleRefused() {
        OffsetDateTime start = at(NOW.plus(Duration.ofHours(24)));
        UUID bookingId = insertLinkBooking(start, NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        jdbcTemplate.update("UPDATE bookings SET reminder_2h_sent = TRUE WHERE id = ?", bookingId);
        resetTurbosms();

        // The service path refuses a same-start move (the booking occupies its own slot → 409), so the entity's
        // "unchanged start keeps the flags" branch (BookingTest) is backed here by: nothing is written, nothing resent.
        assertThatThrownBy(() -> bookingService.rescheduleBooking(salon.ownerId(), Role.SALON_OWNER, bookingId,
                new RescheduleBookingRequest(start, false)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        reminderJob.sendReminders();

        assertThat(flag("reminder_sent", bookingId)).isTrue();
        assertThat(flag("reminder_2h_sent", bookingId)).isTrue();
        assertThat(reminderTexts()).as("no reminder after a same-start reschedule").isEmpty();
    }

    /** Reminder SMS only — a reschedule may also enqueue its own (non-reminder) guest SMS. */
    private List<String> reminderTexts() {
        return sentTexts().stream().filter(text -> text.startsWith(REMINDER_MARKER)).toList();
    }

    private boolean flag(String column, UUID bookingId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM bookings WHERE id = ?", Boolean.class, bookingId));
    }

    private List<UUID> visitItems(UUID appointmentId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at", UUID.class, appointmentId);
    }

    private UUID insertLinkBooking(OffsetDateTime startsAt, Instant createdAt) {
        return insertGuestLinkBooking(LINK_PHONE, startsAt, Duration.ofMinutes(10), createdAt);
    }

    private UUID insertLinkVisit(OffsetDateTime startsAt, int items) {
        UUID appointmentId = insertGuestLinkAppointment(LINK_PHONE, NOW.minus(Duration.ofDays(2)), NOW);
        for (int i = 0; i < items; i++) {
            UUID bookingId = insertLinkBooking(startsAt.plusMinutes(30L * i), NOW.minus(Duration.ofDays(2)));
            jdbcTemplate.update("UPDATE bookings SET appointment_id = ? WHERE id = ?", appointmentId, bookingId);
        }
        return appointmentId;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
