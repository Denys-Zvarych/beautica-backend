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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hourly sweep that sends a 24h reminder SMS for upcoming guest (LINK) bookings
 * (Phase 13.3). Runs at {@code :00} every hour and picks up every confirmed guest
 * booking starting in the 23–25h window that has not yet been reminded, marking
 * each {@code reminderSent = true} so it is never re-sent.
 *
 * <p>Time is read from the injected {@link Clock} (never {@code Instant.now()}) so
 * tests can pin the window (Anti-Bug §G). {@code @EnableScheduling} lives in
 * {@code SchedulingConfig}.
 *
 * <p><b>Multi-service visit dedup (BE-7).</b> A guest multi-service visit persists N chained item rows
 * that share one {@code appointment_id}; without grouping, all N would be due in the same sweep and the
 * guest would receive N identical reminders. The sweep therefore sends EXACTLY ONE reminder per visit
 * (its earliest item) while marking EVERY item {@code reminderSent = true} so the visit never re-reminds.
 * Legacy single guest bookings ({@code appointment_id} NULL) each get their own reminder, unchanged. The
 * reminder query fetch-joins the appointment header, so grouping reads its id with no extra query / N+1.
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

        // Group the visit's item rows by appointment_id (BE-7) so a multi-service visit gets ONE reminder;
        // legacy single guest bookings (appointment_id NULL) stay individual. getAppointment() is served by
        // the query's LEFT JOIN FETCH (no lazy load); a null header means a legacy single booking.
        Map<UUID, List<Booking>> visits = new LinkedHashMap<>();
        List<Booking> singles = new ArrayList<>();
        for (Booking booking : due) {
            UUID appointmentId = booking.getAppointment() == null ? null : booking.getAppointment().getId();
            if (appointmentId == null) {
                singles.add(booking);
            } else {
                visits.computeIfAbsent(appointmentId, k -> new ArrayList<>()).add(booking);
            }
        }

        for (Booking booking : singles) {
            sendReminderSafely(booking);
            booking.setReminderSent(true);
        }
        bookingRepository.saveAll(singles);

        for (List<Booking> items : visits.values()) {
            // Remind on the earliest item; the SMS renders that item's service/time as the visit summary.
            items.sort(Comparator.comparing(Booking::getStartsAt));
            sendReminderSafely(items.get(0));
        }
        if (!visits.isEmpty()) {
            // Mark EVERY item of each reminded visit — including any tail item whose own startsAt sits
            // beyond this sweep's [from, to] window (a visit can span up to 10h, far past the 2h window)
            // so a later sweep can never re-remind the visit's tail. One bulk UPDATE, no extra load.
            bookingRepository.markVisitRemindersSentByAppointmentIds(visits.keySet());
        }
        log.info("Guest booking reminders processed: {} rows across {} visits and {} single bookings",
                due.size(), visits.size(), singles.size());
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
