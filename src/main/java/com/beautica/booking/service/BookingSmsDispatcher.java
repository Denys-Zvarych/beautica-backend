package com.beautica.booking.service;

import com.beautica.notification.sms.SmsBackpressureException;
import com.beautica.notification.sms.SmsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

/**
 * Takes every REQUEST-PATH booking SMS off the request thread (backend-perf MEDIUM, 2026-08-18;
 * widened from the walk-in path alone, backend-perf LOW, 2026-08-19).
 *
 * <h2>The problem</h2>
 * The four request-path senders register the send as an after-commit callback, and an after-commit callback runs
 * on the <b>committing thread</b> — here, the servlet thread, before the response is written and
 * while it still holds its pooled Hikari connection (Spring releases that at
 * {@code afterCompletion}, one phase later; see {@code AsyncConfig#smsReminderDispatchExecutor} for
 * the measurements). So every guest create, every guest cancel, every whole-visit cancel and every
 * walk-in create paid the full Turbosms round trip in its own p99 — up to the 5 s read cap plus 3 s
 * connect on a stalling provider — for a message whose delivery the caller neither sees nor can act
 * on, and against a {@code leak-detection-threshold: 10000} that such a stall walks straight into.
 * Virtual threads remove the pool-exhaustion half of that, not the latency half.
 *
 * <h2>The shape</h2>
 * One hand-off to {@code smsSendExecutor} and return. Two invariants proven before this change are
 * preserved <b>by construction</b>, not by hope:
 * <ul>
 *   <li><b>Cache eviction still runs before the send.</b> Every caller runs its eviction first and
 *       this dispatcher's callback performs no network I/O inline — the ordering that mattered
 *       (evict, then send) is now also an ordering between "runs" and "is queued", which cannot
 *       regress. {@code BookingCancellationService} and {@code GuestVisitCancellationService} used
 *       to send BEFORE evicting; routing them here moved the evict in front, so the two cancel paths
 *       now match the create paths and the eviction can no longer be delayed by a provider
 *       brown-out.</li>
 *   <li><b>A send failure can never fail the committed booking.</b> The failure is caught on a pool
 *       thread, one caller removed from any transaction; and a REJECTED submission is caught here,
 *       so even pool saturation cannot escape into an {@code afterCommit} callback (Spring
 *       propagates throwables out of {@code afterCommit}, which would report an already-committed
 *       write as failed).</li>
 * </ul>
 *
 * <p>It injects {@link SmsService}, so it is fully on the {@code app.booking.sms.enabled} gate and
 * knows nothing about it: with the gate off it hands a {@code NoOpSmsService} call to the pool,
 * which is cheap and correct. That is also why this class is the ONLY booking-side {@code
 * SmsService} consumer worth having — the OTP carve-out ({@code OtpSmsSender}) is deliberately
 * unreachable from this package, mechanically enforced by {@code SmsFeatureGateTest}.
 *
 * <p><b>Out of scope:</b> the 24 h reminder, which is not on a request thread at all and already has
 * its own two-stage pool with opposite rejection semantics ({@code smsReminderExecutor}, a
 * {@code CallerBlocksPolicy} pool — a dropped reminder is unrecoverable, a dropped confirmation is
 * not). See {@code AsyncConfig#smsSendExecutor} for why the two pools must not be merged.
 */
@Component
public class BookingSmsDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BookingSmsDispatcher.class);

    /**
     * Which message was queued. Present only so the drop line and the failure line name the lost
     * message: five senders share one pool, and {@code event=booking_sms_dropped} on its own tells an
     * operator that something was lost but not what. Never rendered into the SMS, never a routing
     * decision — every kind takes the identical path.
     */
    public enum Kind {

        WALK_IN_CONFIRMATION("walk_in_confirmation"),
        GUEST_CONFIRMATION("guest_confirmation"),
        GUEST_CANCELLATION("guest_cancellation"),
        GUEST_VISIT_CANCELLATION("guest_visit_cancellation"),
        /**
         * Phase 390 — the provider side cancelled a guest's booking or visit (decline, salon
         * closure, master removal). Queued from {@code NotificationService} on the outbox drain
         * thread, which is already after commit; routed here so a Turbosms stall never holds the
         * drain batch, and so the drop/failure lines name it like every other booking SMS.
         */
        GUEST_PROVIDER_CANCELLATION("guest_provider_cancellation");

        private final String token;

        Kind(String token) {
            this.token = token;
        }

        /** Stable, machine-greppable log token — renaming one is an alerting change. */
        public String token() {
            return token;
        }
    }

    /**
     * Queue slots of {@code smsSendExecutor} that {@link #dispatchOrThrow} never consumes (phase 390,
     * backend-perf LOW-2): one outbox drain tick can submit up to 50 SMS ({@code BATCH_SIZE}), so a
     * reserve equal to one full batch keeps a burst of retryable outbox SMS from filling the 200-slot
     * queue and dropping request-path confirmations, which are never retried.
     */
    static final int OUTBOX_QUEUE_RESERVE = 50;

    private final SmsService smsService;
    private final TaskExecutor smsSendExecutor;

    public BookingSmsDispatcher(SmsService smsService,
                                @Qualifier("smsSendExecutor") TaskExecutor smsSendExecutor) {
        this.smsService = smsService;
        this.smsSendExecutor = smsSendExecutor;
    }

    /**
     * Queues one booking SMS. Returns immediately and never throws.
     *
     * @param kind      which message this is — log labelling only
     * @param phoneE164 the recipient's normalised number
     * @param text      the fully rendered body — rendered by the caller inside the transaction,
     *                  while its lazy associations are still managed
     */
    public void dispatch(Kind kind, String phoneE164, String text) {
        try {
            smsSendExecutor.execute(() -> sendSafely(kind, phoneE164, text));
        } catch (RejectedExecutionException e) {
            // AbortPolicy, deliberately (Anti-Bug §H-2): the alternatives are worse here. Caller-runs
            // would put the blocking provider call straight back on the request thread this class
            // exists to free, and caller-BLOCKS would park that thread while it still holds a pooled
            // DB connection. A dropped confirmation is the mildest outcome of the three: the write is
            // committed and the client was either standing in front of the provider or has already
            // seen the outcome in the response. Structured, greppable drop log rather than a silent
            // loss — no recipient, no rendered body (Anti-Bug §I).
            log.error("event=booking_sms_dropped kind={} reason=executor_saturated — smsSendExecutor "
                    + "refused the message; the booking write is committed and unaffected", kind.token());
        }
    }

    /**
     * Queues one booking SMS from a caller that can RETRY — the notification outbox drain (phase
     * 390, {@link Kind#GUEST_PROVIDER_CANCELLATION}). Differs from {@link #dispatch} in two ways:
     * <ul>
     *   <li>A saturated {@code smsSendExecutor} is NOT swallowed: an {@link SmsBackpressureException}
     *       propagates so {@code NotificationOutboxDrainWorker} re-queues the row as {@code PENDING}
     *       without counting an attempt. Swallowing it here would let the drain mark the row
     *       {@code SENT} and lose the SMS for good.</li>
     *   <li>It never takes the last {@link #OUTBOX_QUEUE_RESERVE} queue slots. Those belong to the
     *       request-path senders, whose SMS is dropped (never retried) on rejection; the outbox SMS
     *       is retried next tick, so it is the one that yields.</li>
     * </ul>
     * A send failure on the pool thread is still swallowed and logged (the hand-off succeeded; that
     * row is done).
     *
     * <p>Never call this from an {@code afterCommit} callback — Spring would surface the rejection
     * as a failed write that has in fact committed. Request-path callers use {@link #dispatch}.
     *
     * @throws SmsBackpressureException when the pool refuses the task or its free queue slots are
     *                                  below {@link #OUTBOX_QUEUE_RESERVE}
     */
    public void dispatchOrThrow(Kind kind, String phoneE164, String text) {
        if (remainingQueueCapacity() < OUTBOX_QUEUE_RESERVE) {
            // Kind only — no recipient, no rendered body (Anti-Bug §I).
            log.warn("event=booking_sms_deferred kind={} reason=request_path_reserve — the caller re-queues "
                    + "the message", kind.token());
            throw new SmsBackpressureException("smsSendExecutor headroom reserved for request-path SMS");
        }
        try {
            smsSendExecutor.execute(() -> sendSafely(kind, phoneE164, text));
        } catch (RejectedExecutionException e) {
            log.warn("event=booking_sms_deferred kind={} reason=executor_saturated — the caller re-queues "
                    + "the message", kind.token());
            throw new SmsBackpressureException("smsSendExecutor saturated", e);
        }
    }

    /**
     * Free queue slots of the production pool; {@link Integer#MAX_VALUE} for any other executor (the
     * test profile's {@code SyncTaskExecutor} has no queue, so the reserve can never bind there).
     * A check-then-act read: two concurrent outbox submissions may both pass at exactly the
     * boundary, so the reserve is a soft floor — one drain thread submits serially, which keeps the
     * overshoot at zero in practice and at a handful of slots in the worst case.
     */
    private int remainingQueueCapacity() {
        if (smsSendExecutor instanceof ThreadPoolTaskExecutor pool) {
            return pool.getThreadPoolExecutor().getQueue().remainingCapacity();
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Runs on an {@code sms-send-*} thread. The same catch each caller made before, for the same
     * reason: the write is already committed, so a provider outage must never surface anywhere.
     * {@code RuntimeException} rather than {@code SmsDeliveryException} alone because the adapter
     * sits behind an HTTP client and a JSON codec, either of which can raise something else
     * entirely. Only the kind and the exception class are logged — never the phone, never the
     * rendered body (Anti-Bug §I).
     */
    private void sendSafely(Kind kind, String phoneE164, String text) {
        try {
            smsService.send(phoneE164, text);
        } catch (RuntimeException e) {
            log.warn("Booking SMS send failed: kind={} cause={}", kind.token(), e.getClass().getSimpleName());
        }
    }
}
