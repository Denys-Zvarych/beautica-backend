package com.beautica.config;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Shutdown grace shared by both guest-reminder pools, and the number reported in the abandonment log
     * line so the counts beside it are interpretable. Deliberately NOT raised to cover the worst-case
     * drain — see {@link #smsReminderExecutor()}.
     */
    static final int REMINDER_SHUTDOWN_GRACE_SECONDS = 30;

    // SMTP pool for invite and admin notification emails — FCM/APNs push gets a dedicated pool in Phase 5.8+.
    // Wrapped in DelegatingSecurityContextTaskExecutor so the calling thread's SecurityContext
    // (Authentication / actor identity) propagates into async tasks. Without this wrapper any
    // @Async method reading SecurityContextHolder.getContext().getAuthentication() observes null.
    @Bean(name = "emailExecutor")
    public TaskExecutor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(150);
        executor.setThreadNamePrefix("email-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        // Propagate the webapp classloader into email-* threads so that
        // ServiceLoader (jakarta.mail.util.StreamProvider) resolves META-INF/services/
        // entries from the application classpath rather than the system classloader.
        executor.setTaskDecorator(runnable -> {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return () -> {
                ClassLoader prev = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(cl);
                try {
                    runnable.run();
                } finally {
                    Thread.currentThread().setContextClassLoader(prev);
                }
            };
        });
        executor.initialize();
        return new DelegatingSecurityContextTaskExecutor(executor);
    }

    // Dedicated SMTP pool for Help/Contact-us support emails — isolated from emailExecutor
    // so a burst of ~5MB attachment sends cannot starve transactional auth/invite/password-reset
    // mail. Smaller pool (core 1, max 2, queue 20) because support traffic is low-volume and
    // already per-IP rate-limited (5/hr). Same SecurityContext + classloader propagation rationale
    // as emailExecutor (jakarta.mail StreamProvider ServiceLoader resolution).
    @Bean(name = "supportEmailExecutor")
    public TaskExecutor supportEmailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("support-email-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        // Propagate the webapp classloader into support-email-* threads so that
        // ServiceLoader (jakarta.mail.util.StreamProvider) resolves META-INF/services/
        // entries from the application classpath rather than the system classloader.
        executor.setTaskDecorator(runnable -> {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return () -> {
                ClassLoader prev = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(cl);
                try {
                    runnable.run();
                } finally {
                    Thread.currentThread().setContextClassLoader(prev);
                }
            };
        });
        executor.initialize();
        return new DelegatingSecurityContextTaskExecutor(executor);
    }

    // FCM/APNs push — dedicated pool to prevent SMTP starvation under push burst.
    // Same SecurityContext propagation rationale as emailExecutor.
    @Bean(name = "pushExecutor")
    public TaskExecutor pushExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("push-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // Same classloader propagation as emailExecutor — future ServiceLoader-based libs
        // (FCM SDK, APNs provider) need the webapp classloader on push-* threads.
        executor.setTaskDecorator(runnable -> {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return () -> {
                ClassLoader prev = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(cl);
                try {
                    runnable.run();
                } finally {
                    Thread.currentThread().setContextClassLoader(prev);
                }
            };
        });
        executor.initialize();
        return new DelegatingSecurityContextTaskExecutor(executor);
    }

    /**
     * Single-thread hand-off pool that carries the guest 24h reminder batch OFF the committing thread
     * before {@code GuestReminderDispatcher} fans it out across {@link #smsReminderExecutor()}.
     *
     * <p><b>Why this bean exists — measured, not assumed.</b> A
     * {@code @TransactionalEventListener(AFTER_COMMIT)} body runs on the thread that committed, and that
     * thread is <em>still holding its pooled Hikari connection</em>: Spring releases it at
     * {@code afterCompletion}/{@code doCleanupAfterCompletion}, one phase LATER. Instrumenting a real
     * commit against Testcontainers PostgreSQL, sampling {@code HikariPoolMXBean} and Hibernate's
     * {@code LogicalConnection} from inside the listener:
     *
     * <pre>
     * inside tx (post-query) : hikariActive=1  physicallyConnected=true   emBound=true
     * afterCommit ENTRY      : hikariActive=1  physicallyConnected=true   emBound=true
     * afterCommit EXIT (+4s) : hikariActive=1  physicallyConnected=true   emBound=true
     * after tx completion    : hikariActive=0  physicallyConnected=n/a    emBound=false
     * </pre>
     *
     * A 12 s listener body additionally tripped Hikari's own {@code leak-detection-threshold: 10000}
     * ("Apparent connection leak detected"). So <b>every millisecond spent in the listener is a
     * millisecond of pooled-connection hold</b>, against a production pool capped at 10 (Neon).
     *
     * <p>That makes a BLOCKING submit loop in the listener a straight re-introduction of the very
     * backend-perf MEDIUM this whole change removed — it merely relocates "N × provider RTT while pinning
     * a connection" from inside the transaction to immediately after it. Measured with the real dispatcher
     * and the real pool, N=1000 at 50 ms/send: the listener held the connection for <b>3193 ms</b> (peak
     * {@code activeConnections}=1 throughout), matching the drain model
     * {@code (N − queue − pool) / poolSize × RTT}. Extrapolating that validated model to Turbosms's own
     * caps gives 6.2 s at a healthy 100 ms RTT and <b>~5 minutes</b> at the 5 s read-timeout cap — every
     * hour, on the hour.
     *
     * <p>So the listener now submits exactly ONE task here and returns. The N-wide fan-out runs on
     * {@code sms-reminder-dispatch-0}, a thread that holds no DB connection, serves no HTTP request and
     * blocks no scheduler slot — which is what makes {@link CallerBlocksPolicy} on the send pool
     * affordable. A separate pool rather than self-submission into {@code smsReminderExecutor}: a pool
     * whose own tasks block on its own queue can deadlock once every worker is a blocked coordinator.
     * Two pools with disjoint roles cannot form that cycle. (Anti-Bug §H-4 targets duplicate pools doing
     * the SAME work; these do different work, and the split is the deadlock-safety property.)
     *
     * <p>Queue 8 with {@code AbortPolicy}: the only publisher is an hourly cron, so one in-flight batch is
     * the norm and 8 absorbs eight consecutive sweeps whose fan-out overran the hour. A rejection here
     * drops a WHOLE batch, so the dispatcher logs it at ERROR rather than letting it pass as a count.
     *
     * <p>Shutdown truncation is reported by {@link ReminderLossReportingTaskExecutor}, independently of the
     * send pool: a task abandoned HERE is an entire un-fanned-out batch, which is why the report carries
     * the bean name.
     */
    @Bean(name = "smsReminderDispatchExecutor")
    public TaskExecutor smsReminderDispatchExecutor() {
        ThreadPoolTaskExecutor executor =
                new ReminderLossReportingTaskExecutor("smsReminderDispatchExecutor", REMINDER_SHUTDOWN_GRACE_SECONDS);
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("sms-reminder-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Pool for the hourly guest 24h reminder SMS batch ({@code GuestReminderDispatcher}) — backend-perf
     * MEDIUM.
     *
     * <p>The reminder sweep used to make its N serial blocking Turbosms calls (3 s connect / 5 s read)
     * INSIDE its {@code @Transactional} method, holding a Hikari connection for the whole batch. The sends
     * now run here, strictly after that transaction commits, and concurrently rather than serially.
     *
     * <p><b>Sizing: {@code corePoolSize == maxPoolSize}, deliberately.</b> A {@link ThreadPoolExecutor}
     * only grows past its core size once the QUEUE IS FULL, so the earlier core=2 / max=8 / queue=500
     * shape delivered an effective concurrency of <b>2</b> for every batch under 501 — i.e. every real
     * batch — and the 8 threads it advertised existed only in the pathological case. Core and max are now
     * both 8, so all 8 workers are live from the first task; {@code allowCoreThreadTimeOut} lets them die
     * back after 60 s idle, so an hourly job does not hold 8 parked threads for the other 59 minutes.
     *
     * <p><b>{@code CallerBlocksPolicy}, NOT AbortPolicy — Anti-Bug §H-2 still honoured.</b> A rejected
     * reminder is a PERMANENTLY LOST one: {@code reminderSent = true} is already committed, so no later
     * sweep re-selects it. With AbortPolicy a batch of N &gt; queue + pool silently dropped its tail
     * (measured: N=1000 → 492 guests never reminded), and the booking endpoint that feeds this batch is
     * {@code permitAll} with a caller-chosen {@code startsAt} and no per-phone cap, so stacking one
     * hour-bucket is reachable. {@link CallerBlocksPolicy} instead back-pressures the submitter onto the
     * queue. This is not the {@code CallerRunsPolicy} that §H-2 bans: caller-runs would EXECUTE a blocking
     * Turbosms HTTP call on the caller, whereas this only parks it until a slot frees.
     *
     * <p><b>What makes that park affordable is {@link #smsReminderDispatchExecutor()}, not this bean.</b>
     * The submitter is NOT the committing thread — see the measurements on that bean, which show an
     * after-commit listener still pinning its Hikari connection. The submitter is
     * {@code sms-reminder-dispatch-0}, which holds nothing; parking it costs one idle thread and no
     * pooled resource. Do not move the fan-out back into the listener.
     *
     * <p><b>Shutdown drains for at most {@value #REMINDER_SHUTDOWN_GRACE_SECONDS} s, then abandons the
     * rest — and says so.</b> {@code reminderSent = true} is committed before dispatch, so a queued task
     * discarded at shutdown is a reminder that will never be retried.
     * {@code waitForTasksToCompleteOnShutdown} drains the queue rather than cancelling it, but
     * {@code awaitTerminationSeconds} caps how long that drain gets: a full queue of 500 sends at the
     * provider's 5 s read cap needs {@code 500 / 8 × 5 s ≈ 312 s}, so a 30 s grace completes only ~48 of
     * them and Spring then emits a bare "Timed out while waiting for executor to terminate" — up to ~450
     * already-flagged reminders vanishing per deploy with no count anywhere.
     *
     * <p><b>The grace is NOT raised to cover that worst case</b>, for three reasons. (1) 312 s exceeds the
     * platform's SIGTERM→SIGKILL window by a wide margin, so the JVM would be killed mid-wait and we would
     * lose both the reminders AND the report — a longer timeout buys nothing it can actually spend. (2) At
     * a healthy ~100 ms provider RTT the same full queue drains in ~6 s, so 30 s is already ~5× the
     * realistic requirement; the 5 s-per-send figure is a provider outage, in which case those sends are
     * failing rather than delivering. (3) The wait is paid on EVERY deploy, while the benefit exists only
     * in the pathological case. What was actually missing was observability, not patience — so
     * {@link ReminderLossReportingTaskExecutor} reports the abandoned counts at ERROR once the grace
     * expires.
     *
     * <p>No {@link DelegatingSecurityContextTaskExecutor} wrapper: the publisher is a
     * {@code @Scheduled} sweep with no {@code SecurityContext}, and the dispatcher reads none — it takes a
     * phone and a fully-rendered body, nothing principal-derived.
     */
    @Bean(name = "smsReminderExecutor")
    public TaskExecutor smsReminderExecutor() {
        ThreadPoolTaskExecutor executor =
                new ReminderLossReportingTaskExecutor("smsReminderExecutor", REMINDER_SHUTDOWN_GRACE_SECONDS);
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("sms-reminder-");
        executor.setRejectedExecutionHandler(new CallerBlocksPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * {@link ThreadPoolTaskExecutor} that reports, at ERROR, what its shutdown grace period ABANDONED.
     *
     * <p><b>Why this exists.</b> Both reminder pools drain on shutdown, but only for
     * {@code awaitTerminationSeconds}; past that, Spring logs a bare "Timed out while waiting for executor
     * to terminate" carrying no count and no bean identity. For these two pools that silence is a data
     * loss: {@code reminderSent = true} is committed BEFORE dispatch, so nothing re-selects an abandoned
     * reminder on the next sweep. The line below is the only record that those guests exist.
     *
     * <p><b>Why a subclass and not {@code @PreDestroy} on {@code GuestReminderDispatcher}.</b> Spring
     * destroys dependents before their dependencies, and the dispatcher depends on both pools — so its
     * {@code @PreDestroy} runs while the pools are still fully alive and accepting work. It would report
     * the pre-shutdown backlog, which is not loss at all. Overriding {@link #shutdown()} is the only hook
     * that observes the queue at the moment it is genuinely abandoned.
     *
     * <p><b>The report is counts only</b> — no phone, no message body, nothing derived from a
     * {@code GuestReminderSms} (Anti-Bug §I). The queued tasks are opaque {@link Runnable}s here and are
     * never rendered.
     */
    static final class ReminderLossReportingTaskExecutor extends ThreadPoolTaskExecutor {

        /** Stable, machine-greppable alert token. Changing it silently disables downstream alerting. */
        static final String LOSS_EVENT = "event=sms_reminders_lost_on_shutdown";

        private final String beanName;
        private final int graceSeconds;

        ReminderLossReportingTaskExecutor(String beanName, int graceSeconds) {
            this.beanName = beanName;
            this.graceSeconds = graceSeconds;
            setWaitForTasksToCompleteOnShutdown(true);
            setAwaitTerminationSeconds(graceSeconds);
        }

        /**
         * {@code super.shutdown()} stops task acceptance and then blocks for up to {@code graceSeconds}.
         * Whatever is still queued or still running when it returns is abandoned — the JVM is on its way
         * out — so that is the instant worth reporting.
         */
        @Override
        public void shutdown() {
            super.shutdown();
            reportAbandonedWork();
        }

        /**
         * Emits the abandonment line, or nothing at all when the drain completed. Staying silent on a
         * clean shutdown is deliberate: an ERROR on every deploy would train the alert to be ignored.
         */
        void reportAbandonedWork() {
            int queuedTasksAbandoned;
            int inFlightTasksKilled;
            try {
                ThreadPoolExecutor pool = getThreadPoolExecutor();
                queuedTasksAbandoned = pool.getQueue().size();
                inFlightTasksKilled = pool.getActiveCount();
            } catch (IllegalStateException e) {
                return; // Never initialized — it cannot have been carrying work.
            }
            if (queuedTasksAbandoned == 0 && inFlightTasksKilled == 0) {
                return;
            }
            log.error("{} executor={} queuedTasksAbandoned={} inFlightTasksKilled={} graceSeconds={} — "
                            + "queued tasks are already committed as reminderSent and no later sweep "
                            + "re-selects them; in-flight tasks were cut mid-request, delivery unknown",
                    LOSS_EVENT, beanName, queuedTasksAbandoned, inFlightTasksKilled, graceSeconds);
        }
    }

    /**
     * Rejection handler that BLOCKS the submitter until a queue slot frees instead of discarding the task.
     *
     * <p>Used only by {@link #smsReminderExecutor()}, where a discarded task is an unrecoverable loss (the
     * booking's {@code reminderSent} flag is already committed, so nothing re-queues it). Blocking is safe
     * there and only there, and only because the submitter is {@code sms-reminder-dispatch-0}: a thread
     * holding no pooled DB connection, no HTTP request and no scheduler slot. It is NOT safe on a thread
     * that has just committed — see {@link #smsReminderDispatchExecutor()} for the measurements.
     *
     * <p>Parks with a bounded {@code offer} rather than an unbounded {@code put} so the submitter
     * re-checks shutdown on every pass: {@code shutdownNow()} stops the workers, and a thread blocked in
     * {@code put()} on a queue nothing will ever drain would hang JVM shutdown outright. Interrupt
     * restores the flag before rethrowing, so cancellation is observable rather than swallowed.
     *
     * <p>The task cannot be stranded by worker starvation: {@code ThreadPoolExecutor.getTask()} refuses to
     * let the last worker exit while the queue is non-empty, even under
     * {@code allowCoreThreadTimeOut(true)}.
     */
    static final class CallerBlocksPolicy implements RejectedExecutionHandler {

        /** Re-check shutdown this often while parked; short enough that shutdown is never perceptibly delayed. */
        private static final long OFFER_POLL_MILLIS = 100L;

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            try {
                while (!executor.isShutdown()) {
                    if (executor.getQueue().offer(task, OFFER_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted while queueing task", e);
            }
            throw new RejectedExecutionException("smsReminderExecutor is shut down — task not queued");
        }
    }

    /**
     * Pool for master-availability cache eviction ({@code MasterCachePrefixEvictor}) — Perf MEDIUM-3.
     *
     * <p>Eviction is an O(cacheSize) Caffeine keyset scan across up to 5 caches (×M masters for a
     * service-definition mutation). It used to run synchronously on the committing request thread, adding
     * up to ~200 000 key comparisons to a booking-create / schedule-write response and — because
     * {@code removeIf} takes the same ConcurrentHashMap bin lock that {@code sync=true}'s
     * {@code computeIfAbsent} holds across its DB round-trips — occasionally stalling on a concurrent
     * reader's Neon latency. Moving it here takes it off the critical path entirely; it still runs strictly
     * after commit (callers submit from {@code afterCommit}).
     *
     * <p><b>CallerRunsPolicy, deliberately.</b> The Anti-Bug §H-2 ban on caller-runs targets SMTP /
     * notification pools, where saturation re-introduces synchronous NETWORK I/O on the request thread.
     * The trade-off inverts here: the task is a pure in-memory scan, and {@code AbortPolicy} would DROP an
     * eviction, serving stale availability for the full 60-second cache TTL (a client offered a day that is
     * actually full — the exact bug the eviction exists to prevent). Caller-runs degrades, at worst, to the
     * pre-fix behaviour. The queue is sized generously so that fallback stays a pathological case.
     *
     * <p>No {@link DelegatingSecurityContextTaskExecutor} wrapper: eviction reads no
     * {@code SecurityContext} — it takes a masterId and cache names, nothing principal-derived.
     */
    @Bean(name = "cacheEvictionExecutor")
    @Profile("!test")
    public TaskExecutor cacheEvictionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("cache-evict-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Drain the queue on shutdown: a dropped eviction outlives the JVM only as a stale entry in a
        // cache that dies with it, but an in-flight one must not be interrupted mid-scan.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * Test-profile counterpart of {@link #cacheEvictionExecutor()}: eviction runs INLINE on the calling
     * thread, so an integration test that writes a booking/schedule and immediately re-reads availability
     * observes the eviction deterministically instead of racing a pool thread (Anti-Bug §M — no
     * {@code Thread.sleep}, no timing-dependent assertions). The production async path is exercised by
     * {@code MasterCachePrefixEvictorTest}, which drives the evictor directly.
     *
     * <p>Both beans carry the SAME bean name and mutually exclusive profiles, so exactly one is ever
     * registered — the {@code @Async("cacheEvictionExecutor")} qualifier resolves in every profile and can
     * never silently fall back to Spring's default {@code SimpleAsyncTaskExecutor} (which would spawn an
     * unbounded thread per eviction).
     */
    @Bean(name = "cacheEvictionExecutor")
    @Profile("test")
    public TaskExecutor syncCacheEvictionExecutor() {
        return new SyncTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Uncaught async exception in {}.{}(): {}",
                        method.getDeclaringClass().getSimpleName(), method.getName(), ex.getClass().getName(), ex);
    }
}
