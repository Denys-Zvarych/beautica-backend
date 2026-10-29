package com.beautica.review.invite.service;

import com.beautica.notification.sms.SmsDeliveryAmbiguousException;
import com.beautica.notification.sms.SmsService;
import com.beautica.review.invite.config.ReviewInviteProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 400 D4/D6 — the review-invite send job, every {@code app.booking.review-invite.job.fixed-delay-ms} (60 s).
 * One tick:
 * <ol>
 *   <li>expiry sweep — one bounded batch of {@code SENT} → {@code EXPIRED} (D6);</li>
 *   <li>inside the Kyiv quiet window nothing is claimed — a backlog after downtime waits for the morning (D3);</li>
 *   <li>claim + prepare one bounded batch in a short transaction (D4, {@code FOR UPDATE SKIP LOCKED}, no ShedLock —
 *       safe across instances during a rolling deploy);</li>
 *   <li>send each SMS with no transaction and no DB connection held; a provider failure is written back through
 *       {@link ReviewInviteSendService#recordFailedSend} (retry next tick, {@code FAILED} after max attempts). An
 *       UNKNOWN outcome ({@link SmsDeliveryAmbiguousException}: read timeout after the request was written) goes
 *       through {@link ReviewInviteSendService#recordAmbiguousSend} — {@code FAILED}, never retried, because the SMS
 *       may already have been delivered;</li>
 *   <li>the send loop runs within {@code app.booking.review-invite.send-budget} (20 s, below the 30 s scheduler
 *       shutdown grace): when it runs out, the claimed rows not yet attempted are handed back through
 *       {@link ReviewInviteSendService#releaseUnsent} ({@code SCHEDULED}, token voided) for the next tick.</li>
 * </ol>
 * Logs carry the invite id only — never the phone, the text or the token.
 *
 * <p>The bean always exists: with the feature flag off nothing is created, and any row left {@code SCHEDULED} from
 * before the flag was switched off is skipped ({@code INVITES_DISABLED}) instead of sent.
 */
@Slf4j
@Component
public class ReviewInviteSendJob {

    private final ReviewInviteSendService sendService;
    private final QuietHoursCalculator quietHours;
    private final SmsService smsService;
    private final ReviewInviteProperties properties;
    private final Clock clock;

    public ReviewInviteSendJob(ReviewInviteSendService sendService,
                               QuietHoursCalculator quietHours,
                               SmsService smsService,
                               ReviewInviteProperties properties,
                               Clock clock) {
        this.sendService = sendService;
        this.quietHours = quietHours;
        this.smsService = smsService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.booking.review-invite.job.fixed-delay-ms:60000}",
            initialDelayString = "${app.booking.review-invite.job.initial-delay-ms:60000}")
    public void run() {
        tick(clock.instant());
    }

    /** One tick at {@code now}. Public so integration tests drive a pinned instant instead of the timer. */
    public void tick(Instant now) {
        Instant deadline = clock.instant().plus(properties.sendBudget());
        int expired = sendService.expireSent(now);
        if (expired > 0) {
            log.info("Review invites expired: {}", expired);
        }
        if (quietHours.isQuiet(now)) {
            return;
        }
        List<PreparedInviteSms> batch = sendService.claimAndPrepare(now);
        for (int i = 0; i < batch.size(); i++) {
            if (!clock.instant().isBefore(deadline)) {
                releaseUnsent(batch.subList(i, batch.size()));
                return;
            }
            send(batch.get(i));
        }
    }

    /** The send budget ran out: hand the unattempted rows back, so none stays SENT without an SMS. */
    private void releaseUnsent(List<PreparedInviteSms> unsent) {
        List<UUID> ids = unsent.stream().map(PreparedInviteSms::inviteId).toList();
        log.warn("Review invite send budget exhausted: released={}", ids.size());
        try {
            sendService.releaseUnsent(ids);
        } catch (RuntimeException e) {
            log.error("Review invites not released: count={} invites={} cause={}",
                    ids.size(), ids, e.getClass().getSimpleName());
        }
    }

    private void send(PreparedInviteSms sms) {
        try {
            smsService.send(sms.phoneE164(), sms.text());
            log.info("Review invite SMS sent: invite={}", sms.inviteId());
        } catch (SmsDeliveryAmbiguousException e) {
            log.warn("Review invite SMS outcome unknown, not retried: invite={} cause={}",
                    sms.inviteId(), e.getClass().getSimpleName());
            recordAmbiguous(sms);
        } catch (RuntimeException e) {
            log.warn("Review invite SMS failed: invite={} cause={}", sms.inviteId(), e.getClass().getSimpleName());
            recordFailure(sms);
        }
    }

    /** Same isolation as {@link #recordFailure}: a failed write-back leaves the row SENT (at most once), never aborts. */
    private void recordAmbiguous(PreparedInviteSms sms) {
        try {
            sendService.recordAmbiguousSend(sms.inviteId());
        } catch (RuntimeException e) {
            log.error("Review invite unknown outcome not recorded: invite={} cause={}",
                    sms.inviteId(), e.getClass().getSimpleName());
        }
    }

    /** A failed write-back must not cost the rest of the batch, whose rows are already {@code SENT}. */
    private void recordFailure(PreparedInviteSms sms) {
        try {
            sendService.recordFailedSend(sms.inviteId());
        } catch (RuntimeException e) {
            log.error("Review invite failure not recorded: invite={} cause={}",
                    sms.inviteId(), e.getClass().getSimpleName());
        }
    }
}
