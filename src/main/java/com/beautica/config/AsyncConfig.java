package com.beautica.config;

import java.util.concurrent.ThreadPoolExecutor;
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
