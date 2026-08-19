package com.beautica.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.booking.service.BookingSmsDispatcher;
import com.beautica.notification.sms.SmsService;
import java.util.concurrent.atomic.AtomicReference;
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
 * Pins the profile split on {@code smsSendExecutor} — the REQUEST-PATH SMS pool
 * ({@link AsyncConfig#smsSendExecutor()} in production, {@link AsyncConfig#syncSmsSendExecutor()}
 * under the {@code test} profile).
 *
 * <h4>Why this class exists (QA MEDIUM, 2026-08-19)</h4>
 * <p>The reminder pools have {@code ReminderExecutorProfileOverrideTest}; this pool had no
 * equivalent, and the gap was not symmetric with "one more test would be nice". {@code AsyncConfigTest}
 * instantiates {@link AsyncConfig} directly and therefore reads the PRODUCTION factory method
 * whatever profile is active, so nothing anywhere observed the {@code @Profile("test")} swap. Delete
 * {@link AsyncConfig#syncSmsSendExecutor()} and every suite stays green on the first run: the
 * wire-level {@code WalkInBookingSmsIT} / {@code WalkInBookingSmsEnabledIT} assert
 * {@code TURBOSMS.verify(n, ...)} on the test thread the instant the create returns, while the send
 * sits queued on {@code sms-send-0}. That is a FLAKE, not a failure — the worst possible way for a
 * regression to present, and the exact shape that took two {@code GuestVisitLinkParityIT} rows red in
 * CI on PR #107 while a third stayed green.
 *
 * <h4>Why a qualifier-injected probe, and why the real dispatcher too</h4>
 * <p>{@link BookingSmsDispatcher} injects the pool by {@code @Qualifier("smsSendExecutor")}, and an
 * explicit qualifier outranks {@code @Primary} — so a {@code @Primary} override in a
 * {@code @TestConfiguration} would be silently ignored and the ITs would still race. A by-name
 * {@code getBean} lookup would likewise pass even if qualifier resolution landed elsewhere. The rows
 * below therefore go through {@link SendPool}, whose constructor parameter carries the same
 * qualifier, AND — in the test-profile block — through a REAL {@link BookingSmsDispatcher} built by
 * the container, which is the only assertion that proves the class under test resolves the stand-in
 * rather than merely that the stand-in exists.
 *
 * <p>Driven with {@link ApplicationContextRunner} rather than {@code @SpringBootTest}: this needs a
 * three-bean context, not Tomcat and Postgres (Anti-Bug §M-1), and it is the only way to assert BOTH
 * profile branches from one class.
 */
@DisplayName("AsyncConfig — smsSendExecutor is async in production and synchronous under the test profile")
class SmsSendExecutorProfileOverrideTest {

    private static final String SEND_BEAN = "smsSendExecutor";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncConfig.class, SendPool.Config.class);

    @Nested
    @DisplayName("test profile")
    class TestProfile {

        private final ApplicationContextRunner testRunner =
                runner.withPropertyValues("spring.profiles.active=test");

        @Test
        @DisplayName("smsSendExecutor resolves to a synchronous executor THROUGH the @Qualifier the dispatcher uses")
        void should_injectASynchronousExecutor_when_testProfileActive() {
            testRunner.run(context -> {
                assertThat(context.getBean(SendPool.class).send())
                        .as("an async smsSendExecutor under test turns every TURBOSMS.verify(n, ...) in "
                                + "WalkInBookingSmsIT into a race against sms-send-0")
                        .isInstanceOf(SyncTaskExecutor.class);
            });
        }

        @Test
        @DisplayName("the REAL BookingSmsDispatcher sends on the CALLING thread, not on a pool thread")
        void should_sendOnTheCallingThread_when_testProfileActive() {
            testRunner.run(context -> {
                BookingSmsDispatcher dispatcher = context.getBean(BookingSmsDispatcher.class);
                RecordingSmsService sms = context.getBean(RecordingSmsService.class);
                Thread caller = Thread.currentThread();

                dispatcher.dispatch(BookingSmsDispatcher.Kind.WALK_IN_CONFIRMATION,
                        "+380501234567", "Beautica: Запис підтверджено!");

                assertThat(sms.sendingThread.get())
                        .as("the type assertion alone would not catch a stand-in that delegates to a "
                                + "pool; this is the property the wire-level ITs actually depend on")
                        .isSameAs(caller);
            });
        }

        /**
         * {@code containsOnlyOnce(SEND_BEAN)} alone would NOT be enough, and was tried: it stays green
         * when the profile split is replaced by a SECOND, differently-named send pool, because the
         * asserted name still appears exactly once. The assertion is therefore on the whole family —
         * every {@code smsSend*} executor the container knows about — which is what actually breaks
         * when the two mutually exclusive definitions become two coexisting ones.
         */
        @Test
        @DisplayName("exactly ONE send pool exists under the test profile — the two definitions are mutually exclusive")
        void should_registerASingleSendPool_when_testProfileActive() {
            testRunner.run(context -> {
                assertThat(context.getBeanNamesForType(TaskExecutor.class))
                        .filteredOn(name -> name.startsWith("smsSend"))
                        .as("two coexisting send pools mean the winner depends on definition order "
                                + "rather than on the profile, and BookingSmsDispatcher's @Qualifier "
                                + "silently picks whichever one carries the matching name")
                        .containsExactly(SEND_BEAN);
            });
        }
    }

    @Nested
    @DisplayName("production (no test profile)")
    class ProductionProfile {

        @Test
        @DisplayName("smsSendExecutor is still the real 4-wide pool — the send stays OFF the request thread")
        void should_injectAThreadPool_when_testProfileInactive() {
            runner.run(context -> {
                assertThat(context.getBean(SendPool.class).send())
                        .as("making this synchronous in production would put the full Turbosms round "
                                + "trip back on the servlet thread inside an afterCommit callback, "
                                + "while it still holds its pooled Hikari connection")
                        .isInstanceOf(ThreadPoolTaskExecutor.class);
            });
        }

        @Test
        @DisplayName("the production pool keeps its sizing and thread-name prefix through the profile split")
        void should_keepProductionPoolShape_when_testProfileInactive() {
            runner.run(context -> {
                ThreadPoolTaskExecutor send = (ThreadPoolTaskExecutor) context.getBean(SEND_BEAN);

                assertThat(send.getThreadNamePrefix()).isEqualTo("sms-send-");
                assertThat(send.getCorePoolSize())
                        .as("core == max == 4, so the advertised concurrency is the real one")
                        .isEqualTo(4);
                assertThat(send.getMaxPoolSize()).isEqualTo(4);
            });
        }
    }

    /**
     * Resolves the pool exactly as {@link BookingSmsDispatcher} does — by {@code @Qualifier} on a
     * constructor parameter — so the assertions above exercise the real resolution path.
     */
    record SendPool(TaskExecutor send) {

        /**
         * {@code @TestConfiguration}, NOT {@code @Configuration} — and that is load-bearing, not
         * stylistic. This class sits in {@code com.beautica.config}, inside the package
         * {@code @SpringBootApplication} component-scans, so a plain {@code @Configuration} is picked
         * up by EVERY {@code @SpringBootTest} in the suite. Its {@link RecordingSmsService} bean would
         * then be a SECOND {@link SmsService}, and the seven bare {@code SmsService} injection points
         * ({@code GuestBookingService} first among them) would fail at boot with
         * {@code expected single matching bean but found 2} — taking ~80 unrelated integration tests
         * down with them. Boot's {@code TestTypeExcludeFilter} excludes {@code @TestConfiguration}
         * from that scan, while {@code ApplicationContextRunner#withUserConfiguration} still registers
         * it here explicitly.
         */
        @TestConfiguration(proxyBeanMethods = false)
        static class Config {

            @Bean
            SendPool sendPool(@Qualifier(SEND_BEAN) TaskExecutor send) {
                return new SendPool(send);
            }

            @Bean
            RecordingSmsService recordingSmsService() {
                return new RecordingSmsService();
            }

            @Bean
            BookingSmsDispatcher bookingSmsDispatcher(
                    SmsService smsService, @Qualifier(SEND_BEAN) TaskExecutor send) {
                return new BookingSmsDispatcher(smsService, send);
            }
        }
    }

    /** Records which thread the send ran on. Never reaches a network. */
    static final class RecordingSmsService implements SmsService {

        private final AtomicReference<Thread> sendingThread = new AtomicReference<>();

        @Override
        public void send(String phoneE164, String text) {
            sendingThread.set(Thread.currentThread());
        }
    }
}
