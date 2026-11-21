package com.beautica.booking;

import com.beautica.booking.job.BookingReminderJob;
import com.beautica.notification.sms.SmsService;
import com.beautica.support.MutableTestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B527 (NTF-67, case 19) — a future CONFIRMED guest / walk-in booking that was attached to a client account gets the
 * REGISTERED reminders (in-app 24 h and 2 h feed rows for the client) and no SMS: the guest sweeps need a
 * {@code guest_phone}, which the attach cleared, and the registered sweeps now read attached rows.
 */
@TestPropertySource(properties = {"app.booking.sms.enabled=true", "app.booking.reminder-2h.enabled=true"})
@Import(GuestTwoHourReminderIT.ClockConfig.class)
@DisplayName("Attached bookings get registered reminders — B527")
class ClientPhoneAttachReminderIT extends AbstractStaffBookingIT {

    private static final Instant NOW = java.time.LocalDate.now(com.beautica.common.TimeZones.KYIV)
            .atTime(12, 0).atZone(com.beautica.common.TimeZones.KYIV).toInstant();

    @Autowired private BookingReminderJob reminderJob;
    @Autowired private MutableTestClock clock;
    @MockBean private SmsService smsService;

    private SeededUser client;

    @BeforeEach
    void seedClient() {
        clock.set(NOW);
        client = insertUser("CLIENT", null);
    }

    @AfterEach
    void restoreClock() {
        clock.set(NOW);
    }

    /** A CONFIRMED attached booking ({@code source}) — client set, guest_phone cleared, the link kept. */
    private UUID attachedBooking(String source, OffsetDateTime startsAt, int minutes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, client_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, cancel_token_hash, client_linked_at, "
                        + "client_link_reason, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, ?, 0, ?, 'Іванна', 'Гість', ?, NOW(), "
                        + "'PHONE_VERIFIED', ?, ?)",
                id, salon.masterId(), salon.masterServiceId(), salon.salonId(), client.id(), startsAt,
                startsAt.plusMinutes(minutes), minutes, source,
                "LINK".equals(source) ? id.toString().replace("-", "").repeat(2) : null,
                OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC));
        return id;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("19: attached LINK + STAFF bookings get the in-app 24 h and 2 h reminders for the client, and no SMS")
    void should_sendRegisteredRemindersInApp_when_bookingIsAttached() {
        UUID day = attachedBooking("LINK", at(NOW.plus(Duration.ofHours(24))), 10);
        UUID twoHour = attachedBooking("STAFF", at(NOW.plus(Duration.ofMinutes(110))), 10);

        reminderJob.sendReminders();
        reminderJob.sweepTwoHour();

        List<UUID> dayRows = jdbcTemplate.queryForList("SELECT booking_id FROM in_app_notification "
                + "WHERE type = 'BOOKING_REMINDER' AND recipient_user_id = ? AND dedup_key NOT LIKE '%:H2'",
                UUID.class, client.id());
        List<UUID> h2Rows = jdbcTemplate.queryForList("SELECT booking_id FROM in_app_notification "
                + "WHERE type = 'BOOKING_REMINDER' AND recipient_user_id = ? AND dedup_key LIKE '%:H2'",
                UUID.class, client.id());
        assertThat(dayRows).as("24 h reminder").contains(day);
        assertThat(h2Rows).as("2 h reminder").containsExactly(twoHour);
        verify(smsService, never()).send(any(), any());
    }
}
