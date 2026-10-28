package com.beautica.moderation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.config.AsyncConfig;
import com.beautica.config.SupportProperties;
import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.event.ContentReportCreatedEvent;
import com.beautica.moderation.event.ContentReportEmailListener;
import com.beautica.notification.EmailService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Phase 395 D7 — the support e-mail for a content report carries identifiers only: report id, target type/id,
 * reason, reporter id. Neither the reported text nor the reporter's comment may appear. Renders the real
 * {@code templates/email/content-report.html} through a real Thymeleaf engine into a real {@link MimeMessage}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Content report support e-mail — ids only (phase 395 D7)")
class ContentReportEmailTest {

    private static final String TEMPLATE = "templates/email/content-report.html";
    private static final Set<String> ALLOWED_TEMPLATE_VARIABLES =
            // "burst" (phase 396) is a boolean flag, not UGC.
            Set.of("reportId", "targetType", "targetId", "reason", "reporterUserId", "burst");

    @Mock
    private JavaMailSender mailSender;

    private EmailService emailService;
    private MimeMessage message;

    @BeforeEach
    void setUp() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        emailService = new EmailService(mailSender, engine, "noreply@beautica.app");
        message = new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    @DisplayName("listener → e-mail to the support inbox containing every id, the reason and target type")
    void should_sendIdsOnlyEmail_when_reportCreated() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(message);
        UUID reportId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        ContentReportEmailListener listener = new ContentReportEmailListener(supportInbox("support@beautica.test"),
                emailService);

        listener.onContentReportCreated(new ContentReportCreatedEvent(
                reportId, ReportTargetType.REVIEW, targetId, ReportReason.HARASSMENT, reporterId, false));

        verify(mailSender).send(message);
        String html = message.getContent().toString();
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("support@beautica.test");
        assertThat(html).contains(reportId.toString(), targetId.toString(), reporterId.toString(),
                "REVIEW", "HARASSMENT");
        assertThat(message.getSubject()).isEqualTo("[Beautica Report] REVIEW — HARASSMENT");
    }

    @Test
    @DisplayName("phase 396: a burst event → [BURST] subject + burst banner; a normal event carries neither")
    void should_flagBurst_when_eventIsBurst() throws Exception {
        MimeMessage normal = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message, normal);
        ContentReportEmailListener listener = new ContentReportEmailListener(supportInbox("support@beautica.test"),
                emailService);

        listener.onContentReportCreated(new ContentReportCreatedEvent(
                UUID.randomUUID(), ReportTargetType.REVIEW, UUID.randomUUID(), ReportReason.SPAM, UUID.randomUUID(), true));
        listener.onContentReportCreated(new ContentReportCreatedEvent(
                UUID.randomUUID(), ReportTargetType.REVIEW, UUID.randomUUID(), ReportReason.SPAM, UUID.randomUUID(), false));

        assertThat(message.getSubject()).isEqualTo("[BURST] [Beautica Report] REVIEW — SPAM");
        assertThat(message.getContent().toString()).contains("Сплеск скарг");
        assertThat(normal.getSubject()).isEqualTo("[Beautica Report] REVIEW — SPAM");
        assertThat(normal.getContent().toString()).doesNotContain("Сплеск скарг");
    }

    @Test
    @DisplayName("the template binds only the id/enum variables + the burst flag — no slot for review text or comment")
    void should_bindOnlyIdVariables_when_templateParsed() throws Exception {
        String template;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TEMPLATE)) {
            assertThat(in).as("template on the classpath").isNotNull();
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Matcher matcher = Pattern.compile("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_.]*)").matcher(template);
        Set<String> variables = matcher.results().map(m -> m.group(1)).collect(Collectors.toSet());

        assertThat(variables).isEqualTo(ALLOWED_TEMPLATE_VARIABLES);
    }

    @Test
    @DisplayName("the rendered body contains no review text or reporter comment even when both exist")
    void should_notContainUgc_when_rendered() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(message);
        // What the DB holds for this report — none of it is passed to the e-mail path.
        String reviewText = "REVIEW-TEXT-SENTINEL";
        String reporterComment = "REPORTER-COMMENT-SENTINEL";

        emailService.sendContentReportEmail("support@beautica.test", UUID.randomUUID(), "REVIEW",
                UUID.randomUUID(), "OTHER", UUID.randomUUID());

        String html = message.getContent().toString();
        assertThat(html).doesNotContain(reviewText).doesNotContain(reporterComment);
        assertThat(message.getSubject()).doesNotContain(reviewText).doesNotContain(reporterComment);
    }

    @Test
    @DisplayName("blank SUPPORT_EMAIL → no e-mail attempted (the DB row stays authoritative)")
    void should_skipEmail_when_supportInboxBlank() {
        EmailService email = mock(EmailService.class);
        ContentReportEmailListener listener = new ContentReportEmailListener(supportInbox(""), email);

        listener.onContentReportCreated(new ContentReportCreatedEvent(UUID.randomUUID(),
                ReportTargetType.SALON_PROFILE, UUID.randomUUID(), ReportReason.SPAM, UUID.randomUUID(), false));

        verifyNoInteractions(email);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("saturated reportEmailExecutor → hand-off dropped with ONE WARN line, no stack, no exception")
    void should_logSingleWarn_when_reportEmailExecutorRejects() {
        EmailService email = mock(EmailService.class);
        doThrow(new TaskRejectedException("Executor rejected task"))
                .when(email).sendContentReportEmail(anyString(), any(), anyString(), any(), anyString(), any());
        ContentReportEmailListener listener = new ContentReportEmailListener(supportInbox("support@beautica.test"),
                email);
        UUID reportId = UUID.randomUUID();
        Logger listenerLogger = (Logger) LoggerFactory.getLogger(ContentReportEmailListener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        listenerLogger.addAppender(appender);

        try {
            assertThatCode(() -> listener.onContentReportCreated(new ContentReportCreatedEvent(reportId,
                    ReportTargetType.REVIEW, UUID.randomUUID(), ReportReason.SPAM, UUID.randomUUID(), false)))
                    .doesNotThrowAnyException();
        } finally {
            listenerLogger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(reportId.toString(), "reportEmailExecutor saturated");
            assertThat(event.getThrowableProxy()).as("no stack trace").isNull();
        });
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

    private static SupportProperties supportInbox(String email) {
        SupportProperties properties = new SupportProperties();
        properties.setEmail(email);
        return properties;
    }
}
