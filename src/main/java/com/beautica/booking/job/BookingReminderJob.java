package com.beautica.booking.job;

import com.beautica.booking.domain.GuestSmsProviderText;
import com.beautica.booking.domain.GuestSmsServiceName;
import com.beautica.booking.domain.GuestVisitAddress;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.event.GuestRemindersDueEvent.GuestReminderSms;
import com.beautica.booking.event.GuestRemindersDueEvent.ReminderType;
import com.beautica.booking.event.GuestRemindersDueEvent;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.service.GuestBookingPageLinks;
import com.beautica.common.TimeZones;
import com.beautica.common.util.Placeholders;
import com.beautica.config.BookingSmsProperties;
import com.beautica.notification.service.ReminderKind;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private static final String ADDRESS = "address";
    /** Phase 435 — template lines dropped whole when their value is absent. */
    private static final String SALON_ADDRESS = "salonAddress";
    /** Phase 435 — the guest's own booking page; its «Скасувати запис:» line is dropped when no link exists. */
    private static final String CANCEL_URL = "cancelUrl";
    private static final Set<String> OPTIONAL_PLACEHOLDERS = Set.of(ADDRESS, SALON_ADDRESS, CANCEL_URL);
    /** Audit-fix — the address placeholders, dropped first when the reminder is over the length cap. */
    private static final Set<String> ADDRESS_PLACEHOLDERS = Set.of(ADDRESS, SALON_ADDRESS);
    /** 24h window {@code [now+23h, now+25h]}; owned by {@link ReminderKind} so the registered leg's drain agrees. */
    private static final Duration WINDOW_START = ReminderKind.H24.windowStart();
    private static final Duration WINDOW_END = ReminderKind.H24.windowEnd();

    /** Phase 413 D1 — 2h sweep window {@code [now+1h45, now+2h15)}, ticked every 15 min. */
    static final Duration TWO_HOUR_WINDOW_START = ReminderKind.H2.windowStart();
    static final Duration TWO_HOUR_WINDOW_END = ReminderKind.H2.windowEnd();
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
    /** Phase 435 — the guest's own booking page for the «Скасувати запис:» line (dropped when absent). */
    private final GuestBookingPageLinks pageLinks;
    /** Phase 494 — the registered-client (APP) e-mail leg of both sweeps. */
    private final RegisteredBookingReminderSweep registeredReminders;

    public BookingReminderJob(
            BookingRepository bookingRepository,
            BookingSmsProperties smsProperties,
            ApplicationEventPublisher events,
            Clock clock,
            MeterRegistry meterRegistry,
            GuestBookingPageLinks pageLinks,
            RegisteredBookingReminderSweep registeredReminders) {
        this.bookingRepository = bookingRepository;
        this.smsProperties = smsProperties;
        this.events = events;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.pageLinks = pageLinks;
        this.registeredReminders = registeredReminders;
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void sendReminders() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime from = now.plus(WINDOW_START);
        OffsetDateTime to = now.plus(WINDOW_END);

        // Phase 494 — the registered-client e-mail leg first (same window), so the guest leg's early return on an
        // empty guest batch cannot skip it. Claim + outbox enqueue share this transaction.
        registeredReminders.remindDayBefore(now);

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

        BookingSmsProperties.Sms sms = smsProperties.getSms();
        for (Booking booking : singles) {
            collectReminder(reminders, booking, reminderTemplate(booking, sms.getReminder(), sms.getReminderSalon()), 1);
        }
        // No setReminderSent(true) / saveAll: the native claim above (claimRemindersByIds) already
        // persisted reminder_sent = TRUE for exactly these rows, as the visit claim does for visits. The
        // in-memory flag is left stale on purpose (nothing later in this transaction reads it), which keeps
        // Hibernate's dirty check from issuing a redundant UPDATE per row at commit.

        Map<UUID, Long> itemCounts = visitItemCounts(visits.keySet());
        for (Map.Entry<UUID, List<Booking>> visit : visits.entrySet()) {
            // Remind on the earliest item; the SMS renders that item's service/time as the visit summary, with
            // «та ще N послуг(и)» for the rest of the visit (phase 435).
            List<Booking> items = visit.getValue();
            items.sort(Comparator.comparing(Booking::getStartsAt));
            Booking lead = items.get(0);
            collectReminder(reminders, lead, reminderTemplate(lead, sms.getReminder(), sms.getReminderSalon()),
                    itemCounts.getOrDefault(visit.getKey(), 1L));
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
        // Phase 494 — the registered-client e-mail leg, behind the same flag (D4) and night skip, same window and
        // short-notice rule; before the guest query so its early return cannot skip it.
        registeredReminders.remindTwoHoursBefore(now, config.getMinLead());
        List<Booking> due = bookingRepository.findGuestBookingsForTwoHourReminder(
                now.plus(TWO_HOUR_WINDOW_START), now.plus(TWO_HOUR_WINDOW_END), includeWalkIns(),
                Limit.of(BookingRepository.TWO_HOUR_REMINDER_BATCH_CAP));
        if (due.isEmpty()) {
            return;
        }
        List<Booking> leads = claimTwoHourLeads(due, config.getMinLead());
        List<GuestReminderSms> reminders = new ArrayList<>();
        Map<UUID, Long> itemCounts = visitItemCounts(leads.stream()
                .filter(lead -> lead.getAppointment() != null)
                .map(lead -> lead.getAppointment().getId())
                .toList());
        BookingSmsProperties.Sms sms = smsProperties.getSms();
        for (Booking lead : leads) {
            long itemCount = lead.getAppointment() == null
                    ? 1 : itemCounts.getOrDefault(lead.getAppointment().getId(), 1L);
            collectReminder(reminders, lead, reminderTemplate(lead, sms.getReminder2h(), sms.getReminder2hSalon()),
                    itemCount);
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
            List<UUID> dueIds = visits.values().stream().flatMap(List::stream).map(Booking::getId).toList();
            Set<UUID> claimedRows = new HashSet<>();
            Set<UUID> claimedVisits = new HashSet<>();
            for (BookingRepository.TwoHourVisitClaim claim
                    : bookingRepository.claimTwoHourVisitRemindersByAppointmentIds(visits.keySet(), dueIds)) {
                claimedRows.add(claim.getId());
                claimedVisits.add(claim.getAppointmentId());
            }
            visits.forEach((appointmentId, items) -> {
                if (claimedVisits.contains(appointmentId)) {
                    // B493: lead with the earliest due row THIS claim flipped (a moved item, never a guarded added
                    // service). Fallback keeps phase 413: a due row skipped while locked, rest of the visit claimed.
                    leads.add(items.stream().filter(b -> claimedRows.contains(b.getId())).findFirst()
                            .orElse(items.get(0)));
                }
            });
        }
        return leads;
    }

    /** D3 — created less than {@code minLead} before its start; a missing audit timestamp is never short notice. */
    private static boolean isShortNotice(Booking booking, Duration minLead) {
        return isShortNotice(booking.getCreatedAt(), booking.getStartsAt(), minLead);
    }

    /** D3 kernel, shared with the registered leg ({@link RegisteredBookingReminderSweep}) so the rule cannot drift. */
    static boolean isShortNotice(Instant createdAt, OffsetDateTime startsAt, Duration minLead) {
        return createdAt != null && createdAt.isAfter(startsAt.toInstant().minus(minLead));
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
    /**
     * Phase 435 — the salon variant (salon name + performing master) whenever the booking has a salon. Audit-fix: the
     * independent-master template rides along as the fallback {@link GuestSmsProviderText#render} uses when the salon
     * name is dropped (URL-like) or the message is over the length cap.
     */
    private static ReminderTemplates reminderTemplate(Booking booking, String masterTemplate, String salonTemplate) {
        return new ReminderTemplates(booking.getSalon() != null ? salonTemplate : null, masterTemplate);
    }

    private record ReminderTemplates(String salon, String master) {
    }

    /** Phase 435 — CONFIRMED item count per claimed visit, one grouped query; empty input → no query. */
    private Map<UUID, Long> visitItemCounts(Collection<UUID> appointmentIds) {
        if (appointmentIds.isEmpty()) {
            return Map.of();
        }
        return bookingRepository.countConfirmedItemsByAppointmentIds(appointmentIds).stream()
                .collect(Collectors.toMap(BookingRepository.VisitItemCount::getAppointmentId,
                        BookingRepository.VisitItemCount::getItemCount));
    }

    private void collectReminder(List<GuestReminderSms> reminders, Booking booking, ReminderTemplates template,
                                 long itemCount) {
        // Phase 373 defensive guard: an anonymised guest row has guest_phone NULL. The sweep's
        // window (upcoming CONFIRMED) cannot reach one — retention needs ends_at > 12 months ago and
        // the erase cancels upcoming rows before wiping — but a null must never reach the SMS sender.
        if (booking.getGuestPhone() == null) {
            log.warn("Guest reminder skipped: booking {} has no guestPhone", booking.getId());
            return;
        }
        try {
            reminders.add(new GuestReminderSms(booking.getGuestPhone(), buildReminderSms(booking, template, itemCount)));
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
     * copy — SMS is a guest's only channel. Since phase 435 this template also carries {@code {cancelUrl}}, which
     * the same single pass keeps from being duplicated or forged by a value. A single pass copies
     * substituted values out verbatim, so data can never become markup.
     *
     * <p>Phase 435: the «Адреса:» line is optional — dropped whole when {@link GuestVisitAddress} finds none; so is the
     * «Скасувати запис: {cancelUrl}» line when {@link GuestBookingPageLinks} cannot build the guest's page.
     */
    private String buildReminderSms(Booking booking, ReminderTemplates template, long itemCount) {
        OffsetDateTime kyiv = booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        Map<String, String> values = new HashMap<>();
        // 413 D4: platform name for walk-ins; phase 435: «та ще N послуг(и)» for a visit. Audit-fix: every provider
        // value goes through GuestSmsProviderText (one line, capped; salon name / address dropped when URL-like).
        values.put("serviceName", GuestSmsServiceName.visitPhrase(
                GuestSmsProviderText.personName(GuestSmsServiceName.of(booking)), itemCount));
        // Audit-fix cycle 2: a URL-like master name is dropped — render then removes the master segment.
        GuestSmsProviderText.masterName(masterName(booking)).ifPresent(name -> values.put("masterName", name));
        values.put("time", TIME_FMT.format(kyiv));
        // Phase 435 follow-up: the address (both keys) plus the « ({locationNote})» value, kept only beside an address.
        values.putAll(GuestVisitAddress.smsValues(booking, ADDRESS_PLACEHOLDERS));
        if (booking.getSalon() != null) {
            GuestSmsProviderText.salonName(booking.getSalon().getName())
                    .ifPresent(name -> values.put("salonName", name));
        }
        pageLinks.forBooking(booking).ifPresent(url -> values.put(CANCEL_URL, url));
        // Capped at GuestSmsProviderText.MAX_SMS_CHARS: the note goes first, then the address line, then the salon.
        return GuestSmsProviderText.render(template.salon(), template.master(), values,
                OPTIONAL_PLACEHOLDERS, ADDRESS_PLACEHOLDERS);
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
