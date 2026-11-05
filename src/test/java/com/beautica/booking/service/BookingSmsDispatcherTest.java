package com.beautica.booking.service;

import com.beautica.notification.channel.GuestMessageKind;
import com.beautica.notification.channel.GuestMessageRouter;
import com.beautica.notification.channel.GuestMessageRouters;
import com.beautica.notification.telegram.TelegramDeliveryException;
import com.beautica.notification.telegram.TelegramMessenger;
import com.beautica.notification.telegram.link.ActiveLink;
import com.beautica.notification.telegram.link.TelegramLinkService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.notification.sms.SmsBackpressureException;
import com.beautica.notification.sms.SmsDeliveryException;
import com.beautica.notification.sms.SmsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link BookingSmsDispatcher} — the hand-off that keeps the Turbosms round trip off the request
 * thread (backend-perf MEDIUM, 2026-08-18; widened from the walk-in path to all four request-path
 * senders, backend-perf LOW, 2026-08-19).
 *
 * <p>{@link BookingSmsDispatcher.Kind} is parameterised only across the routing rows (linked → Telegram,
 * not linked → SMS, Telegram failure → SMS): since the 2026-11-04 user decision every kind goes through
 * {@code GuestMessageRouter}, and those rows prove no kind bypasses it. The hand-off rows below run one
 * kind — the executor path does not depend on it.
 *
 * <p>The pool's own configuration is asserted in {@code AsyncConfigTest}; what is asserted here is
 * the contract the CALLER depends on, and every row below is about a way this class could silently
 * undo the fix or break the write it is bolted onto:
 * <ul>
 *   <li>the send is SUBMITTED, not executed inline (a dispatcher that called
 *       {@code smsService.send} directly would pass every "the SMS was sent" assertion in
 *       {@code StaffBookingServiceTest} while restoring the whole latency problem);</li>
 *   <li>a provider failure never escapes;</li>
 *   <li>a REJECTED submission never escapes either — this one matters because the caller is an
 *       {@code afterCommit} callback, and Spring propagates throwables out of {@code afterCommit},
 *       so an escaping {@link RejectedExecutionException} would report an already-committed booking
 *       as a failed request.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingSmsDispatcher — request-path booking SMS hand-off")
class BookingSmsDispatcherTest {

    private static final BookingSmsDispatcher.Kind KIND =
            BookingSmsDispatcher.Kind.WALK_IN_CONFIRMATION;
    private static final BookingSmsDispatcher.Kind GUEST_KIND =
            BookingSmsDispatcher.Kind.GUEST_PROVIDER_CANCELLATION;
    private static final String PHONE = "+380501234567";
    private static final String TEXT = "Beautica: Запис підтверджено!";
    private static final long CHAT_ID = 42L;

    @Mock private SmsService smsService;

    /** Bound to the dispatcher's own logger: the drop line is part of its contract, not incidental. */
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger dispatcherLogger =
            (Logger) LoggerFactory.getLogger(BookingSmsDispatcher.class);

    @BeforeEach
    void attachAppender() {
        appender.start();
        dispatcherLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        dispatcherLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("should_submitTheSendToTheExecutor_when_dispatched")
    void should_submitTheSendToTheExecutor_when_dispatched() {
        RecordingExecutor executor = new RecordingExecutor();
        BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(GuestMessageRouters.smsOnly(smsService), executor);

        dispatcher.dispatch(KIND, PHONE, TEXT);

        // The executor holds the task and NOTHING has been sent yet — that gap is the fix. An
        // inline send would leave submitted empty and smsService already called.
        assertThat(executor.submitted).hasSize(1);
        verifyNoInteractions(smsService);

        executor.submitted.get(0).run();
        verify(smsService).send(eq(PHONE), eq(TEXT));
    }

    /**
     * User decision 2026-11-04 — every kind (confirmations included) goes through {@code GuestMessageRouter}: with
     * Telegram ON and the phone ACTIVE-linked the message reaches Telegram only, as the identical text, and no SMS
     * leaves. Mutation: restoring a direct {@code smsService.send} for any kind turns its row red.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(BookingSmsDispatcher.Kind.class)
    @DisplayName("should_sendToTelegramOnly_when_phoneIsLinked")
    void should_sendToTelegramOnly_when_phoneIsLinked(BookingSmsDispatcher.Kind kind) {
        TelegramMessenger messenger = mock(TelegramMessenger.class);
        TelegramLinkService links = mock(TelegramLinkService.class);
        when(links.findActiveForPhone(PHONE)).thenReturn(Optional.of(new ActiveLink(UUID.randomUUID(), CHAT_ID)));
        BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(linkedRouter(messenger, links),
                new SyncTaskExecutor());

        dispatcher.dispatch(kind, PHONE, TEXT);

        verify(messenger).sendWaiting(CHAT_ID, TEXT);
        verifyNoInteractions(smsService);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BookingSmsDispatcher.Kind.class)
    @DisplayName("should_sendSmsOnly_when_phoneIsNotLinked")
    void should_sendSmsOnly_when_phoneIsNotLinked(BookingSmsDispatcher.Kind kind) {
        TelegramMessenger messenger = mock(TelegramMessenger.class);
        TelegramLinkService links = mock(TelegramLinkService.class);
        when(links.findActiveForPhone(PHONE)).thenReturn(Optional.empty());
        BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(linkedRouter(messenger, links),
                new SyncTaskExecutor());

        dispatcher.dispatch(kind, PHONE, TEXT);

        verify(smsService).send(PHONE, TEXT);
        verifyNoInteractions(messenger);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BookingSmsDispatcher.Kind.class)
    @DisplayName("should_fallBackToSms_when_telegramSendFails")
    void should_fallBackToSms_when_telegramSendFails(BookingSmsDispatcher.Kind kind) {
        TelegramMessenger messenger = mock(TelegramMessenger.class);
        TelegramLinkService links = mock(TelegramLinkService.class);
        when(links.findActiveForPhone(PHONE)).thenReturn(Optional.of(new ActiveLink(UUID.randomUUID(), CHAT_ID)));
        doThrow(new TelegramDeliveryException("bot api 500")).when(messenger).sendWaiting(CHAT_ID, TEXT);
        BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(linkedRouter(messenger, links),
                new SyncTaskExecutor());

        dispatcher.dispatch(kind, PHONE, TEXT);

        verify(messenger).sendWaiting(CHAT_ID, TEXT);
        verify(smsService).send(PHONE, TEXT);
    }

    /** The routed kind keeps the dispatcher's log token, so metrics and booking-SMS log lines name the same message. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(BookingSmsDispatcher.Kind.class)
    @DisplayName("should_routeAsTheSameNamedGuestMessageKind_when_anyKindDispatched")
    void should_routeAsTheSameNamedGuestMessageKind_when_anyKindDispatched(BookingSmsDispatcher.Kind kind) {
        GuestMessageRouter router = mock(GuestMessageRouter.class);
        BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(router, new SyncTaskExecutor());

        dispatcher.dispatch(kind, PHONE, TEXT);

        verify(router).deliver(GuestMessageKind.valueOf(kind.name()), PHONE, TEXT);
        assertThat(GuestMessageKind.valueOf(kind.name()).token()).isEqualTo(kind.token());
    }

    /**
     * The one exception to "every guest notification via the router": the guest phone-OTP stays SMS. Structural, so a
     * future refactor cannot route it by accident — the OTP sender's package never reaches the router or this
     * dispatcher, and neither of them ever holds the OTP sender.
     */
    @Test
    @DisplayName("should_keepOtpOffTheRouter_when_scanningProductionClasses")
    void should_keepOtpOffTheRouter_when_scanningProductionClasses() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.beautica.auth.phoneotp", "com.beautica.booking.service",
                        "com.beautica.notification.channel");
        assertThat(production.stream().map(JavaClass::getSimpleName))
                .contains("PhoneOtpService", "BookingSmsDispatcher", "GuestMessageRouter");

        noClasses().that().resideInAPackage("com.beautica.auth.phoneotp..")
                .should().dependOnClassesThat().haveSimpleName("GuestMessageRouter")
                .orShould().dependOnClassesThat().haveSimpleName("BookingSmsDispatcher")
                .check(production);
        noClasses().that().haveSimpleName("GuestMessageRouter").or().haveSimpleName("BookingSmsDispatcher")
                .should().dependOnClassesThat().haveSimpleName("OtpSmsSender")
                .check(production);
    }

    private GuestMessageRouter linkedRouter(TelegramMessenger messenger, TelegramLinkService links) {
        return new GuestMessageRouter(smsService, messenger, links, GuestMessageRouters.telegramProperties(true),
                GuestMessageRouters.bookingSms(true), new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("should_swallowTheFailure_when_theProviderThrows")
    void should_swallowTheFailure_when_theProviderThrows() {
        doThrow(new SmsDeliveryException("provider down")).when(smsService).send(anyString(), anyString());
        BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(GuestMessageRouters.smsOnly(smsService), new SyncTaskExecutor());

        // Synchronous executor, so the failure would surface at the call site if it escaped at all —
        // and the call site is an afterCommit callback on an already-committed booking.
        assertThatCode(() -> dispatcher.dispatch(KIND, PHONE, TEXT)).doesNotThrowAnyException();

        verify(smsService).send(eq(PHONE), eq(TEXT));
    }

    @Test
    @DisplayName("should_swallowTheRejection_when_theExecutorIsSaturated")
    void should_swallowTheRejection_when_theExecutorIsSaturated() {
        TaskExecutor saturated = task -> {
            throw new RejectedExecutionException("smsSendExecutor is full");
        };
        BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(GuestMessageRouters.smsOnly(smsService), saturated);

        assertThatCode(() -> dispatcher.dispatch(KIND, PHONE, TEXT)).doesNotThrowAnyException();

        verifyNoInteractions(smsService);
    }

    /**
     * The drop is SILENT to the caller by design, so the log line is the only trace a real lost
     * confirmation leaves — and {@code event=booking_sms_dropped} is a machine-greppable alert token,
     * exactly like {@code AsyncConfig.ReminderLossReportingTaskExecutor.LOSS_EVENT}, which
     * {@code AsyncConfigTest} already pins. Nothing pinned this one (QA LOW, 2026-08-19): renaming it
     * would silently disable downstream alerting while every test stayed green. Asserted as a LITERAL
     * rather than through a shared constant so that extracting one later is still a change this row
     * notices.
     *
     * <p>{@code kind=} is pinned alongside it: four senders now share one pool, so a drop line
     * without the kind tells an operator that something was lost but not what — and a
     * {@link BookingSmsDispatcher.Kind} whose token silently changed would degrade the alert to
     * exactly that.
     */
    @Test
    @DisplayName("should_logTheGreppableDropEvent_when_theExecutorIsSaturated")
    void should_logTheGreppableDropEvent_when_theExecutorIsSaturated() {
        TaskExecutor saturated = task -> {
            throw new RejectedExecutionException("smsSendExecutor is full");
        };
        new BookingSmsDispatcher(GuestMessageRouters.smsOnly(smsService), saturated).dispatch(KIND, PHONE, TEXT);

        assertThat(appender.list)
                .as("a dropped confirmation must be reported at ERROR under a stable alert token")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage())
                            .contains("event=booking_sms_dropped")
                            .contains("kind=walk_in_confirmation")
                            .contains("reason=executor_saturated");
                });
        assertThat(appender.list)
                .as("the drop line must carry no recipient and no rendered body (Anti-Bug §I)")
                .noneMatch(event -> event.getFormattedMessage().contains(PHONE)
                        || event.getFormattedMessage().contains(TEXT));
    }

    @Test
    @DisplayName("should_rethrowTheRejection_when_dispatchOrThrowAndExecutorSaturated")
    void should_rethrowTheRejection_when_dispatchOrThrowAndExecutorSaturated() {
        TaskExecutor saturated = task -> {
            throw new RejectedExecutionException("smsSendExecutor is full");
        };
        BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(GuestMessageRouters.smsOnly(smsService), saturated);

        // The outbox drain needs the rejection to re-queue the row; swallowing it would mark it SENT.
        assertThatThrownBy(() -> dispatcher.dispatchOrThrow(GUEST_KIND, PHONE, TEXT))
                .isInstanceOf(SmsBackpressureException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);

        verifyNoInteractions(smsService);
        assertThat(appender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("event=booking_sms_deferred")
                        .contains("kind=guest_provider_cancellation"))
                .noneMatch(event -> event.getFormattedMessage().contains(PHONE)
                        || event.getFormattedMessage().contains(TEXT));
    }

    @Test
    @DisplayName("should_submitAndSwallowProviderFailure_when_dispatchOrThrowAccepted")
    void should_submitAndSwallowProviderFailure_when_dispatchOrThrowAccepted() {
        doThrow(new SmsDeliveryException("provider down")).when(smsService).send(anyString(), anyString());
        RecordingExecutor executor = new RecordingExecutor();
        BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(GuestMessageRouters.smsOnly(smsService), executor);

        dispatcher.dispatchOrThrow(GUEST_KIND, PHONE, TEXT);

        assertThat(executor.submitted).hasSize(1);
        verifyNoInteractions(smsService);
        assertThatCode(() -> executor.submitted.get(0).run()).doesNotThrowAnyException();
        verify(smsService).send(eq(PHONE), eq(TEXT));
    }

    // ── Phase 390 perf LOW-2: request-path reserve on the shared queue ────────

    @Test
    @DisplayName("should_refuseOutboxSms_when_freeQueueSlotsBelowRequestPathReserve")
    void should_refuseOutboxSms_when_freeQueueSlotsBelowRequestPathReserve() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolTaskExecutor pool = blockedPool(release, BookingSmsDispatcher.OUTBOX_QUEUE_RESERVE - 1);
        try {
            BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(GuestMessageRouters.smsOnly(smsService), pool);

            assertThatThrownBy(() -> dispatcher.dispatchOrThrow(GUEST_KIND, PHONE, TEXT))
                    .isInstanceOf(SmsBackpressureException.class);

            assertThat(pool.getThreadPoolExecutor().getQueue()).as("nothing queued into the reserve").isEmpty();
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("event=booking_sms_deferred").contains("reason=request_path_reserve"));
        } finally {
            release.countDown();
            pool.shutdown();
        }
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("should_acceptOutboxSms_when_freeQueueSlotsEqualTheReserve")
    void should_acceptOutboxSms_when_freeQueueSlotsEqualTheReserve() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolTaskExecutor pool = blockedPool(release, BookingSmsDispatcher.OUTBOX_QUEUE_RESERVE);
        try {
            BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(GuestMessageRouters.smsOnly(smsService), pool);

            dispatcher.dispatchOrThrow(GUEST_KIND, PHONE, TEXT);

            assertThat(pool.getThreadPoolExecutor().getQueue()).hasSize(1);
        } finally {
            release.countDown();
            pool.shutdown();
        }
        verify(smsService).send(eq(PHONE), eq(TEXT));
    }

    @Test
    @DisplayName("should_useFullQueueCapacity_when_requestPathDispatchInsideTheReserve")
    void should_useFullQueueCapacity_when_requestPathDispatchInsideTheReserve() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        // One free slot left: deep inside the reserve, still above zero.
        ThreadPoolTaskExecutor pool = blockedPool(release, 1);
        try {
            BookingSmsDispatcher dispatcher = new BookingSmsDispatcher(GuestMessageRouters.smsOnly(smsService), pool);

            dispatcher.dispatch(KIND, PHONE, TEXT);

            assertThat(pool.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
            assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("booking_sms_dropped"));
        } finally {
            release.countDown();
            pool.shutdown();
        }
        verify(smsService).send(eq(PHONE), eq(TEXT));
    }

    /**
     * A single-thread pool whose worker is parked on {@code release} and whose queue has exactly
     * {@code freeSlots} remaining slots. The worker takes the blocker directly (a fresh core thread
     * never queues its first task), so the queue starts empty and its capacity IS the free-slot count.
     * Shut down with wait-for-tasks, so every queued send has run by the time {@code shutdown()} returns.
     */
    private static ThreadPoolTaskExecutor blockedPool(CountDownLatch release, int freeSlots) throws Exception {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(freeSlots);
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(10);
        pool.initialize();
        CountDownLatch started = new CountDownLatch(1);
        pool.execute(() -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        return pool;
    }

    /** Captures submitted tasks instead of running them, so "submitted" and "ran" stay separable. */
    private static final class RecordingExecutor implements TaskExecutor {

        private final List<Runnable> submitted = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            submitted.add(task);
        }
    }
}
