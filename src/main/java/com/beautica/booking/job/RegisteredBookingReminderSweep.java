package com.beautica.booking.job;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingRepository.RegisteredReminderCandidate;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.service.ReminderKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 494 (NTF-47) — the REGISTERED-client leg of {@link BookingReminderJob}'s two sweeps: APP bookings get a
 * reminder e-mail 24 h and 2 h before, on the guest timing (GST-46). E-mail only — a registered user never gets SMS
 * or Telegram (NTF-D04) — and one e-mail per booking, never per visit (NTF-D11), so there is no visit grouping.
 *
 * <p>Same claim-first shape as the guest leg: the conditional {@code UPDATE ... RETURNING} on the existing flag
 * ({@code reminder_sent} / {@code reminder_2h_sent}) decides which instance reminds, and the outbox row
 * ({@code BOOKING_REMINDER}) is written in the SAME transaction (D2, NTF-01) — the flag and the row commit together,
 * and the drain owns retry / DEAD. No network I/O here.
 *
 * <p>Both legs {@code REQUIRES_NEW} (phase 522 audit-fix 4 for 2 h, cycle 2 for 24 h): each is its own short
 * transaction, committed before the job's guest leg runs, so the claimed booking rows are not held locked through it.
 * The job applies the 2 h flag gate and the Kyiv night skip before calling in, so they bind both legs identically (D4).
 *
 * <p>Phase 495 (NTF-48) — the 24 h leg ALSO writes one {@code BOOKING_REMINDER} feed row per claimed booking for its
 * client, in this same claim transaction, and that row's fan-out is the only push (NTF-12 / NTF-S10). Phase 522
 * (NTF-48) — the 2 h leg does the same (key suffixed {@code :H2}), behind the same flag / night / short-notice gates
 * because it only writes for bookings this leg claimed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RegisteredBookingReminderSweep {

    private final BookingRepository bookingRepository;
    private final NotificationOutboxService outboxService;
    private final InAppNotificationService inAppNotificationService;

    /**
     * Claims the due APP bookings in the 24 h window {@code [now+23h, now+25h]} (at most
     * {@link BookingRepository#REGISTERED_DAY_BEFORE_REMINDER_BATCH_CAP}; overflow next tick; locked rows skipped) and
     * enqueues one {@link ReminderKind#H24} row per claimed booking, plus (phase 495) one {@code BOOKING_REMINDER}
     * feed row for each claimed booking's client. Returns how many e-mails were enqueued.
     *
     * <p>Phase 522 audit-fix cycle 2 — {@code REQUIRES_NEW} (was {@code MANDATORY}), as {@link #remindTwoHoursBefore}:
     * committed when this method returns, so the claimed rows are unlocked before the job's guest 24 h leg.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int remindDayBefore(OffsetDateTime now) {
        List<RegisteredReminderCandidate> due = bookingRepository.findRegisteredBookingsForReminder(
                now.plus(ReminderKind.H24.windowStart()), now.plus(ReminderKind.H24.windowEnd()),
                Limit.of(BookingRepository.REGISTERED_DAY_BEFORE_REMINDER_BATCH_CAP));
        if (due.isEmpty()) {
            return 0;
        }
        List<UUID> dueIds = due.stream().map(RegisteredReminderCandidate::getId).toList();
        List<UUID> claimedIds = bookingRepository.claimRegisteredRemindersByIds(dueIds);
        writeReminderFeedRows(claimedIds, ReminderKind.H24);
        return enqueueClaimed(dueIds, claimedIds, ReminderKind.H24, now);
    }

    /**
     * Phase 495 — one feed row per booking this instance won (never for a skipped / lost claim). One projection
     * SELECT reads the claimed rows' ids and start (no {@code Booking} entity is loaded into the session) and one
     * bulk INSERT writes every row (audit-fix cycle 2 — never one statement per booking). Phase 522 — the window
     * ({@code kind}) goes into the dedup key, so a booking's 24 h and 2 h rows coexist.
     */
    private void writeReminderFeedRows(List<UUID> claimedIds, ReminderKind kind) {
        if (claimedIds.isEmpty()) {
            return;
        }
        inAppNotificationService.notifyBookingRemindersBulk(bookingRepository.findReminderFeedTargetsByIds(claimedIds),
                kind);
    }

    /**
     * Claims the due APP bookings in the 2 h window {@code [now+1h45, now+2h15)} that were not booked at short notice
     * (created less than {@code minLead} before the start — not claimed, so the window's second tick re-evaluates it
     * identically) and enqueues one {@link ReminderKind#H2} row per claimed booking, plus (phase 522, NTF-48) one
     * {@code BOOKING_REMINDER} feed row keyed {@code …:H2} for each claimed booking's client. Returns how many e-mails
     * were enqueued.
     *
     * <p>Phase 522 audit-fix 4 — {@code REQUIRES_NEW}: the 2 h leg is its OWN short transaction (claim, feed rows,
     * outbox rows), committed when this method returns, so the claimed booking rows are unlocked before the job's
     * guest 2 h leg runs (the job calls this outside any transaction, so nothing is suspended).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int remindTwoHoursBefore(OffsetDateTime now, Duration minLead) {
        List<UUID> dueIds = bookingRepository.findRegisteredBookingsForTwoHourReminder(
                        now.plus(ReminderKind.H2.windowStart()), now.plus(ReminderKind.H2.windowEnd()),
                        Limit.of(BookingRepository.TWO_HOUR_REMINDER_BATCH_CAP)).stream()
                .filter(c -> !BookingReminderJob.isShortNotice(c.getCreatedAt(), c.getStartsAt(), minLead))
                .map(RegisteredReminderCandidate::getId)
                .toList();
        if (dueIds.isEmpty()) {
            return 0;
        }
        List<UUID> claimedIds = bookingRepository.claimRegisteredTwoHourRemindersByIds(dueIds);
        writeReminderFeedRows(claimedIds, ReminderKind.H2);
        return enqueueClaimed(dueIds, claimedIds, ReminderKind.H2, now);
    }

    /** One outbox row per booking this instance won, in due order. */
    private int enqueueClaimed(List<UUID> dueIds, List<UUID> claimedIds, ReminderKind kind, OffsetDateTime now) {
        Set<UUID> claimed = new HashSet<>(claimedIds);
        int enqueued = 0;
        for (UUID bookingId : dueIds) {
            if (claimed.contains(bookingId)) {
                outboxService.enqueueBookingReminder(bookingId, kind, now.toInstant());
                enqueued++;
            }
        }
        log.info("Registered-client {} reminders: {} enqueued from {} due bookings", kind, enqueued, dueIds.size());
        return enqueued;
    }
}
