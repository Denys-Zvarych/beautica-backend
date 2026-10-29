package com.beautica.moderation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.config.AsyncConfig;
import com.beautica.config.SupportProperties;
import com.beautica.moderation.dto.ContentReportEmailModel;
import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.dto.ModerationActionLink;
import com.beautica.moderation.dto.ReportedContent;
import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.event.ContentReportCreatedEvent;
import com.beautica.moderation.event.ContentReportEmailDispatcher;
import com.beautica.moderation.event.ContentReportEmailListener;
import com.beautica.moderation.service.ModerationActionLinkService;
import com.beautica.moderation.service.ModerationEmailContentLoader;
import com.beautica.notification.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 395 D7 / 414 — the support e-mail listener (request thread: hand-off only) and dispatcher (reportEmailExecutor:
 * content model D1, minted action links D3, send). Covers the routing (normal vs burst), the skip / drop paths and
 * the bounded executor. What the e-mail RENDERS is pinned by {@link ContentReportEmailRenderTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Content report support e-mail — listener (phase 395 D7 / 414)")
class ContentReportEmailTest {

    @Mock
    private EmailService emailService;

    @Mock
    private ModerationEmailContentLoader contentLoader;

    @Mock
    private ModerationActionLinkService actionLinkService;

    @Mock
    private ContentReportEmailDispatcher dispatcherMock;

    // ── listener: request thread, hand-off only ──────────────────────────────────────────────────

    @Test
    @DisplayName("audit-fix: the listener only hands the event to the dispatcher — no load, no mint, no send on the "
            + "request thread")
    void should_onlyHandOff_when_reportCreated() {
        ContentReportCreatedEvent event = event(ReportTargetType.REVIEW, false);

        listener("support@beautica.test").onContentReportCreated(event);

        verify(dispatcherMock).dispatch("support@beautica.test", event);
        verifyNoInteractions(emailService, contentLoader, actionLinkService);
    }

    @Test
    @DisplayName("audit-fix: the dispatcher runs on reportEmailExecutor (@Async), so load + mint + send are off-thread")
    void should_runOnReportEmailExecutor_when_dispatching() throws Exception {
        Method dispatch = ContentReportEmailDispatcher.class.getMethod(
                "dispatch", String.class, ContentReportCreatedEvent.class);

        Async async = dispatch.getAnnotation(Async.class);

        assertThat(async).as("dispatch must be @Async").isNotNull();
        assertThat(async.value()).isEqualTo("reportEmailExecutor");
    }

    @Test
    @DisplayName("blank SUPPORT_EMAIL → nothing dispatched (the DB row stays authoritative)")
    void should_skipEmail_when_supportInboxBlank() {
        listener("").onContentReportCreated(event(ReportTargetType.SALON_PROFILE, false));

        verifyNoInteractions(dispatcherMock);
    }

    @Test
    @DisplayName("saturated reportEmailExecutor → hand-off dropped with ONE WARN line, no stack, no exception")
    void should_logSingleWarn_when_reportEmailExecutorRejects() {
        ContentReportCreatedEvent event = event(ReportTargetType.REVIEW, false);
        doThrow(new TaskRejectedException("Executor rejected task"))
                .when(dispatcherMock).dispatch(anyString(), any());

        List<ILoggingEvent> logged = captureLog(ContentReportEmailListener.class, () -> assertThatCode(
                () -> listener("support@beautica.test").onContentReportCreated(event)).doesNotThrowAnyException());

        assertThat(logged).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage())
                    .contains(event.reportId().toString(), "reportEmailExecutor saturated");
            assertThat(line.getThrowableProxy()).as("no stack trace").isNull();
        });
    }

    // ── dispatcher: report-email thread ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("dispatcher → content model with the minted links to the support inbox (normal send)")
    void should_sendModelWithLinks_when_dispatched() {
        ContentReportCreatedEvent event = event(ReportTargetType.REVIEW, false);
        ContentReportEmailModel loaded = model(event, ReportedContent.review("text", (short) 2, true));
        List<ModerationActionLink> links = List.of(
                new ModerationActionLink(ModerationAction.REMOVE, "https://api.beautica.test/x"));
        when(contentLoader.load(event)).thenReturn(loaded);
        when(actionLinkService.mintFor(ReportTargetType.REVIEW, event.targetId(), true)).thenReturn(links);

        dispatcher().dispatch("support@beautica.test", event);

        verify(emailService).sendContentReportEmail("support@beautica.test", loaded.withActions(links));
        verify(emailService, never()).sendContentReportBurstEmail(anyString(), any());
    }

    @Test
    @DisplayName("phase 396: a burst event → the burst send")
    void should_routeBurst_when_eventIsBurst() {
        ContentReportCreatedEvent event = event(ReportTargetType.SALON_PROFILE, true);
        ContentReportEmailModel loaded = model(event, ReportedContent.profile("Salon", null));
        when(contentLoader.load(event)).thenReturn(loaded);
        when(actionLinkService.mintFor(ReportTargetType.SALON_PROFILE, event.targetId(), false)).thenReturn(List.of());

        dispatcher().dispatch("support@beautica.test", event);

        verify(emailService).sendContentReportBurstEmail("support@beautica.test", loaded);
        verify(emailService, never()).sendContentReportEmail(anyString(), any());
    }

    @Test
    @DisplayName("a DB failure while loading content → nudge dropped with ONE WARN line, no stack, no throw")
    void should_dropWithSingleWarn_when_contentLoadFails() {
        ContentReportCreatedEvent event = event(ReportTargetType.REVIEW, false);
        when(contentLoader.load(event)).thenThrow(new DataAccessResourceFailureException("db down"));

        List<ILoggingEvent> logged = captureLog(ContentReportEmailDispatcher.class,
                () -> dispatcher().dispatch("support@beautica.test", event));

        assertThat(logged).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).contains(event.reportId().toString())
                    .doesNotContain("db down");
            assertThat(line.getThrowableProxy()).as("no stack trace").isNull();
        });
        verifyNoInteractions(emailService, actionLinkService);
    }

    @Test
    @DisplayName("the event carries the comment in memory but never prints it")
    void should_omitComment_when_eventPrinted() {
        ContentReportCreatedEvent event = new ContentReportCreatedEvent(UUID.randomUUID(), ReportTargetType.REVIEW,
                UUID.randomUUID(), ReportReason.SPAM, UUID.randomUUID(), false, "SECRET-COMMENT");

        assertThat(event.toString()).doesNotContain("SECRET-COMMENT");
    }

    @Test
    @DisplayName("reportEmailTaskPool: 1 thread + 50 queued, the next hand-off is rejected (AbortPolicy)")
    void should_rejectTask_when_reportEmailPoolSaturated() throws Exception {
        ThreadPoolTaskExecutor pool = new AsyncConfig().reportEmailTaskPool();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                running.countDown();
                awaitQuietly(release);
            });
            running.await();
            for (int i = 0; i < 50; i++) {
                pool.execute(() -> { });
            }

            assertThatThrownBy(() -> pool.execute(() -> { })).isInstanceOf(TaskRejectedException.class);
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ContentReportEmailListener listener(String supportEmail) {
        SupportProperties properties = new SupportProperties();
        properties.setEmail(supportEmail);
        return new ContentReportEmailListener(properties, dispatcherMock);
    }

    private ContentReportEmailDispatcher dispatcher() {
        return new ContentReportEmailDispatcher(emailService, contentLoader, actionLinkService);
    }

    private static ContentReportCreatedEvent event(ReportTargetType type, boolean burst) {
        return new ContentReportCreatedEvent(UUID.randomUUID(), type, UUID.randomUUID(), ReportReason.SPAM,
                UUID.randomUUID(), burst, null);
    }

    private static ContentReportEmailModel model(ContentReportCreatedEvent event, ReportedContent content) {
        return new ContentReportEmailModel(event.reportId(), event.targetType(), event.targetId(), event.reason(),
                event.reporterUserId(), event.burst(), content, null, 1, List.of());
    }

    private static List<ILoggingEvent> captureLog(Class<?> source, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(source);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
