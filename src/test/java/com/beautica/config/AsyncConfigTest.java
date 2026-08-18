package com.beautica.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextTaskExecutor;

/**
 * Pins the thread-pool configuration of {@link AsyncConfig}'s {@code emailExecutor} and
 * {@code pushExecutor} beans, plus the presence of the {@link AsyncUncaughtExceptionHandler}.
 *
 * <p>Sibling test {@code AsyncConfigSecurityContextPropagationTest} verifies the runtime
 * propagation behaviour (SecurityContext + context classloader); this class verifies the
 * <em>static configuration contract</em>: core/max pool sizes, queue capacity, thread-name
 * prefix, and the {@link ThreadPoolExecutor.AbortPolicy} rejection policy. A "tuning" PR that
 * silently changes a pool size, drops the AbortPolicy (defaulting to the silent-discard or
 * caller-runs policy), or removes the uncaught-exception handler fails here fast.
 *
 * <p>The beans are wrapped in {@link DelegatingSecurityContextTaskExecutor}, which hides the
 * underlying {@link ThreadPoolTaskExecutor}. We unwrap via the documented {@code delegate}
 * field on {@code DelegatingSecurityContextExecutor} to read the configured JDK
 * {@link ThreadPoolExecutor}. Direct instantiation (no Spring context) keeps the test fast and
 * pins exactly what the {@code @Bean} factory methods configure.
 */
@DisplayName("AsyncConfig — executor pool configuration and uncaught-exception handler")
class AsyncConfigTest {

    private final AsyncConfig asyncConfig = new AsyncConfig();

    @Nested
    @DisplayName("emailExecutor bean")
    class EmailExecutor {

        private final ThreadPoolExecutor pool = unwrapJdkPool(asyncConfig.emailExecutor());

        @Test
        @DisplayName("emailExecutor — core pool size is 2")
        void should_configureCorePoolSizeOfTwo_when_emailExecutorBuilt() {
            assertThat(pool.getCorePoolSize())
                    .as("emailExecutor core pool size")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("emailExecutor — max pool size is 5")
        void should_configureMaxPoolSizeOfFive_when_emailExecutorBuilt() {
            assertThat(pool.getMaximumPoolSize())
                    .as("emailExecutor max pool size")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("emailExecutor — queue capacity is 150")
        void should_configureQueueCapacityOf150_when_emailExecutorBuilt() {
            // remainingCapacity == configured capacity while the queue is empty.
            assertThat(pool.getQueue().remainingCapacity())
                    .as("emailExecutor queue capacity")
                    .isEqualTo(150);
        }

        @Test
        @DisplayName("emailExecutor — rejection policy is AbortPolicy")
        void should_useAbortPolicy_when_emailExecutorSaturated() {
            assertThat(pool.getRejectedExecutionHandler())
                    .as("emailExecutor rejection policy")
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        }

        @Test
        @DisplayName("emailExecutor — worker threads use the 'email-' name prefix")
        void should_nameWorkerThreadsWithEmailPrefix_when_taskSubmitted() throws InterruptedException {
            assertThat(captureWorkerThreadName(asyncConfig.emailExecutor()))
                    .as("emailExecutor worker thread name")
                    .startsWith("email-");
        }
    }

    @Nested
    @DisplayName("pushExecutor bean")
    class PushExecutor {

        private final ThreadPoolExecutor pool = unwrapJdkPool(asyncConfig.pushExecutor());

        @Test
        @DisplayName("pushExecutor — core pool size is 4")
        void should_configureCorePoolSizeOfFour_when_pushExecutorBuilt() {
            assertThat(pool.getCorePoolSize())
                    .as("pushExecutor core pool size")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("pushExecutor — max pool size is 20")
        void should_configureMaxPoolSizeOf20_when_pushExecutorBuilt() {
            assertThat(pool.getMaximumPoolSize())
                    .as("pushExecutor max pool size")
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("pushExecutor — queue capacity is 200")
        void should_configureQueueCapacityOf200_when_pushExecutorBuilt() {
            assertThat(pool.getQueue().remainingCapacity())
                    .as("pushExecutor queue capacity")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("pushExecutor — rejection policy is AbortPolicy")
        void should_useAbortPolicy_when_pushExecutorSaturated() {
            assertThat(pool.getRejectedExecutionHandler())
                    .as("pushExecutor rejection policy")
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        }

        @Test
        @DisplayName("pushExecutor — worker threads use the 'push-' name prefix")
        void should_nameWorkerThreadsWithPushPrefix_when_taskSubmitted() throws InterruptedException {
            assertThat(captureWorkerThreadName(asyncConfig.pushExecutor()))
                    .as("pushExecutor worker thread name")
                    .startsWith("push-");
        }
    }

    /**
     * The guest 24h reminder pool. Unlike its siblings this bean is NOT wrapped for SecurityContext
     * propagation (the publisher is a {@code @Scheduled} sweep with no principal), so it is read
     * directly rather than through {@link #unwrapJdkPool}.
     *
     * <p>Every assertion here exists because the shipped shape (core 2 / max 8 / queue 500 /
     * AbortPolicy) was wrong in two compounding ways, both invisible to a test that only counts
     * annotations: a {@link ThreadPoolExecutor} grows past its core size ONLY once the queue is full,
     * so "max 8" was really "concurrency 2" for every batch under 501; and AbortPolicy then discarded
     * the tail of any batch over 508 — reminders whose {@code reminderSent} flag is already committed,
     * so nothing ever retries them.
     */
    @Nested
    @DisplayName("smsReminderExecutor bean")
    class SmsReminderExecutor {

        private final ThreadPoolTaskExecutor bean = (ThreadPoolTaskExecutor) asyncConfig.smsReminderExecutor();
        private final ThreadPoolExecutor pool = bean.getThreadPoolExecutor();

        /**
         * The load-bearing sizing assertion. {@code corePoolSize < maxPoolSize} on a pool with a bounded
         * queue is not "elastic up to max" — it is "fixed at core until the queue fills". Core must equal
         * max for the advertised concurrency to be the real one.
         */
        @Test
        @DisplayName("smsReminderExecutor — core equals max, so the advertised concurrency is the real one")
        void should_makeCoreEqualMax_when_smsReminderExecutorBuilt() {
            assertThat(pool.getCorePoolSize())
                    .as("core=%s below max=%s means a bounded-queue pool never grows until the queue is "
                                    + "full — effective concurrency would be the core size, not the max",
                            pool.getCorePoolSize(), pool.getMaximumPoolSize())
                    .isEqualTo(pool.getMaximumPoolSize());
        }

        @Test
        @DisplayName("smsReminderExecutor — pool size is 8")
        void should_configurePoolSizeOfEight_when_smsReminderExecutorBuilt() {
            assertThat(pool.getMaximumPoolSize())
                    .as("smsReminderExecutor max pool size")
                    .isEqualTo(8);
        }

        /**
         * The cost of core == max: without core-thread timeout, an HOURLY job would hold 8 threads
         * parked for the other 59 minutes.
         */
        @Test
        @DisplayName("smsReminderExecutor — idle core threads time out, so an hourly job parks no threads")
        void should_allowCoreThreadTimeOut_when_smsReminderExecutorBuilt() {
            assertThat(pool.allowsCoreThreadTimeOut())
                    .as("core=max without core timeout parks 8 idle threads between hourly sweeps")
                    .isTrue();
        }

        @Test
        @DisplayName("smsReminderExecutor — queue capacity is 500")
        void should_configureQueueCapacityOf500_when_smsReminderExecutorBuilt() {
            assertThat(pool.getQueue().remainingCapacity())
                    .as("smsReminderExecutor queue capacity")
                    .isEqualTo(500);
        }

        /**
         * AbortPolicy here is a silent notification loss, not back-pressure: the reminder's
         * {@code reminderSent} flag is committed before dispatch, so a discarded task is a guest who is
         * never reminded and never retried.
         */
        @Test
        @DisplayName("smsReminderExecutor — saturation blocks the submitter, it never discards a reminder")
        void should_useCallerBlocksPolicy_when_smsReminderExecutorSaturated() {
            assertThat(pool.getRejectedExecutionHandler())
                    .as("a discarding policy loses reminders permanently — the flag is already committed")
                    .isInstanceOf(AsyncConfig.CallerBlocksPolicy.class)
                    .isNotInstanceOf(ThreadPoolExecutor.AbortPolicy.class)
                    .isNotInstanceOf(ThreadPoolExecutor.DiscardPolicy.class)
                    .isNotInstanceOf(ThreadPoolExecutor.DiscardOldestPolicy.class);
        }

        @Test
        @DisplayName("smsReminderExecutor — worker threads use the 'sms-reminder-' name prefix")
        void should_nameWorkerThreadsWithSmsReminderPrefix_when_taskSubmitted() throws InterruptedException {
            assertThat(captureWorkerThreadName(bean))
                    .as("smsReminderExecutor worker thread name")
                    .startsWith("sms-reminder-");
        }

        /**
         * The behavioural counterpart to the core==max assertion, and the one that cannot be satisfied by
         * a configuration edit that merely looks right. Eight tasks are submitted back-to-back into an
         * EMPTY queue and each parks on a {@link CyclicBarrier} of 8: the barrier can only trip if all
         * eight are running at the same instant. Under the shipped core=2 shape, tasks 3–8 sit in the
         * (non-full) queue behind two blocked workers and the barrier times out.
         */
        @Test
        @DisplayName("smsReminderExecutor — 8 sends run concurrently from the first task, not 2")
        void should_runEightTasksConcurrently_when_theQueueIsEmpty() throws Exception {
            int width = pool.getMaximumPoolSize();
            CyclicBarrier allRunning = new CyclicBarrier(width);
            CountDownLatch tripped = new CountDownLatch(width);

            try {
                for (int i = 0; i < width; i++) {
                    bean.execute(() -> {
                        try {
                            allRunning.await(10, TimeUnit.SECONDS);
                            tripped.countDown();
                        } catch (Exception e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }

                assertThat(tripped.await(15, TimeUnit.SECONDS))
                        .as("only %s of %s tasks were running at once — a bounded-queue pool with "
                                        + "core < max stays at core until the queue fills",
                                width - tripped.getCount(), width)
                        .isTrue();
            } finally {
                // ThreadPoolTaskExecutor workers are NON-daemon: leaking them on a failed assertion
                // hangs the Gradle test JVM at exit instead of just failing the test.
                bean.shutdown();
            }
        }
    }

    /**
     * The hand-off pool that keeps the after-commit listener O(1).
     *
     * <p>It exists because a {@code @TransactionalEventListener(AFTER_COMMIT)} body still holds the
     * committing thread's pooled Hikari connection — Spring releases it at {@code afterCompletion}, one
     * phase later. Measured against a real commit on Testcontainers PostgreSQL: {@code hikariActive=1} and
     * Hibernate {@code physicallyConnected=true} throughout the listener body, 0 only after completion,
     * with a 12 s body tripping Hikari's {@code leak-detection-threshold: 10000}. Fanning a batch out
     * directly from the listener therefore pays drain time in pooled-connection hold — measured at 3193 ms
     * for N=1000 at 50 ms per send, and ~5 minutes at Turbosms's 5 s read-timeout cap.
     */
    @Nested
    @DisplayName("smsReminderDispatchExecutor bean")
    class SmsReminderDispatchExecutor {

        private final ThreadPoolTaskExecutor bean =
                (ThreadPoolTaskExecutor) asyncConfig.smsReminderDispatchExecutor();
        private final ThreadPoolExecutor pool = bean.getThreadPoolExecutor();

        /**
         * Single-threaded on purpose. Widening it would let two sweeps fan out concurrently into the same
         * send pool, so each could park the other behind a full queue for no gain — the concurrency that
         * matters is the send pool's 8, not this one's.
         */
        @Test
        @DisplayName("smsReminderDispatchExecutor — exactly one dispatch thread")
        void should_useASingleDispatchThread_when_built() {
            assertThat(pool.getCorePoolSize()).as("dispatch core pool size").isEqualTo(1);
            assertThat(pool.getMaximumPoolSize()).as("dispatch max pool size").isEqualTo(1);
        }

        @Test
        @DisplayName("smsReminderDispatchExecutor — the idle dispatch thread times out between hourly sweeps")
        void should_allowCoreThreadTimeOut_when_built() {
            assertThat(pool.allowsCoreThreadTimeOut())
                    .as("an hourly job must not park a thread for the other 59 minutes")
                    .isTrue();
        }

        /**
         * A rejection here drops a WHOLE batch rather than a tail, so the queue must absorb several
         * consecutive sweeps whose fan-out overran the hour.
         */
        @Test
        @DisplayName("smsReminderDispatchExecutor — queue absorbs eight backed-up sweeps")
        void should_configureQueueCapacityOfEight_when_built() {
            assertThat(pool.getQueue().remainingCapacity()).as("dispatch queue capacity").isEqualTo(8);
        }

        /**
         * AbortPolicy, NOT CallerBlocksPolicy: blocking HERE would block the committing thread, which is
         * precisely the connection-hold this bean exists to prevent.
         */
        @Test
        @DisplayName("smsReminderDispatchExecutor — never blocks the committing thread on saturation")
        void should_useAbortPolicy_when_dispatchQueueSaturated() {
            assertThat(pool.getRejectedExecutionHandler())
                    .as("a blocking policy here parks the thread that still holds a pooled DB connection")
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class)
                    .isNotInstanceOf(AsyncConfig.CallerBlocksPolicy.class);
        }

        @Test
        @DisplayName("smsReminderDispatchExecutor — worker thread uses the 'sms-reminder-dispatch-' name prefix")
        void should_nameTheDispatchThread_when_taskSubmitted() throws InterruptedException {
            assertThat(captureWorkerThreadName(bean))
                    .as("dispatch worker thread name")
                    .startsWith("sms-reminder-dispatch-");
        }
    }

    /**
     * The master-availability cache-eviction pool (Perf MEDIUM-3), previously untested in any shape.
     *
     * <p>The bean is annotated {@code @Profile("!test")}, so no integration test ever instantiates it
     * — {@code TestAsyncConfig} substitutes a synchronous stand-in. That makes this class the ONLY
     * place its production shape can be pinned, and it can do so because it calls the {@code @Bean}
     * factory method directly: {@code @Profile} is a container-time concern and has no effect on a
     * plain {@code new AsyncConfig()}.
     *
     * <p><b>Why the sizing is behaviour, not cosmetics</b> (backend-perf LOW, 2026-08-13). The bean
     * shipped as {@code core = 1 / max = 2 / queue = 1000}. That is not "up to two evictions at a
     * time": a {@link ThreadPoolExecutor} with a bounded queue grows past its core size only once
     * the queue is FULL, so this pool was effectively single-threaded in every state short of a
     * 1000-task backlog — a platform-wide serialization point for evictions that can each park
     * 50–200 ms on a {@code sync = true} reader's Neon round-trip. One master's slow eviction
     * therefore delayed every other master's. Raising the core to 2 is the fix; asserting
     * {@code core == max} is what stops it silently regressing to the same trap the
     * {@code smsReminderExecutor} above already fell into once.
     */
    @Nested
    @DisplayName("cacheEvictionExecutor bean")
    class CacheEvictionExecutor {

        private final ThreadPoolTaskExecutor bean = (ThreadPoolTaskExecutor) asyncConfig.cacheEvictionExecutor();
        private final ThreadPoolExecutor pool = bean.getThreadPoolExecutor();

        /**
         * The load-bearing sizing assertion, and the one that would have caught the shipped bug by
         * inspection: with a bounded queue, {@code core < max} means the advertised max is
         * unreachable in practice, so effective concurrency is the CORE, not the max.
         */
        @Test
        @DisplayName("cacheEvictionExecutor — core equals max, so the advertised concurrency is the real one")
        void should_makeCoreEqualMax_when_cacheEvictionExecutorBuilt() {
            assertThat(pool.getCorePoolSize())
                    .as("core=%s below max=%s means this bounded-queue pool never grows until its "
                                    + "1000-slot queue is full — effective eviction concurrency would be the "
                                    + "core size, making one slow master's sweep block every other master's",
                            pool.getCorePoolSize(), pool.getMaximumPoolSize())
                    .isEqualTo(pool.getMaximumPoolSize());
        }

        @Test
        @DisplayName("cacheEvictionExecutor — pool size is 2")
        void should_configurePoolSizeOfTwo_when_cacheEvictionExecutorBuilt() {
            assertThat(pool.getMaximumPoolSize())
                    .as("cacheEvictionExecutor max pool size")
                    .isEqualTo(2);
        }

        /**
         * The behavioural counterpart to {@code core == max}, and the assertion a configuration edit
         * that merely looks right cannot satisfy. Two tasks are submitted back-to-back into an EMPTY
         * queue and each parks on a {@link CyclicBarrier} of 2 — the barrier can only trip if both
         * are running at the same instant. Under the shipped {@code core = 1} shape the second task
         * sits in the (far from full) queue behind the first blocked worker and the barrier times
         * out: that is head-of-line blocking, reproduced directly.
         */
        @Test
        @DisplayName("cacheEvictionExecutor — a second eviction starts while the first is still "
                + "parked, so one master's slow sweep cannot head-of-line-block another's")
        void should_runTwoEvictionsConcurrently_when_theQueueIsEmpty() throws Exception {
            int width = pool.getMaximumPoolSize();
            CyclicBarrier allRunning = new CyclicBarrier(width);
            CountDownLatch tripped = new CountDownLatch(width);

            try {
                for (int i = 0; i < width; i++) {
                    bean.execute(() -> {
                        try {
                            allRunning.await(10, TimeUnit.SECONDS);
                            tripped.countDown();
                        } catch (Exception e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }

                assertThat(tripped.await(15, TimeUnit.SECONDS))
                        .as("only %s of %s evictions were running at once — a bounded-queue pool with "
                                        + "core < max stays at core until the queue fills, so the second "
                                        + "master's sweep waits behind the first master's Neon stall",
                                width - tripped.getCount(), width)
                        .isTrue();
            } finally {
                // ThreadPoolTaskExecutor workers are NON-daemon: leaking them on a failed assertion
                // hangs the Gradle test JVM at exit instead of just failing the test.
                bean.shutdown();
            }
        }

        @Test
        @DisplayName("cacheEvictionExecutor — queue capacity is 1000, so caller-runs stays pathological")
        void should_configureQueueCapacityOf1000_when_cacheEvictionExecutorBuilt() {
            assertThat(pool.getQueue().remainingCapacity())
                    .as("cacheEvictionExecutor queue capacity — the fallback policy below degrades "
                            + "eviction onto the committing thread, so the queue must make that rare")
                    .isEqualTo(1000);
        }

        /**
         * CallerRunsPolicy is a documented, deliberate deviation from the Anti-Bug §H-2 ban on
         * caller-runs (which targets pools whose tasks do NETWORK I/O). Here the task is a pure
         * in-memory Caffeine keyset scan, and a DISCARDING policy would serve stale availability for
         * the cache's full 60-second TTL — offering a client a day that is actually full, the exact
         * bug the eviction exists to prevent.
         */
        @Test
        @DisplayName("cacheEvictionExecutor — saturation runs the sweep on the caller, it never "
                + "discards an eviction (a dropped sweep serves stale availability for the full TTL)")
        void should_useCallerRunsPolicy_when_cacheEvictionQueueSaturated() {
            assertThat(pool.getRejectedExecutionHandler())
                    .as("a discarding policy would leave a full day advertised as bookable until the "
                            + "60s cache TTL expires")
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class)
                    .isNotInstanceOf(ThreadPoolExecutor.AbortPolicy.class)
                    .isNotInstanceOf(ThreadPoolExecutor.DiscardPolicy.class)
                    .isNotInstanceOf(ThreadPoolExecutor.DiscardOldestPolicy.class);
        }

        @Test
        @DisplayName("cacheEvictionExecutor — worker threads use the 'cache-evict-' name prefix")
        void should_nameWorkerThreadsWithCacheEvictPrefix_when_taskSubmitted() throws InterruptedException {
            assertThat(captureWorkerThreadName(bean))
                    .as("cacheEvictionExecutor worker thread name — the prefix is what makes an "
                            + "eviction stall identifiable in a thread dump")
                    .startsWith("cache-evict-");
        }
    }

    @Nested
    @DisplayName("CallerBlocksPolicy")
    class CallerBlocksPolicyBehaviour {

        /**
         * The policy parks the submitter on a full queue, so it MUST re-check shutdown while parked. A
         * blind {@code BlockingQueue.put()} on a pool stopped by {@code shutdownNow()} waits on a queue
         * nothing will ever drain, hanging JVM shutdown instead of surfacing a rejection the dispatcher
         * can log.
         */
        @Test
        @DisplayName("CallerBlocksPolicy — rejects instead of parking forever once the pool is shut down")
        void should_rejectRatherThanPark_when_thePoolIsShutDown() {
            ThreadPoolExecutor saturated = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
            saturated.shutdownNow();

            assertThatThrownBy(() ->
                    new AsyncConfig.CallerBlocksPolicy().rejectedExecution(() -> {}, saturated))
                    .as("parking on a queue that will never drain hangs shutdown")
                    .isInstanceOf(RejectedExecutionException.class);
        }

        /**
         * The property the whole policy exists for: a task refused by {@code execute()} must end up on the
         * queue, not on the floor. A discarded reminder is permanently lost — {@code reminderSent} is
         * already committed.
         */
        @Test
        @DisplayName("CallerBlocksPolicy — enqueues the task rather than discarding it")
        void should_enqueueTheTask_when_theQueueHasRoom() {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
            Runnable task = () -> {};
            try {
                new AsyncConfig.CallerBlocksPolicy().rejectedExecution(task, pool);

                assertThat(pool.getQueue())
                        .as("a rejection handler that loses the task is the bug this policy replaces")
                        .containsExactly(task);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * What shutdown ABANDONS must be countable.
     *
     * <p>Both reminder pools drain on shutdown, but only for {@code awaitTerminationSeconds}. Past that,
     * stock Spring logs a bare "Timed out while waiting for executor to terminate" — no count, no bean
     * identity — while up to a full queue of reminders is discarded. Those bookings already committed
     * {@code reminderSent = true}, so no later sweep re-selects them: the abandonment is permanent and,
     * without this line, invisible. The 30 s grace is deliberately NOT raised to cover the ~312 s
     * worst-case drain (it exceeds the platform's SIGTERM window, so the JVM would be killed mid-wait and
     * lose the report too, while every deploy pays the delay) — observability is the fix, not patience.
     */
    @Nested
    @DisplayName("reminder pools — shutdown abandonment reporting")
    class ReminderShutdownLossReporting {

        /** Short enough to keep the test fast; the production grace is asserted separately. */
        private static final int TEST_GRACE_SECONDS = 1;

        private ListAppender<ILoggingEvent> logAppender;
        private ch.qos.logback.classic.Logger configLogger;

        @BeforeEach
        void attachAppender() {
            logAppender = new ListAppender<>();
            logAppender.start();
            configLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AsyncConfig.class);
            configLogger.addAppender(logAppender);
        }

        @AfterEach
        void detachAppender() {
            configLogger.detachAppender(logAppender);
            logAppender.stop();
        }

        /**
         * The alert contract, field by field. Each one answers a distinct operational question, so each is
         * pinned individually rather than by a single message match: the token makes the line greppable,
         * the executor name says WHICH pool truncated (the dispatch pool can truncate independently, and a
         * task abandoned there is a whole batch rather than one guest), {@code queuedTasksAbandoned} is the
         * already-flagged work that will never be attempted, {@code inFlightTasksKilled} is the separate
         * population cut mid-request with unknown delivery state, and {@code graceSeconds} is what makes
         * the queued count interpretable at all.
         */
        @Test
        @DisplayName("an expired shutdown grace must report the abandoned counts at ERROR")
        void should_reportAbandonedWorkAtError_when_theShutdownGraceExpires() throws Exception {
            ILoggingEvent lossEvent = shutdownWithWorkStillPending(4);

            assertThat(lossEvent.getLevel())
                    .as("lost reminders must page someone, not sit at WARN behind a deploy log")
                    .isEqualTo(Level.ERROR);
            assertThat(lossEvent.getFormattedMessage())
                    .as("every field is load-bearing for the alert")
                    .contains(AsyncConfig.ReminderLossReportingTaskExecutor.LOSS_EVENT)
                    .contains("executor=smsReminderExecutor")
                    .contains("queuedTasksAbandoned=4")
                    .contains("inFlightTasksKilled=1")
                    .contains("graceSeconds=" + TEST_GRACE_SECONDS);
        }

        /**
         * Anti-Bug §I. The pool holds opaque {@link Runnable}s, and a report that rendered the queue to
         * name what was lost would put guest phone numbers and message bodies into a production ERROR log.
         * The queued tasks here carry both in {@code toString()}, so any such rendering fails here.
         */
        @Test
        @DisplayName("the abandonment report must carry counts only — never a phone or a message body")
        void should_reportCountsOnly_when_abandonedTasksCarryGuestData() throws Exception {
            ILoggingEvent lossEvent = shutdownWithWorkStillPending(4);

            assertThat(lossEvent.getFormattedMessage())
                    .as("counts identify how much was lost; rendering the queue would leak who")
                    .doesNotContain("+380501234567")
                    .doesNotContain("Манікюр");
        }

        /**
         * Silence on a clean shutdown is the other half of the contract. An ERROR on every deploy trains
         * the alert to be ignored, which costs exactly the visibility this line was added to buy.
         */
        @Test
        @DisplayName("a shutdown that drains everything must log nothing at ERROR")
        void should_logNothing_when_shutdownDrainsEveryTask() {
            AsyncConfig.ReminderLossReportingTaskExecutor executor = reportingExecutor();
            try {
                executor.execute(() -> { });
            } finally {
                executor.shutdown();
            }

            assertThat(logAppender.list)
                    .as("nothing was abandoned, so nothing may be reported as abandoned")
                    .noneSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
        }

        /**
         * The wiring net. The reporting behaviour is worthless if a later "tuning" edit rebuilds either
         * bean as a plain {@link ThreadPoolTaskExecutor} — the pools would go back to losing reminders
         * behind Spring's countless timeout warning.
         */
        @Test
        @DisplayName("both reminder pools must be built as loss-reporting executors")
        void should_buildBothReminderPoolsAsReportingExecutors_when_configured() {
            assertThat(asyncConfig.smsReminderExecutor())
                    .as("a plain executor abandons its queue silently")
                    .isInstanceOf(AsyncConfig.ReminderLossReportingTaskExecutor.class);
            assertThat(asyncConfig.smsReminderDispatchExecutor())
                    .as("a task abandoned here is an entire un-fanned-out batch")
                    .isInstanceOf(AsyncConfig.ReminderLossReportingTaskExecutor.class);
        }

        /**
         * Drives a real shutdown that genuinely times out: one worker is gated so it cannot finish, and
         * {@code queued} further tasks pile up behind it. Returns the single ERROR event produced.
         */
        private ILoggingEvent shutdownWithWorkStillPending(int queued) throws InterruptedException {
            AsyncConfig.ReminderLossReportingTaskExecutor executor = reportingExecutor();
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try {
                executor.execute(() -> {
                    running.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(running.await(5, TimeUnit.SECONDS))
                        .as("fixture: the single worker never picked up the gated task")
                        .isTrue();
                for (int i = 0; i < queued; i++) {
                    executor.execute(guestBearingTask());
                }

                executor.shutdown();
            } finally {
                // The gate must be released whatever happens: the worker is non-daemon and would
                // otherwise hang the Gradle test JVM at exit. countDown is idempotent.
                release.countDown();
                executor.getThreadPoolExecutor().shutdownNow();
            }

            List<ILoggingEvent> errors = logAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .toList();
            assertThat(errors)
                    .as("an expired grace abandoned %s queued task(s) — that must produce exactly one "
                            + "report", queued)
                    .hasSize(1);
            return errors.get(0);
        }

        private AsyncConfig.ReminderLossReportingTaskExecutor reportingExecutor() {
            AsyncConfig.ReminderLossReportingTaskExecutor executor =
                    new AsyncConfig.ReminderLossReportingTaskExecutor("smsReminderExecutor", TEST_GRACE_SECONDS);
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(50);
            executor.setThreadNamePrefix("sms-reminder-");
            executor.initialize();
            return executor;
        }

        /** A queued task whose rendering would leak exactly what must never reach a log line. */
        private static Runnable guestBearingTask() {
            return new Runnable() {
                @Override
                public void run() {
                    // never reached — abandoned at shutdown
                }

                @Override
                public String toString() {
                    return "+380501234567 Манікюр о 10:00";
                }
            };
        }
    }

    @Nested
    @DisplayName("uncaught-exception handler")
    class UncaughtHandler {

        @Test
        @DisplayName("getAsyncUncaughtExceptionHandler — returns a non-null handler so async failures are logged, not swallowed")
        void should_provideUncaughtExceptionHandler_when_asyncConfigQueried() {
            AsyncUncaughtExceptionHandler handler = asyncConfig.getAsyncUncaughtExceptionHandler();
            assertThat(handler)
                    .as("async uncaught-exception handler")
                    .isNotNull();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Unwraps the {@link DelegatingSecurityContextTaskExecutor} returned by the bean factory
     * down to the underlying JDK {@link ThreadPoolExecutor} so its configured properties can be
     * asserted. Initializes the wrapped {@link ThreadPoolTaskExecutor} if necessary.
     */
    private static ThreadPoolExecutor unwrapJdkPool(TaskExecutor bean) {
        assertThat(bean)
                .as("bean must be wrapped for SecurityContext propagation")
                .isInstanceOf(DelegatingSecurityContextTaskExecutor.class);
        Object delegate = readDelegateField(bean);
        assertThat(delegate)
                .as("unwrapped delegate executor")
                .isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor springExecutor = (ThreadPoolTaskExecutor) delegate;
        return springExecutor.getThreadPoolExecutor();
    }

    /** Reads the private {@code delegate} field of {@code DelegatingSecurityContextExecutor}. */
    private static Object readDelegateField(TaskExecutor bean) {
        try {
            Field field =
                    bean.getClass().getSuperclass().getDeclaredField("delegate");
            field.setAccessible(true);
            return field.get(bean);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "Could not unwrap DelegatingSecurityContextExecutor.delegate — "
                            + "Spring Security internal field renamed?",
                    e);
        }
    }

    /** Submits a task and returns the worker thread's name, proving the configured prefix. */
    private static String captureWorkerThreadName(TaskExecutor executor) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> name = new AtomicReference<>();
        executor.execute(() -> {
            name.set(Thread.currentThread().getName());
            done.countDown();
        });
        assertThat(done.await(5, TimeUnit.SECONDS))
                .as("worker task did not run within 5s")
                .isTrue();
        return name.get();
    }
}
