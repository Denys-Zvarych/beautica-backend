package com.beautica.booking.job;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.TimeZones;
import com.beautica.config.BookingSmsProperties;
import com.beautica.notification.sms.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Hourly sweep that sends a 24h reminder SMS for upcoming guest (LINK) bookings
 * (Phase 13.3). Runs at {@code :00} every hour and picks up every confirmed guest
 * booking starting in the 23–25h window that has not yet been reminded, marking
 * each {@code reminderSent = true} so it is never re-sent.
 *
 * <p>Time is read from the injected {@link Clock} (never {@code Instant.now()}) so
 * tests can pin the window (Anti-Bug §G). {@code @EnableScheduling} lives in
 * {@code SchedulingConfig}.
 */
@Component
@Slf4j
public class BookingReminderJob {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Duration WINDOW_START = Duration.ofHours(23);
    private static final Duration WINDOW_END = Duration.ofHours(25);

    private final BookingRepository bookingRepository;
    private final SmsService smsService;
    private final BookingSmsProperties smsProperties;
    private final Clock clock;

    public BookingReminderJob(
            BookingRepository bookingRepository,
            SmsService smsService,
            BookingSmsProperties smsProperties,
            Clock clock) {
        this.bookingRepository = bookingRepository;
        this.smsService = smsService;
        this.smsProperties = smsProperties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void sendReminders() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime from = now.plus(WINDOW_START);
        OffsetDateTime to = now.plus(WINDOW_END);

        List<Booking> due = bookingRepository.findGuestBookingsForReminder(from, to);
        if (due.isEmpty()) {
            return;
        }
        for (Booking booking : due) {
            sendReminderSafely(booking);
            booking.setReminderSent(true);
        }
        bookingRepository.saveAll(due);
        log.info("Guest booking reminders processed: {}", due.size());
    }

    private void sendReminderSafely(Booking booking) {
        try {
            smsService.send(booking.getGuestPhone(), buildReminderSms(booking));
        } catch (RuntimeException e) {
            // A provider failure for one recipient must not abort the whole sweep or the
            // reminderSent flush. Log the cause class only (never the phone or text).
            log.warn("Guest reminder SMS failed: {}", e.getClass().getSimpleName());
        }
    }

    private String buildReminderSms(Booking booking) {
        OffsetDateTime kyiv = booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        return smsProperties.getSms().getReminder()
                .replace("{serviceName}", booking.getMasterService().getServiceDefinition().getName())
                .replace("{masterName}", masterName(booking))
                .replace("{time}", TIME_FMT.format(kyiv));
    }

    private static String masterName(Booking booking) {
        var user = booking.getMaster().getUser();
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        return (first + " " + last).trim();
    }
}
