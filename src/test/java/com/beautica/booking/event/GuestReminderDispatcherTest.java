package com.beautica.booking.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.booking.event.GuestRemindersDueEvent.GuestReminderSms;
import com.beautica.config.AsyncConfig;
import com.beautica.notification.sms.SmsDeliveryException;
import com.beautica.notification.sms.SmsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("GuestReminderDispatcher — after-commit guest reminder SMS delivery")
class GuestReminderDispatcherTest {

    @Mock private SmsService smsService;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger dispatcherLogger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        logAppender.start();
        dispatcherLogger = (Logger) LoggerFactory.getLogger(GuestReminderDispatcher.class);
        dispatcherLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        dispatcherLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    /**
     * The core of backend-perf MEDIUM. {@code @TransactionalEventListener(AFTER_COMMIT)} runs on the
     * PUBLISHING thread, so being after-commit alone would still leave every blocking Turbosms call on the
     * scheduler thread. This pins that the listener genuinely hands off: no send touches the calling
     * thread, and all of them land on the bounded, named {@code sms-reminder-*} pool.
     */
    @Test
    @DisplayName("should run every send on the sms-reminder pool, never on the publishing thread")
    void should_dispatchOffThePublishingThread_when_remindersAreDue() throws Exception {
        ThreadPoolTaskExecutor pool = pool();
        Set<String> sendThreads = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch sent = new CountDownLatch(3);
        doAnswer(inv -> {
            sendThreads.add(Thread.currentThread().getName());
            sent.countDown();
            return null;
        }).when(smsService).send(anyString(), anyString());

        try {
            new GuestReminderDispatcher(smsService, new SyncTaskExecutor(), pool).onGuestRemindersDue(event(3));
            assertThat(sent.await(10, TimeUnit.SECONDS)).as("all three sends ran").isTrue();
        } finally {
            // Must be in a finally: ThreadPoolTaskExecutor's workers are NON-daemon, so a failing
            // assertion above would leak live threads and hang the Gradle test JVM at exit.
            pool.shutdown();
        }
        assertThat(sendThreads)
                .as("a send on the publishing thread is the bug: it puts provider RTT back on the scheduler")
                .doesNotContain(Thread.currentThread().getName())
                .allSatisfy(name -> assertThat(name).startsWith("sms-reminder-"));
    }

    /**
     * Pins the at-most-once contract's other half. {@code fallbackExecution = false} means no transaction
     * → no dispatch, so a send is only ever attempted once {@code reminderSent = true} is committed. A
     * crash between commit and dispatch therefore loses one reminder instead of re-sending the batch.
     */
    @Test
    @DisplayName("delivery must be bound to AFTER_COMMIT with no non-transactional fallback")
    void should_dispatchOnlyAfterCommit_when_theListenerIsRegistered() throws Exception {
        TransactionalEventListener annotation = GuestReminderDispatcher.class
                .getMethod("onGuestRemindersDue", GuestRemindersDueEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution())
                .as("fallback dispatch would send for bookings whose reminderSent flag never committed")
                .isFalse();
    }

    @Test
    @DisplayName("should deliver the remaining reminders when one provider send throws")
    void should_sendTheRest_when_oneSendThrows() {
        doThrow(new SmsDeliveryException("provider timeout"))
                .when(smsService).send(eq("+38050000000"), anyString());

        new GuestReminderDispatcher(smsService, new SyncTaskExecutor(), new SyncTaskExecutor()).onGuestRemindersDue(event(3));

        verify(smsService).send(eq("+38050000000"), anyString());
        verify(smsService).send(eq("+38050000001"), anyString());
        verify(smsService).send(eq("+38050000002"), anyString());
    }

    /**
     * A rejected reminder is permanently lost — {@code reminderSent} is already committed, so no later
     * sweep re-selects it. It must therefore be logged at ERROR, and it must not abort the reminders
     * queued behind it, nor escape the listener (Spring propagates throwables out of afterCommit).
     */
    @Test
    @DisplayName("should log a saturation drop and keep submitting when the executor rejects")
    void should_logAndKeepSubmitting_when_theExecutorRejects() {
        AtomicInteger submitted = new AtomicInteger();
        TaskExecutor rejectsTheSecond = task -> {
            if (submitted.incrementAndGet() == 2) {
                throw new TaskRejectedException("queue full");
            }
            task.run();
        };

        assertThatCode(() -> new GuestReminderDispatcher(smsService, new SyncTaskExecutor(), rejectsTheSecond)
                .onGuestRemindersDue(event(3))).doesNotThrowAnyException();

        verify(smsService).send(eq("+38050000000"), anyString());
        verify(smsService).send(eq("+38050000002"), anyString());
        assertThat(logAppender.list)
                .as("a dropped reminder is never retried — it must not vanish silently")
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(e.getFormattedMessage()).contains("1 of 3");
                });
    }

    @Test
    @DisplayName("a failed send must log no guest phone and no message body")
    void should_logNoPii_when_aSendFails() {
        doThrow(new SmsDeliveryException("provider rejected +38050000000"))
                .when(smsService).send(anyString(), anyString());

        new GuestReminderDispatcher(smsService, new SyncTaskExecutor(), new SyncTaskExecutor()).onGuestRemindersDue(event(1));

        assertThat(logAppender.list)
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getFormattedMessage())
                        .doesNotContain("+38050000000")
                        .doesNotContain("Манікюр"));
    }

    /**
     * <b>The load-bearing test of this class.</b> Every reminder in the batch must be delivered, however
     * large the batch is relative to the pool.
     *
     * <p>A submission the executor refuses is not a delayed reminder, it is a lost one:
     * {@code reminderSent = true} is committed before the dispatcher ever runs, so no later sweep
     * re-selects that booking and the guest is simply never reminded. The shipped pool (core 2 / max 8 /
     * queue 500, {@code AbortPolicy}) dropped everything past 508 — measured at N=1000, 492 guests lost —
     * and the batch size is influenceable: the booking endpoint that feeds it is {@code permitAll} with a
     * caller-chosen {@code startsAt}, so one verified phone can stack a single hour bucket.
     *
     * <p>Run against the REAL bean, not a test-local pool, because the defect lived in the bean's sizing.
     * The workers are gated so the queue genuinely saturates before the tail is submitted — without that,
     * 8 workers drain 1000 no-op sends as fast as they arrive, the queue never fills, and the test would
     * pass against {@code AbortPolicy} too (a defanged assertion, not a regression net).
     */
    @Test
    @DisplayName("should deliver every reminder — never drop the tail — when the batch overflows the queue")
    void should_deliverEveryReminder_when_theBatchOverflowsTheQueue() throws Exception {
        ThreadPoolTaskExecutor bean = (ThreadPoolTaskExecutor) new AsyncConfig().smsReminderExecutor();
        ThreadPoolExecutor jdkPool = bean.getThreadPoolExecutor();
        int acceptedOutright = jdkPool.getQueue().remainingCapacity() + jdkPool.getMaximumPoolSize();
        int batch = 1000;
        assertThat(batch)
                .as("the batch must exceed queue + pool (%s) or saturation is never reached", acceptedOutright)
                .isGreaterThan(acceptedOutright);

        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(batch);
        doAnswer(inv -> {
            releaseWorkers.await(30, TimeUnit.SECONDS);
            delivered.countDown();
            return null;
        }).when(smsService).send(anyString(), anyString());

        Thread submitter = new Thread(
                () -> new GuestReminderDispatcher(smsService, new SyncTaskExecutor(), bean).onGuestRemindersDue(event(batch)),
                "test-publisher");
        try {
            submitter.start();
            boolean saturated = awaitQueueSaturated(jdkPool);
            // Release BEFORE asserting and before the join: with CallerBlocksPolicy the submitter is
            // parked on a full queue whose workers are all gated, so joining first would deadlock.
            releaseWorkers.countDown();
            assertThat(saturated)
                    .as("the queue never filled — the pool drained faster than the test could saturate it, "
                            + "so this run proves nothing about the overflow path")
                    .isTrue();
            submitter.join(TimeUnit.SECONDS.toMillis(60));
            assertThat(delivered.await(60, TimeUnit.SECONDS))
                    .as("%s of %s reminders never reached the provider — each is a guest who is never "
                            + "reminded and never retried, because reminderSent is already committed",
                            delivered.getCount(), batch)
                    .isTrue();
        } finally {
            // Safety net for the failure path: an unreleased gate parks 8 non-daemon workers forever
            // and an un-shut pool hangs the Gradle test JVM at exit. countDown is idempotent at zero.
            releaseWorkers.countDown();
            bean.shutdown();
        }

        verify(smsService, times(batch)).send(anyString(), anyString());
        assertThat(logAppender.list)
                .as("nothing was dropped, so nothing may be reported as dropped")
                .noneSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }

    /**
     * <b>The connection-hold net.</b> The listener runs on the thread that just committed, and that thread
     * has NOT yet returned its Hikari connection — Spring releases it at {@code afterCompletion}, one phase
     * later. Measured against a real commit: {@code hikariActive=1} and Hibernate
     * {@code physicallyConnected=true} for the entire listener body, 0 only after completion; a 12 s body
     * trips Hikari's {@code leak-detection-threshold: 10000}.
     *
     * <p>So every millisecond the listener spends is a millisecond of pooled-connection hold, against a
     * production pool of 10. Doing the fan-out inline — as an earlier revision did — parked the listener on
     * the full send queue for a measured 3193 ms at N=1000 / 50 ms per send, i.e. it re-created the exact
     * MEDIUM this class exists to remove, moved from inside the transaction to just after it. At Turbosms's
     * 5 s read-timeout cap the same drain model gives ~5 minutes, hourly.
     *
     * <p>This test pins the fix at its seam: with every send blocked and the send queue saturating, the
     * listener must still RETURN. Run on its own thread and joined with a deadline so the inline-fan-out
     * regression fails the assertion instead of hanging the suite.
     */
    @Test
    @DisplayName("the after-commit listener must return while every send is still blocked — it holds a DB connection")
    void should_returnWhileEverySendIsStillBlocked_when_theBatchOverflowsTheQueue() throws Exception {
        AsyncConfig config = new AsyncConfig();
        ThreadPoolTaskExecutor sendPool = (ThreadPoolTaskExecutor) config.smsReminderExecutor();
        ThreadPoolTaskExecutor dispatchPool = (ThreadPoolTaskExecutor) config.smsReminderDispatchExecutor();
        int batch = 1000;

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(batch);
        doAnswer(inv -> {
            gate.await(60, TimeUnit.SECONDS);
            delivered.countDown();
            return null;
        }).when(smsService).send(anyString(), anyString());

        Thread committer = new Thread(
                () -> new GuestReminderDispatcher(smsService, dispatchPool, sendPool)
                        .onGuestRemindersDue(event(batch)),
                "test-committer");
        try {
            committer.start();
            committer.join(TimeUnit.SECONDS.toMillis(15));
            boolean listenerReturned = !committer.isAlive();

            assertThat(gate.getCount())
                    .as("fixture sanity: no send may have completed yet, or the pool was never blocked")
                    .isEqualTo(1);
            assertThat(awaitQueueSaturated(sendPool.getThreadPoolExecutor()))
                    .as("the send queue never filled, so this run proves nothing about the blocking path")
                    .isTrue();
            assertThat(listenerReturned)
                    .as("the listener is still parked on the send queue while holding a pooled Hikari "
                            + "connection — this is backend-perf MEDIUM, relocated to after the commit")
                    .isTrue();

            gate.countDown();
            assertThat(delivered.await(60, TimeUnit.SECONDS))
                    .as("%s of %s reminders never reached the provider after the hand-off",
                            delivered.getCount(), batch)
                    .isTrue();
        } finally {
            gate.countDown();
            committer.join(TimeUnit.SECONDS.toMillis(10));
            dispatchPool.shutdown();
            sendPool.shutdown();
        }
    }

    /**
     * The O(1) half of the same contract, asserted structurally rather than by timing: whatever the batch
     * size, the connection-holding thread performs exactly ONE submission. A per-reminder loop moved back
     * into the listener fails here even on a pool fast enough never to saturate.
     */
    @Test
    @DisplayName("the listener must submit exactly one hand-off task, however large the batch")
    void should_submitOneHandOffTask_when_theBatchIsLarge() {
        AtomicInteger handOffs = new AtomicInteger();
        TaskExecutor countingDispatch = task -> {
            handOffs.incrementAndGet();
            task.run();
        };

        new GuestReminderDispatcher(smsService, countingDispatch, new SyncTaskExecutor())
                .onGuestRemindersDue(event(50));

        assertThat(handOffs)
                .as("work on the committing thread must be O(1) — it is paid in pooled-connection hold time")
                .hasValue(1);
        verify(smsService, times(50)).send(anyString(), anyString());
    }

    /**
     * A refused hand-off costs the WHOLE batch, not a tail, so it must be loud. It must also not escape:
     * Spring propagates throwables out of {@code afterCommit} (unlike {@code afterCompletion}), so an
     * escaping rejection would report an already-committed sweep as failed.
     */
    @Test
    @DisplayName("a refused hand-off must be logged at ERROR and must not escape the after-commit listener")
    void should_logAtErrorAndNotThrow_when_theHandOffIsRejected() {
        TaskExecutor alwaysRejects = task -> {
            throw new TaskRejectedException("dispatch queue full");
        };

        assertThatCode(() -> new GuestReminderDispatcher(smsService, alwaysRejects, new SyncTaskExecutor())
                .onGuestRemindersDue(event(3)))
                .as("an escaping throwable fails an already-committed sweep")
                .doesNotThrowAnyException();

        verifyNoInteractions(smsService);
        assertThat(logAppender.list)
                .as("a whole dropped batch must not vanish silently")
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(e.getFormattedMessage()).contains("3 reminders not dispatched");
                });
    }

    /**
     * {@code catch (RuntimeException)} left {@link Error} escaping into the JVM's default uncaught handler
     * — a raw stderr stack trace that bypasses the production JSON encoder, so the reminder is lost with
     * no structured record. {@code SmsService} sits behind an HTTP client and a JSON codec, both able to
     * surface {@code NoClassDefFoundError} rather than a {@code RuntimeException}.
     */
    @Test
    @DisplayName("an Error from one send must be contained and logged, not escape to the default handler")
    void should_containAndLogTheFailure_when_aSendThrowsAnError() {
        doThrow(new NoClassDefFoundError("okhttp3/internal/Util"))
                .when(smsService).send(eq("+38050000000"), anyString());

        assertThatCode(() -> new GuestReminderDispatcher(smsService, new SyncTaskExecutor(), new SyncTaskExecutor())
                .onGuestRemindersDue(event(2)))
                .as("an escaping Error bypasses the log encoder entirely")
                .doesNotThrowAnyException();

        verify(smsService).send(eq("+38050000001"), anyString());
        assertThat(logAppender.list)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("NoClassDefFoundError"));
    }

    /**
     * Shutdown interrupts in-flight sends. Swallowing the flag leaves the worker looking healthy and
     * taking more tasks from a pool that is trying to stop. The chain — not just the top type — is
     * inspected because the SMS client wraps an interrupted socket read.
     */
    @Test
    @DisplayName("the interrupt flag must be restored when a send fails with InterruptedException in its cause chain")
    void should_restoreTheInterruptFlag_when_aSendIsInterrupted() {
        doThrow(new SmsDeliveryException("send failed",
                new IOException("socket read", new InterruptedException("pool shutting down"))))
                .when(smsService).send(anyString(), anyString());

        new GuestReminderDispatcher(smsService, new SyncTaskExecutor(), new SyncTaskExecutor()).onGuestRemindersDue(event(1));

        // Thread.interrupted() reads AND clears, so the flag cannot leak into a sibling test.
        assertThat(Thread.interrupted())
                .as("a cleared interrupt makes a worker keep pulling tasks off a pool that is stopping")
                .isTrue();
    }

    @Test
    @DisplayName("the interrupt flag must stay clear when a send fails for an ordinary provider reason")
    void should_leaveTheInterruptFlagClear_when_aSendFailsWithoutInterruption() {
        doThrow(new SmsDeliveryException("provider timeout"))
                .when(smsService).send(anyString(), anyString());

        new GuestReminderDispatcher(smsService, new SyncTaskExecutor(), new SyncTaskExecutor()).onGuestRemindersDue(event(1));

        assertThat(Thread.interrupted())
                .as("blanket-interrupting on any failure would cancel unrelated work on a shared thread")
                .isFalse();
    }

    /**
     * The cause walk runs on an {@code sms-reminder-*} worker, over throwables this code did NOT build —
     * they come out of an HTTP client and a JSON codec. {@link Throwable#initCause} rejects only
     * SELF-causation, so {@code a.initCause(b); b.initCause(a);} is a perfectly legal 2-cycle that a
     * self-cycle guard walks forever: one worker pinned permanently, and enough of them wedge the pool
     * into silent reminder loss (every subsequent batch parks on CallerBlocksPolicy and never drains).
     *
     * <p>A genuine 2-cycle is used rather than a synthetic depth probe, because that is the shape the
     * self-cycle guard actually misses — a depth-only assertion would still pass against the buggy
     * version. It is run under {@code assertTimeoutPreemptively} so the regression FAILS at 5 s instead of
     * hanging the Gradle test JVM; JUnit's timeout thread is a daemon, so the spinning walk cannot block
     * JVM exit either.
     */
    @Test
    @DisplayName("a cyclic cause chain must terminate the walk, not pin the worker forever")
    void should_terminateTheCauseWalk_when_theCauseChainIsCyclic() {
        RuntimeException fromHttpClient = new RuntimeException("connection reset");
        RuntimeException fromJsonCodec = new RuntimeException("malformed provider response");
        fromHttpClient.initCause(fromJsonCodec);
        fromJsonCodec.initCause(fromHttpClient);
        doThrow(fromHttpClient).when(smsService).send(anyString(), anyString());
        AtomicBoolean interruptedAfterwards = new AtomicBoolean();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            new GuestReminderDispatcher(smsService, new SyncTaskExecutor(), new SyncTaskExecutor())
                    .onGuestRemindersDue(event(1));
            // Read-and-clear inside the timed body so the flag cannot leak into a sibling test.
            interruptedAfterwards.set(Thread.interrupted());
        }, "the cause walk never terminated — one sms-reminder worker is pinned forever and the pool "
                + "degrades into silent reminder loss");

        assertThat(interruptedAfterwards)
                .as("no InterruptedException is anywhere in the cycle, so nothing may be interrupted")
                .isFalse();
        assertThat(logAppender.list)
                .as("the failure must still be reported once the walk gives up")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("RuntimeException"));
    }

    /**
     * Ordering, not just presence. Restoring the interrupt flag BEFORE the log call makes the diagnostic's
     * survival a property of the appender rather than of this code: Logback's {@code putUninterruptibly}
     * happens to cope, but an async/queue-backed appender parking on a full queue, or any appender doing
     * interruptible I/O, would drop or truncate the one line that explains why the worker is stopping.
     *
     * <p>Asserted by sampling the flag from inside an appender — the exact moment the ordering governs.
     */
    @Test
    @DisplayName("the failure must be logged before the interrupt flag is restored")
    void should_logBeforeRestoringTheInterruptFlag_when_aSendIsInterrupted() {
        AtomicReference<Boolean> flagWhileLogging = new AtomicReference<>();
        AppenderBase<ILoggingEvent> flagProbe = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent unused) {
                flagWhileLogging.compareAndSet(null, Thread.currentThread().isInterrupted());
            }
        };
        flagProbe.start();
        dispatcherLogger.addAppender(flagProbe);
        doThrow(new SmsDeliveryException("send failed", new InterruptedException("pool shutting down")))
                .when(smsService).send(anyString(), anyString());

        try {
            new GuestReminderDispatcher(smsService, new SyncTaskExecutor(), new SyncTaskExecutor())
                    .onGuestRemindersDue(event(1));
        } finally {
            dispatcherLogger.detachAppender(flagProbe);
            flagProbe.stop();
        }

        assertThat(flagWhileLogging)
                .as("the appender was entered with the interrupt flag already raised — whether the "
                        + "diagnostic survives is then the appender's choice, not this code's")
                .hasValue(false);
        assertThat(Thread.interrupted())
                .as("the flag must still be restored — just afterwards")
                .isTrue();
    }

    /** Spins (no sleep, no fixed wait) until the pool's queue has no room left, or the deadline passes. */
    private static boolean awaitQueueSaturated(ThreadPoolExecutor pool) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (pool.getQueue().remainingCapacity() == 0) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private static GuestRemindersDueEvent event(int count) {
        return new GuestRemindersDueEvent(IntStream.range(0, count)
                .mapToObj(i -> new GuestReminderSms("+3805000000" + i, "Манікюр о 10:00"))
                .toList());
    }

    private static ThreadPoolTaskExecutor pool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("sms-reminder-");
        executor.initialize();
        return executor;
    }
}
