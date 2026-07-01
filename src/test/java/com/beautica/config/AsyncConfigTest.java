package com.beautica.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
