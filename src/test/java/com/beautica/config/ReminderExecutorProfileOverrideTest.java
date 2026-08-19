package com.beautica.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pins the profile split on the two guest-reminder pools: asynchronous in production, synchronous under
 * the {@code test} profile — and, crucially, pins it <em>at the seam production actually uses</em>.
 *
 * <h4>Why this class exists</h4>
 * <p>{@code BookingReminderJob#sendReminders()} publishes a {@code GuestRemindersDueEvent}; the
 * AFTER_COMMIT {@code GuestReminderDispatcher} hands it to {@code smsReminderDispatchExecutor}, which fans
 * it out across {@code smsReminderExecutor}. Both hops are asynchronous in production by design. Every
 * integration test that calls {@code sendReminders()} and then asserts on the {@code SmsService} mock on
 * the SAME thread therefore races that dispatch — which is exactly how two assertions in
 * {@code GuestVisitLinkParityIT} went red in CI on PR #107 while a third, structurally identical one
 * stayed green. The fix is the synchronous test-profile pair in {@link AsyncConfig}; this class is what
 * makes it impossible to silently lose again (a "tuning" PR that drops the {@code @Profile("test")} beans,
 * or renames one of them, fails here immediately rather than as a flake three ITs away).
 *
 * <h4>Why a qualifier-injected probe rather than {@code context.getBean(name)}</h4>
 * <p>The trap this override had to avoid is that {@code GuestReminderDispatcher} injects both pools by
 * {@code @Qualifier}, and an explicit qualifier outranks {@code @Primary} — so the {@code @Primary}
 * mechanism {@code TestAsyncConfig} uses for {@code emailExecutor} would have been silently ignored here
 * and the ITs would still race. Asserting through {@link ReminderPools}, whose constructor parameters
 * carry the same {@code @Qualifier} annotations as the dispatcher's, proves the override survives the
 * actual resolution path. A by-name {@code getBean} lookup would pass even if qualifier resolution landed
 * somewhere else.
 *
 * <p>Driven with {@link ApplicationContextRunner} rather than {@code @SpringBootTest}: this needs a
 * three-bean context, not Tomcat and Postgres (Anti-Bug §M-1). It is also the only way to assert BOTH
 * profile branches from one test class.
 */
@DisplayName("AsyncConfig — guest-reminder pools are async in production and synchronous under the test profile")
class ReminderExecutorProfileOverrideTest {

    private static final String DISPATCH_BEAN = "smsReminderDispatchExecutor";
    private static final String SEND_BEAN = "smsReminderExecutor";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncConfig.class, ReminderPools.Config.class);

    @Nested
    @DisplayName("test profile")
    class TestProfile {

        private final ApplicationContextRunner testRunner = runner.withPropertyValues("spring.profiles.active=test");

        @Test
        @DisplayName("both pools resolve to a synchronous executor THROUGH the @Qualifier the dispatcher uses")
        void should_injectSynchronousExecutors_when_testProfileActive() {
            testRunner.run(context -> {
                ReminderPools pools = context.getBean(ReminderPools.class);

                assertThat(pools.dispatch())
                        .as("smsReminderDispatchExecutor must be synchronous under the test profile — "
                                + "otherwise every IT asserting on SmsService after sendReminders() races "
                                + "the after-commit hand-off")
                        .isInstanceOf(SyncTaskExecutor.class);
                assertThat(pools.send())
                        .as("smsReminderExecutor must be synchronous under the test profile — a synchronous "
                                + "dispatch stage that fans out to an async send pool still races")
                        .isInstanceOf(SyncTaskExecutor.class);
            });
        }

        @Test
        @DisplayName("a task submitted to either pool runs on the SUBMITTING thread, not a pool thread")
        void should_runTasksOnTheCallingThread_when_testProfileActive() {
            testRunner.run(context -> {
                ReminderPools pools = context.getBean(ReminderPools.class);
                Thread caller = Thread.currentThread();

                Thread[] observed = new Thread[2];
                pools.dispatch().execute(() -> observed[0] = Thread.currentThread());
                pools.send().execute(() -> observed[1] = Thread.currentThread());

                assertThat(observed[0])
                        .as("dispatch stage ran off-thread — the type assertion alone would not have caught "
                                + "a wrapper that delegates to a pool")
                        .isSameAs(caller);
                assertThat(observed[1]).as("send stage ran off-thread").isSameAs(caller);
            });
        }

        @Test
        @DisplayName("exactly ONE bean is registered per pool name — the profiles are mutually exclusive")
        void should_registerASingleBeanPerName_when_testProfileActive() {
            testRunner.run(context -> {
                assertThat(context.getBeanNamesForType(TaskExecutor.class))
                        .as("a duplicate registration would mean the winner depends on definition order, "
                                + "not on the profile")
                        .containsOnlyOnce(DISPATCH_BEAN, SEND_BEAN);
            });
        }
    }

    @Nested
    @DisplayName("production (no test profile)")
    class ProductionProfile {

        @Test
        @DisplayName("both pools are still the real thread pools — the sends stay OFF the committing thread")
        void should_injectThreadPools_when_testProfileInactive() {
            runner.run(context -> {
                ReminderPools pools = context.getBean(ReminderPools.class);

                assertThat(pools.dispatch())
                        .as("the production dispatch pool is what keeps the after-commit body O(1); making "
                                + "it synchronous in production would re-pin a Hikari connection for the "
                                + "whole batch")
                        .isInstanceOf(ThreadPoolTaskExecutor.class);
                assertThat(pools.send())
                        .as("the production send pool is the 8-wide CallerBlocksPolicy pool")
                        .isInstanceOf(ThreadPoolTaskExecutor.class);
            });
        }

        @Test
        @DisplayName("the production pools keep their sizing and thread-name prefixes through the profile split")
        void should_keepProductionPoolShape_when_testProfileInactive() {
            runner.run(context -> {
                ThreadPoolTaskExecutor dispatch = (ThreadPoolTaskExecutor) context.getBean(DISPATCH_BEAN);
                ThreadPoolTaskExecutor send = (ThreadPoolTaskExecutor) context.getBean(SEND_BEAN);

                assertThat(dispatch.getThreadNamePrefix()).isEqualTo("sms-reminder-dispatch-");
                assertThat(dispatch.getMaxPoolSize()).as("single dispatch thread").isEqualTo(1);
                assertThat(send.getThreadNamePrefix()).isEqualTo("sms-reminder-");
                assertThat(send.getCorePoolSize()).as("8-wide send pool, core == max").isEqualTo(8);
                assertThat(send.getMaxPoolSize()).isEqualTo(8);
            });
        }
    }

    /**
     * Resolves both pools exactly as {@code GuestReminderDispatcher} does — by {@code @Qualifier} on
     * constructor parameters — so the assertions above exercise the real resolution path.
     */
    record ReminderPools(TaskExecutor dispatch, TaskExecutor send) {

        /**
         * {@code @TestConfiguration}, not {@code @Configuration} (QA LOW, 2026-08-19): this class
         * sits in {@code com.beautica.config}, inside the package {@code @SpringBootApplication}
         * component-scans, so a plain {@code @Configuration} registers this {@code ReminderPools}
         * bean into EVERY {@code @SpringBootTest} context in the suite. Inert today only because the
         * bean type is inert — the sibling {@code SmsSendExecutorProfileOverrideTest} proved the
         * failure mode by declaring an {@code SmsService} the same way and taking ~80 unrelated
         * integration tests down at boot. Boot's {@code TestTypeExcludeFilter} keeps
         * {@code @TestConfiguration} out of that scan; {@code withUserConfiguration} still registers
         * it explicitly here.
         */
        @TestConfiguration(proxyBeanMethods = false)
        static class Config {
            @Bean
            ReminderPools reminderPools(
                    @Qualifier(DISPATCH_BEAN) TaskExecutor dispatch,
                    @Qualifier(SEND_BEAN) TaskExecutor send) {
                return new ReminderPools(dispatch, send);
            }
        }
    }
}
