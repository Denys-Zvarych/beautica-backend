package com.beautica.booking.job;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.event.GuestRemindersDueEvent;
import com.beautica.booking.event.GuestRemindersDueEvent.GuestReminderSms;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.TimeZones;
import com.beautica.common.util.Placeholders;
import com.beautica.config.BookingSmsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
 *
 * <p><b>No SMS is sent from this transaction (backend-perf MEDIUM).</b> The sweep only selects, renders and
 * flags; the rendered batch is published as a {@link GuestRemindersDueEvent} and delivered by
 * {@code GuestReminderDispatcher} after commit, on {@code smsReminderExecutor}. Sending in-transaction made
 * the sweep's duration N × provider RTT (Turbosms: 3 s connect / 5 s read) while pinning a Hikari
 * connection — ~100 s at N=1000, far worse against a stalling provider, every hour on the hour. See that
 * class for the at-most-once rationale behind flagging BEFORE dispatch.
 */
@Component
@Slf4j
public class BookingReminderJob {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Duration WINDOW_START = Duration.ofHours(23);
    private static final Duration WINDOW_END = Duration.ofHours(25);

    private final BookingRepository bookingRepository;
    private final BookingSmsProperties smsProperties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public BookingReminderJob(
            BookingRepository bookingRepository,
            BookingSmsProperties smsProperties,
            ApplicationEventPublisher events,
            Clock clock) {
        this.bookingRepository = bookingRepository;
        this.smsProperties = smsProperties;
        this.events = events;
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

        // Render every message HERE, while the Hibernate session is still open, and collect them —
        // buildReminderSms walks two lazy association chains (masterService → serviceDefinition, master →
        // user), so it cannot run in the after-commit dispatcher. Only Strings cross the boundary.
        List<GuestReminderSms> reminders = new ArrayList<>();

        for (Booking booking : singles) {
            collectReminder(reminders, booking);
            booking.setReminderSent(true);
        }
        // No saveAll: these rows were loaded by findGuestBookingsForReminder INSIDE this transaction, so
        // they are managed and Hibernate's dirty check flushes reminderSent at commit regardless. The
        // explicit saveAll only added a merge pass over entities already in the persistence context.

        for (List<Booking> items : visits.values()) {
            // Remind on the earliest item; the SMS renders that item's service/time as the visit summary.
            items.sort(Comparator.comparing(Booking::getStartsAt));
            collectReminder(reminders, items.get(0));
        }
        if (!visits.isEmpty()) {
            // Mark EVERY item of each reminded visit — including any tail item whose own startsAt sits
            // beyond this sweep's [from, to] window (a visit can span up to 10h, far past the 2h window)
            // so a later sweep can never re-remind the visit's tail. One bulk UPDATE, no extra load.
            bookingRepository.markVisitRemindersSentByAppointmentIds(visits.keySet());
        }

        // Hand the batch to GuestReminderDispatcher, which fires only once THIS transaction commits and
        // immediately moves every blocking Turbosms call onto smsReminderExecutor. Nothing below this
        // point does network I/O, so the transaction now closes in query time rather than in N × provider
        // RTT (backend-perf MEDIUM). The flags above are committed by the same commit that releases the
        // event, giving at-most-once delivery: a crash in between loses a reminder, never duplicates one.
        if (!reminders.isEmpty()) {
            events.publishEvent(new GuestRemindersDueEvent(reminders));
        }
        // The DISPATCHED count leads, and is what an incident is read against. `due.size()` is a row
        // count inflated by BE-7 dedup (an N-item visit contributes N rows but ONE reminder), and a
        // render failure drops a reminder without changing it either — so a line reporting only rows
        // reads identically whether the batch went out whole or half of it never left. Pairing the two
        // makes a short batch visible in the log alone: dispatched < rows − (rows − visits − singles).
        log.info("Guest booking reminders: {} dispatched from {} due rows across {} visits and {} single bookings",
                reminders.size(), due.size(), visits.size(), singles.size());
    }

    /**
     * Renders one reminder into the outgoing batch. A render failure for one booking must not abort the
     * sweep or the {@code reminderSent} flush — same containment the old in-loop {@code sendReminderSafely}
     * gave, kept because {@code buildReminderSms} now runs inside the transaction. Logs the cause class
     * only (never the phone or the text).
     *
     * <p>The booking is still marked reminded when rendering fails: the cause is a deterministic data
     * problem that would fail identically on the next sweep, so retrying it hourly would only re-log.
     */
    private void collectReminder(List<GuestReminderSms> reminders, Booking booking) {
        try {
            reminders.add(new GuestReminderSms(booking.getGuestPhone(), buildReminderSms(booking)));
        } catch (RuntimeException e) {
            log.warn("Guest reminder SMS could not be rendered: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Renders the guest reminder SMS in ONE pass over the template — see
     * {@link Placeholders#format}.
     *
     * <p>Chained {@link String#replace} was a template-injection vector here: {@code {serviceName}}
     * was substituted BEFORE {@code {time}}, so the later {@code replace} re-scanned the service
     * name it had just written in. A provider who named a service {@code "Манікюр {time}"}
     * therefore got a second, fabricated time expanded inside a message the guest reads as platform
     * copy — SMS is a guest's only channel. This template carries no {@code {cancelUrl}}, so there
     * is no link to duplicate, but the layout-steering vector is the same. A single pass copies
     * substituted values out verbatim, so data can never become markup.
     */
    private String buildReminderSms(Booking booking) {
        OffsetDateTime kyiv = booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        return Placeholders.format(smsProperties.getSms().getReminder(), Map.of(
                "serviceName", booking.getMasterService().getServiceDefinition().getName(),
                "masterName", masterName(booking),
                "time", TIME_FMT.format(kyiv)));
    }

    // V157 / phase 294 D3: a reminder is sent about a HISTORICAL booking whose master may have been
    // detached (staff account hard-deleted), so the name comes from the accessor pair, which falls
    // back to the detach-time snapshot. Still null-safe on either half.
    private static String masterName(Booking booking) {
        String firstName = booking.getMaster().displayFirstName();
        String lastName = booking.getMaster().displayLastName();
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        return (first + " " + last).trim();
    }
}
