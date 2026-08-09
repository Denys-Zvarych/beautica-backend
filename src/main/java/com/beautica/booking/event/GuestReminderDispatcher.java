package com.beautica.booking.event;

import com.beautica.booking.event.GuestRemindersDueEvent.GuestReminderSms;
import com.beautica.notification.sms.SmsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.RejectedExecutionException;

/**
 * Delivers the guest 24h reminder SMS batch AFTER {@code BookingReminderJob}'s transaction commits
 * (backend-perf MEDIUM).
 *
 * <h4>The bug this closes</h4>
 * <p>The job used to call {@code SmsService#send} inside its own {@code @Transactional} sweep — N
 * serial blocking Turbosms HTTP calls (3 s connect / 5 s read timeout) with the {@code reminderSent}
 * flush only after the loop. At N=1000 and a 100 ms provider RTT that is a ~100 s transaction pinning
 * one Hikari connection, and a provider stalling at the 5 s read cap stretches it to over an hour. The
 * cron is {@code 0 0 * * * *}, so it does that on the hour, alongside everything else scheduled there.
 *
 * <h4>Hand-off — two stages, and why</h4>
 * <p>{@code @TransactionalEventListener} runs on the PUBLISHING thread by default — being an
 * AFTER_COMMIT listener buys ordering, not a thread change. And that thread has not yet released its
 * pooled Hikari connection: Spring releases it at {@code afterCompletion}, one phase later. Measured
 * against a real commit ({@code HikariPoolMXBean} + Hibernate's {@code LogicalConnection} sampled from
 * inside the listener): {@code hikariActive=1} and {@code physicallyConnected=true} for the whole
 * listener body, dropping to 0 only after completion; a 12 s body trips Hikari's own
 * {@code leak-detection-threshold: 10000}.
 *
 * <p>So the listener body must be O(1). Doing the N-wide submit loop here was measured at <b>3193 ms</b>
 * of connection hold for N=1000 at 50 ms/send once the send queue saturated and the submitter parked —
 * i.e. it re-created the exact MEDIUM this class exists to remove, relocated from inside the transaction
 * to immediately after it. Instead:
 *
 * <ol>
 *   <li>the listener submits ONE task to {@code smsReminderDispatchExecutor} and returns, so the
 *       transaction completes and the connection goes back to the pool immediately;</li>
 *   <li>that task, on {@code sms-reminder-dispatch-0} — holding no connection, no request, no scheduler
 *       slot — fans the batch out across {@code smsReminderExecutor}, where it may park freely on a full
 *       queue rather than dropping the tail.</li>
 * </ol>
 *
 * <h4>Why {@code fallbackExecution} stays false</h4>
 * <p>With no transaction in progress the event is dropped rather than dispatched. That is the
 * at-most-once contract: {@code reminderSent = true} is committed by the job BEFORE this listener can
 * fire, so a send is only ever attempted for a booking already durably marked as reminded. A crash
 * between commit and dispatch therefore loses that reminder — it can never produce a second one. A
 * guest receiving the same reminder twice reads as platform spam on their only channel; a guest not
 * receiving it still holds the booking confirmation SMS sent at creation. (This is also strictly
 * safer than the pre-fix order, which sent first and flushed the flags afterwards, so a crash
 * mid-loop re-sent every already-delivered reminder on the next tick.)
 *
 * <h4>Failure isolation</h4>
 * <p>Each reminder is its own task with its own {@code catch}, so one provider timeout cannot stop the
 * rest — matching (and, by running them concurrently, improving on) the old in-loop
 * {@code sendReminderSafely}.
 *
 * <h4>Saturation must not drop reminders</h4>
 * <p>A dropped submission is a guest who is never reminded and never retried: {@code reminderSent} is
 * already committed. {@code smsReminderExecutor} therefore back-pressures instead of aborting
 * ({@code AsyncConfig.CallerBlocksPolicy}) — {@link #fanOut} parks on a full queue rather than losing its
 * tail, which is affordable only because it runs on the dispatch thread and not on the committing one.
 * The {@code RejectedExecutionException} catch in {@link #fanOut} is the residual path only: the pool is
 * shutting down, or that thread was interrupted. It is counted and logged at ERROR (never swallowed) and
 * never aborts the reminders queued behind it.
 *
 * <p>Nothing in the listener may throw: Spring propagates throwables out of {@code afterCommit} callbacks
 * (unlike {@code afterCompletion}, which swallows them), so an escaping exception would surface as a
 * failure of an already-committed sweep.
 */
@Component
public class GuestReminderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(GuestReminderDispatcher.class);

    /**
     * How many links of a failure's cause chain {@link #containsInterrupted} inspects before giving up.
     * See that method for why the walk is bounded rather than cycle-detected.
     */
    private static final int MAX_CAUSE_CHAIN_DEPTH = 16;

    private final SmsService smsService;
    private final TaskExecutor smsReminderDispatchExecutor;
    private final TaskExecutor smsReminderExecutor;

    public GuestReminderDispatcher(
            SmsService smsService,
            @Qualifier("smsReminderDispatchExecutor") TaskExecutor smsReminderDispatchExecutor,
            @Qualifier("smsReminderExecutor") TaskExecutor smsReminderExecutor) {
        this.smsService = smsService;
        this.smsReminderDispatchExecutor = smsReminderDispatchExecutor;
        this.smsReminderExecutor = smsReminderExecutor;
    }

    /**
     * Runs on the committing thread, which still holds its pooled Hikari connection — so the body is one
     * hand-off and nothing else. Any per-reminder work here (including parking on a full send queue) is
     * paid in connection-hold time; see the class Javadoc for the measurements.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGuestRemindersDue(GuestRemindersDueEvent event) {
        try {
            smsReminderDispatchExecutor.execute(() -> fanOut(event));
        } catch (RejectedExecutionException e) {
            // The dispatch pool takes one task per hourly sweep against a queue of 8, so this is
            // effectively shutdown-only. It costs the WHOLE batch rather than a tail, so it is logged
            // at ERROR — and swallowed rather than rethrown, because Spring propagates throwables out
            // of afterCommit and would report the already-committed sweep as failed.
            log.error("Guest reminder SMS batch dropped — smsReminderDispatchExecutor refused the batch: "
                    + "{} reminders not dispatched", event.reminders().size());
        }
    }

    /**
     * Fans the batch out across {@code smsReminderExecutor}, one task per reminder. Runs on
     * {@code sms-reminder-dispatch-0}: holding no DB connection, no HTTP request and no scheduler slot,
     * so parking here on a full send queue (CallerBlocksPolicy) costs one idle thread and nothing else.
     */
    private void fanOut(GuestRemindersDueEvent event) {
        int rejected = 0;
        for (GuestReminderSms reminder : event.reminders()) {
            try {
                smsReminderExecutor.execute(() -> sendSafely(reminder));
            } catch (RejectedExecutionException e) {
                // Residual path only — CallerBlocksPolicy parks on a full queue instead of rejecting,
                // so this fires only when the pool is shutting down or this thread was interrupted
                // (Spring also wraps rejection as TaskRejectedException, a subclass). Counted rather
                // than rethrown so the remaining reminders are still submitted, and logged below
                // because the booking is already flagged as reminded — this one is lost, not delayed.
                rejected++;
            }
        }
        if (rejected > 0) {
            log.error("Guest reminder SMS dropped — smsReminderExecutor saturated: {} of {} not dispatched",
                    rejected, event.reminders().size());
        }
    }

    /**
     * Runs on an {@code sms-reminder-*} thread. Logs the failure class only — never the phone, never
     * the message body (Anti-Bug §I).
     *
     * <p><b>Catches {@link Throwable}, not {@code RuntimeException}.</b> Nothing here reaches an
     * {@code @Async} proxy or a {@code Future}, so an escaping throwable has exactly one destination:
     * the JVM default uncaught-exception handler, which writes a raw stack trace to stderr — bypassing
     * the production JSON log encoder entirely, so the reminder is lost with no structured record that
     * it ever existed. {@code SmsService} sits behind an HTTP client and a JSON codec, both of which
     * can surface {@code Error} (a {@code NoClassDefFoundError} from a partially-initialised codec, an
     * {@code OutOfMemoryError} on a large response) rather than a {@code RuntimeException}. Catching
     * broadly here contains one reminder; letting it escape costs the log line too.
     *
     * <p>Interrupt is the one condition that must not be swallowed: shutdown interrupts in-flight
     * sends, and a cleared flag makes the worker look healthy and keep taking tasks. The flag is
     * restored whenever the cause chain contains an {@link InterruptedException} — the chain, not just
     * the top type, because the SMS client wraps it (e.g. an interrupted socket read arrives as an
     * {@code IOException} caused by an {@code InterruptedException}).
     *
     * <p>The flag is restored <b>after</b> the log call, not before. Logback's {@code putUninterruptibly}
     * happens to survive an already-raised flag, but any appender that parks (async/queue-backed) or does
     * interruptible I/O would not — and this is the one log line that explains why the worker is stopping,
     * so its survival must be a property of this method rather than of the configured appender.
     */
    private void sendSafely(GuestReminderSms reminder) {
        try {
            smsService.send(reminder.phoneE164(), reminder.text());
        } catch (Throwable t) {
            boolean interrupted = containsInterrupted(t);
            log.warn("Guest reminder SMS failed: {}", t.getClass().getSimpleName());
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * True when {@code t} or anything within the first {@value #MAX_CAUSE_CHAIN_DEPTH} links of its cause
     * chain is an {@link InterruptedException}.
     *
     * <p><b>Depth-capped, not cycle-detected.</b> {@link Throwable#initCause} rejects only SELF-causation
     * ({@code cause == this}), so a two-throwable cycle — {@code a.initCause(b); b.initCause(a);} — is
     * legal, and a walk that only guards the self case spins on it forever, pinning one
     * {@code sms-reminder-*} worker permanently; enough occurrences wedge the pool and every subsequent
     * reminder is lost silently. These throwables are not built here: they arrive from an HTTP client and
     * a JSON codec, so the chain is bounded rather than trusted. The cap is the same defence
     * {@link Throwable#printStackTrace()} applies with its dejaVu set, and 16 is far beyond any real wrap
     * depth (the deepest this code has ever seen is {@code IOException → InterruptedException}).
     */
    private static boolean containsInterrupted(Throwable t) {
        Throwable cause = t;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_CHAIN_DEPTH; depth++) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
