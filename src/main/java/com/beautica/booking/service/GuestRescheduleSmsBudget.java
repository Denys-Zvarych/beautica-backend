package com.beautica.booking.service;

import com.beautica.booking.entity.GuestRescheduleSmsSend;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.GuestRescheduleSmsSendRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 431 (audit-fix cycle 1, security HIGH) — the per-PHONE send budget for the guest reschedule
 * SMS. (The per-BOOKING bound is not an SMS cap: per the user decision of 2026-10-30 it is a cap on
 * the provider reschedule REQUEST itself — 5 per guest booking/visit per rolling 24 h, the 6th
 * rejected with {@code RESCHEDULE_LIMIT_REACHED}; see
 * {@code AppointmentTransitionService#assertGuestRescheduleWithinLimitAndRecord}.)
 *
 * <ul>
 *   <li><b>Per phone (silent):</b> walk-in confirmation SMS plus reschedule SMS count against ONE budget of
 *       {@link StaffBookingService#MAX_WALK_INS_PER_PHONE_PER_WINDOW} per
 *       {@link StaffBookingService#WALK_IN_PHONE_WINDOW} — the same constants, the same per-phone
 *       advisory lock ({@link BookingRepository#acquireWalkInPhoneLock}, salt 3, fused 3 s
 *       {@code lock_timeout}) and the same visit count {@code StaffBookingService
 *       #assertWalkInSmsBudgetForPhone} uses. The budget is ONE-WAY: walk-ins count against reschedule SMS,
 *       but reschedule SMS never count against a walk-in create (its 429 reads walk-in visits only, user
 *       rule 2026-10-30), so one provider's reschedules cannot block another's walk-in. The
 *       lock serialises a walk-in create and a reschedule reservation for the same number, so neither
 *       can read a stale count (the TOCTOU that lock was introduced for).</li>
 * </ul>
 *
 * <p>Counts SENDS (rows reserved immediately before the hand-off to the SMS pool), never outbox rows.
 * A back-pressure retry of the same outbox row reuses its reservation ({@code outbox_entry_id} is
 * unique), so a deferred send is counted once. A reservation whose hand-off later fails on the pool
 * thread still counts — the conservative direction for an abuse cap.
 *
 * <p>Over the budget only the SMS is skipped — never the request, which must not reveal that the
 * number has activity elsewhere. The reschedule and the master e-mail are unaffected. The skip is
 * logged with the booking id and the reason only — never the phone (Anti-Bug §I).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestRescheduleSmsBudget {

    /** Rows older than the window are dead weight. */
    static final Duration RETENTION = Duration.ofDays(2);

    private final BookingRepository bookingRepository;
    private final GuestRescheduleSmsSendRepository sendRepository;
    private final Clock clock;

    /**
     * Reserves one reschedule SMS for {@code bookingId} to {@code phoneE164}, or returns {@code false}
     * when the per-phone budget is spent. Runs in its own transaction: the caller is the outbox drain, which runs
     * outside one ({@code NOT_SUPPORTED}), and the advisory lock is transaction-scoped.
     *
     * @param phoneE164     the booking's normalised {@code guest_phone} — the lock key, never logged
     * @param outboxEntryId the outbox row being dispatched; {@code null} only for direct callers with no
     *                      row, which then get no retry de-duplication
     */
    @Transactional
    public boolean tryReserve(UUID bookingId, String phoneE164, @Nullable UUID outboxEntryId) {
        bookingRepository.acquireWalkInPhoneLock(phoneE164);
        if (outboxEntryId != null && sendRepository.existsByOutboxEntryId(outboxEntryId)) {
            return true;
        }
        Instant now = clock.instant();
        Instant phoneSince = now.minus(StaffBookingService.WALK_IN_PHONE_WINDOW);
        long phoneUsed = bookingRepository.countPhoneSmsBudgetUsedSince(phoneE164, phoneSince).total();
        if (phoneUsed >= StaffBookingService.MAX_WALK_INS_PER_PHONE_PER_WINDOW) {
            log.info("event=guest_reschedule_sms_suppressed reason=per_phone_budget bookingId={}", bookingId);
            return false;
        }
        sendRepository.save(GuestRescheduleSmsSend.of(bookingId, outboxEntryId, now));
        return true;
    }

    /**
     * Daily purge (04:13 UTC, clear of the 03:xx jobs) of rows past every window: the SMS send log
     * (1 h window) and the provider-reschedule log behind the request cap (24 h window).
     */
    @Scheduled(cron = "0 13 4 * * *", zone = "UTC")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = clock.instant().minus(RETENTION);
        int sends = sendRepository.deleteSentBefore(cutoff);
        int reschedules = bookingRepository.deleteGuestProviderReschedulesBefore(cutoff);
        log.debug("Guest reschedule log purge removed {} SMS rows and {} reschedule rows", sends, reschedules);
    }
}
