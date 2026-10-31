package com.beautica.booking.job;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.domain.GuestSmsServiceName;
import com.beautica.booking.event.GuestRemindersDueEvent;
import com.beautica.booking.event.GuestRemindersDueEvent.GuestReminderSms;
import com.beautica.booking.event.GuestRemindersDueEvent.ReminderType;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.TimeZones;
import com.beautica.common.util.Placeholders;
import com.beautica.config.BookingSmsProperties;
import com.beautica.review.invite.config.ReviewInviteProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Phase 413 D1 — 2h sweep window {@code [now+1h45, now+2h15)}, ticked every 15 min. */
    static final Duration TWO_HOUR_WINDOW_START = Duration.ofMinutes(105);
    static final Duration TWO_HOUR_WINDOW_END = Duration.ofMinutes(135);
    /** Phase 413 D2 — Kyiv night: no 2h reminder is selected, claimed or sent from 21:00 to 09:00. */
    static final LocalTime NIGHT_START = LocalTime.of(21, 0);
    static final LocalTime NIGHT_END = LocalTime.of(9, 0);

    static final String REMINDER_METRIC = "beautica.guest_reminder";
    static final String OUTCOME_SENT = "sent";
    static final String OUTCOME_SKIPPED_NIGHT = "skipped_night";
    static final String OUTCOME_SKIPPED_SHORT_NOTICE = "skipped_short_notice";
    private static final String TYPE_H2 = "h2";

    private final BookingRepository bookingRepository;
    private final BookingSmsProperties smsProperties;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public BookingReminderJob(
            BookingRepository bookingRepository,
            BookingSmsProperties smsProperties,
            ApplicationEventPublisher events,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.smsProperties = smsProperties;
        this.events = events;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void sendReminders() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime from = now.plus(WINDOW_START);
        OffsetDateTime to = now.plus(WINDOW_END);

        List<Booking> due = bookingRepository.findGuestBookingsForReminder(from, to, includeWalkIns());
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

        // CLAIM before rendering/sending (instance-overlap guard): the conditional UPDATE ... RETURNING flips
        // reminder_sent only where it is still false, so when two instances run this sweep during a rolling
        // deploy, exactly one wins each row and the loser reminds nobody. Only claimed bookings/visits are
        // reminded below. The flag is committed with this transaction, i.e. before the after-commit dispatch
        // (at-most-once, see GuestReminderDispatcher) — unchanged.
        if (!singles.isEmpty()) {
            Set<UUID> claimed = new HashSet<>(bookingRepository.claimRemindersByIds(
                    singles.stream().map(Booking::getId).toList()));
            singles.removeIf(booking -> !claimed.contains(booking.getId()));
        }
        if (!visits.isEmpty()) {
            // The claim also flips EVERY item of each visit — including any tail item whose own startsAt sits
            // beyond this sweep's [from, to] window (a visit can span up to 10h, far past the 2h window) —
            // so a later sweep can never re-remind the visit's tail. One statement, no extra load.
            Set<UUID> claimedVisits = new HashSet<>(
                    bookingRepository.claimVisitRemindersByAppointmentIds(visits.keySet()));
            visits.keySet().removeIf(appointmentId -> !claimedVisits.contains(appointmentId));
        }

        // Render every message HERE, while the Hibernate session is still open, and collect them —
        // buildReminderSms walks two lazy association chains (masterService → serviceDefinition, master →
        // user), so it cannot run in the after-commit dispatcher. Only Strings cross the boundary.
        List<GuestReminderSms> reminders = new ArrayList<>();

        for (Booking booking : singles) {
            collectReminder(reminders, booking);
        }
        // No setReminderSent(true) / saveAll: the native claim above (claimRemindersByIds) already
        // persisted reminder_sent = TRUE for exactly these rows, as the visit claim does for visits. The
        // in-memory flag is left stale on purpose (nothing later in this transaction reads it), which keeps
        // Hibernate's dirty check from issuing a redundant UPDATE per row at commit.

        for (List<Booking> items : visits.values()) {
            // Remind on the earliest item; the SMS renders that item's service/time as the visit summary.
            items.sort(Comparator.comparing(Booking::getStartsAt));
            collectReminder(reminders, items.get(0));
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
     * Phase 413 — the 2h-before guest reminder, every 15 min (at :05/:20/:35/:50), at most
     * {@link BookingRepository#TWO_HOUR_REMINDER_BATCH_CAP} rows per tick. Same shape as {@link #sendReminders()}: select,
     * group per visit (BE-7: one reminder per visit, on its earliest due item), CLAIM ({@code reminder_2h_sent},
     * conditional {@code UPDATE ... RETURNING}, so overlapping instances remind once), render in-transaction and
     * publish one {@link GuestRemindersDueEvent} ({@link ReminderType#H2}) delivered after commit by
     * {@code GuestReminderDispatcher} — Telegram when linked, else SMS (phase 408). No network I/O here.
     *
     * <ul>
     *   <li>D6 — flag {@code app.booking.reminder-2h.enabled} off: returns before any query.</li>
     *   <li>D2 — Kyiv night (21:00–09:00): returns without selecting or claiming. Skip, never defer: a visit whose
     *       whole window passes during the night is never 2h-reminded. Applies to both channels (408 D5).</li>
     *   <li>D3 — short notice: a booking (visit lead) created less than {@code min-lead} before its start is not
     *       reminded and not claimed (re-evaluated, identically, on the window's second tick).</li>
     * </ul>
     */
    // Offset to :05/:20/:35/:50 (audit-fix) so it never fires together with the hourly :00 24h sweep and the other
    // top-of-hour jobs; the 30-minute window still covers every start at two ticks.
    @Scheduled(cron = "0 5/15 * * * *")
    @Transactional
    public void sweepTwoHour() {
        BookingSmsProperties.Reminder2h config = smsProperties.getReminder2h();
        if (!config.isEnabled()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (isKyivNight(now.toInstant())) {
            countTwoHour(OUTCOME_SKIPPED_NIGHT, 1);
            return;
        }
        List<Booking> due = bookingRepository.findGuestBookingsForTwoHourReminder(
                now.plus(TWO_HOUR_WINDOW_START), now.plus(TWO_HOUR_WINDOW_END), includeWalkIns(),
                Limit.of(BookingRepository.TWO_HOUR_REMINDER_BATCH_CAP));
        if (due.isEmpty()) {
            return;
        }
        List<Booking> leads = claimTwoHourLeads(due, config.getMinLead());
        List<GuestReminderSms> reminders = new ArrayList<>();
        for (Booking lead : leads) {
            collectReminder(reminders, lead, smsProperties.getSms().getReminder2h());
        }
        if (!reminders.isEmpty()) {
            events.publishEvent(new GuestRemindersDueEvent(ReminderType.H2, reminders));
        }
        countTwoHour(OUTCOME_SENT, reminders.size());
        log.info("Guest 2h reminders: {} dispatched from {} due rows", reminders.size(), due.size());
    }

    /**
     * Phase 413 D4 (after 412) — STAFF walk-ins with a guest phone join BOTH sweeps only while the walk-in SMS gate
     * ({@code app.booking.sms.enabled}, phase 254) is on. Off → LINK guests only, exactly the pre-412 selection, so a
     * Telegram-routed (408) walk-in reminder never goes out while walk-in SMS is still dark.
     */
    private boolean includeWalkIns() {
        return smsProperties.getSms().isEnabled();
    }

    /** D2 — true from 21:00 (inclusive) to 09:00 (exclusive) Kyiv wall-clock time, DST-aware. */
    static boolean isKyivNight(Instant instant) {
        return ReviewInviteProperties.isQuiet(instant.atZone(TimeZones.KYIV).toLocalTime(), NIGHT_START, NIGHT_END);
    }

    /**
     * Groups the due rows per visit (earliest due item leads), drops short-notice leads (D3), then claims the
     * rest. Returns only the leads this instance won.
     */
    private List<Booking> claimTwoHourLeads(List<Booking> due, Duration minLead) {
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
        visits.values().forEach(items -> items.sort(Comparator.comparing(Booking::getStartsAt)));
        int before = singles.size() + visits.size();
        singles.removeIf(booking -> isShortNotice(booking, minLead));
        visits.values().removeIf(items -> isShortNotice(items.get(0), minLead));
        countTwoHour(OUTCOME_SKIPPED_SHORT_NOTICE, before - singles.size() - visits.size());

        List<Booking> leads = new ArrayList<>();
        if (!singles.isEmpty()) {
            Set<UUID> claimed = new HashSet<>(bookingRepository.claimTwoHourRemindersByIds(
                    singles.stream().map(Booking::getId).toList()));
            singles.stream().filter(booking -> claimed.contains(booking.getId())).forEach(leads::add);
        }
        if (!visits.isEmpty()) {
            Set<UUID> claimed = new HashSet<>(
                    bookingRepository.claimTwoHourVisitRemindersByAppointmentIds(visits.keySet()));
            visits.forEach((appointmentId, items) -> {
                if (claimed.contains(appointmentId)) {
                    leads.add(items.get(0));
                }
            });
        }
        return leads;
    }

    /** D3 — created less than {@code minLead} before its start; a missing audit timestamp is never short notice. */
    private static boolean isShortNotice(Booking booking, Duration minLead) {
        Instant createdAt = booking.getCreatedAt();
        return createdAt != null && createdAt.isAfter(booking.getStartsAt().toInstant().minus(minLead));
    }

    private void countTwoHour(String outcome, int amount) {
        if (amount > 0) {
            meterRegistry.counter(REMINDER_METRIC, "type", TYPE_H2, "outcome", outcome).increment(amount);
        }
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
        collectReminder(reminders, booking, smsProperties.getSms().getReminder());
    }

    private void collectReminder(List<GuestReminderSms> reminders, Booking booking, String template) {
        // Phase 373 defensive guard: an anonymised guest row has guest_phone NULL. The sweep's
        // window (upcoming CONFIRMED) cannot reach one — retention needs ends_at > 12 months ago and
        // the erase cancels upcoming rows before wiping — but a null must never reach the SMS sender.
        if (booking.getGuestPhone() == null) {
            log.warn("Guest reminder skipped: booking {} has no guestPhone", booking.getId());
            return;
        }
        try {
            reminders.add(new GuestReminderSms(booking.getGuestPhone(), buildReminderSms(booking, template)));
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
    private String buildReminderSms(Booking booking, String template) {
        OffsetDateTime kyiv = booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        return Placeholders.format(template, Map.of(
                "serviceName", GuestSmsServiceName.of(booking), // 413 D4: platform name for walk-ins
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
