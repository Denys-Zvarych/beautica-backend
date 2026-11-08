package com.beautica.notification;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailService — unit")
class EmailServiceTest {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceTest.class);

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private SpringTemplateEngine templateEngine;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(mailSender, templateEngine, "noreply@beautica.app");
    }

    @Test
    @DisplayName("sendAdminNotification calls mailSender.send when invoked")
    void should_sendEmail_when_sendAdminNotificationCalled() throws Exception {
        var toEmail = "admin@beautica.app";
        var subject = "Test Subject";
        var body = "Test body";
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        log.debug("Arrange: real MimeMessage so fields are inspectable after helper populates them");

        when(mailSender.createMimeMessage()).thenReturn(realMessage);

        log.debug("Act: sendAdminNotification to={}", toEmail);
        emailService.sendAdminNotification(toEmail, subject, body);

        verify(mailSender).send(realMessage);

        assertThat(realMessage.getSubject())
                .as("Subject must equal the value passed to sendAdminNotification")
                .isEqualTo(subject);
        assertThat(realMessage.getFrom())
                .as("From: must be populated with a single address")
                .isNotNull()
                .hasSize(1);
        assertThat(realMessage.getFrom()[0].toString())
                .as("From: must contain the configured noreply address")
                .contains("noreply@beautica.app");
        assertThat(realMessage.getAllRecipients())
                .as("To: must contain exactly the recipient passed in")
                .isNotNull()
                .hasSize(1);
        assertThat(realMessage.getAllRecipients()[0].toString())
                .as("To: must contain the admin email")
                .contains(toEmail);
        assertThat(realMessage.getContent().toString())
                .as("Body must contain the text passed to sendAdminNotification")
                .contains(body);
    }

    @Test
    @DisplayName("sendAdminNotification completes normally when MailException occurs (async method logs and swallows)")
    void should_notThrow_when_mailExceptionOnAdminNotification() {
        var toEmail = "admin@beautica.app";
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        log.debug("Arrange: mailSender.send will throw MailSendException");

        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        doThrow(new MailSendException("SMTP connection failed")).when(mailSender).send(any(MimeMessage.class));

        log.debug("Act: sendAdminNotification when mailSender throws — @Async method must not propagate");
        assertThatCode(() -> emailService.sendAdminNotification(toEmail, "Test Subject", "Test body"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("phase 441: admin notification SMTP failure — exactly one ERROR, the brevo-smtp external-call line, no address")
    void should_logOneBrevoSmtpError_when_adminNotificationSendFails() {
        var toEmail = "admin@beautica.app";
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("550 rejected " + toEmail)).when(mailSender).send(any(MimeMessage.class));
        ch.qos.logback.classic.Logger serviceLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EmailService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        serviceLogger.addAppender(appender);

        try {
            emailService.sendAdminNotification(toEmail, "Test Subject", "Test body");
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .singleElement()
                .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .isEqualTo("External call failed service=brevo-smtp op=send http=- reason=rejected to=- "
                        + "detail=admin-notification MailSendException");
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(toEmail));
    }

    // ── sendCategoryRequestNotification — real Thymeleaf render ─────────────────

    /**
     * Builds an {@link EmailService} whose template engine is a REAL
     * {@link SpringTemplateEngine} resolving the production {@code email/} templates
     * from the classpath. This is what makes the HTML-escaping assertion meaningful:
     * a mocked engine would prove nothing about auto-escaping.
     */
    private EmailService emailServiceWithRealEngine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        return new EmailService(mailSender, engine, "noreply@beautica.app");
    }

    @Test
    @DisplayName("sendCategoryRequestNotification sends to the admin with an integral subject carrying the category name")
    void should_sendToAdminWithIntactSubject_when_categoryRequestNotificationCalled() throws Exception {
        var realEmailService = emailServiceWithRealEngine();
        var admin = "admin@beautica.app";
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);

        log.debug("Act: sendCategoryRequestNotification with a clean payload");
        realEmailService.sendCategoryRequestNotification(
                admin, "user-123", "NAIL_ART", "Нейл-арт",
                "https://api.beautica.app/api/v1/service-categories/requests/review?token=abc123");

        verify(mailSender).send(realMessage);
        assertThat(realMessage.getAllRecipients())
                .as("To: must contain exactly the admin recipient")
                .isNotNull().hasSize(1);
        assertThat(realMessage.getAllRecipients()[0].toString())
                .as("recipient must be the admin email")
                .contains(admin);
        assertThat(realMessage.getSubject())
                .as("subject must carry the category name unbroken (no CR/LF header injection)")
                .isEqualTo("Beautica: Новий запит на категорію послуги — NAIL_ART");
    }

    @Test
    @DisplayName("sendCategoryRequestNotification escapes markup in displayName (HTML injection resistance)")
    void should_escapeMarkupInDisplayName_when_categoryRequestNotificationRendered() throws Exception {
        var realEmailService = emailServiceWithRealEngine();
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);

        String malicious = "<script>alert('xss')</script>";

        log.debug("Act: render with a markup-laden displayName — Thymeleaf th:text must escape it");
        realEmailService.sendCategoryRequestNotification(
                "admin@beautica.app", "user-123", "NAIL_ART", malicious,
                "https://api.beautica.app/api/v1/service-categories/requests/review?token=abc123");

        String html = realMessage.getContent().toString();
        assertThat(html)
                .as("raw <script> markup must NOT appear unescaped in the rendered body")
                .doesNotContain("<script>alert('xss')</script>");
        assertThat(html)
                .as("the markup must appear HTML-entity-escaped")
                .contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("sendCategoryRequestNotification carries the reviewUrl token unbroken in the approve link")
    void should_carryReviewUrlTokenUnbroken_when_categoryRequestNotificationRendered() throws Exception {
        var realEmailService = emailServiceWithRealEngine();
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);

        String reviewUrl =
                "https://api.beautica.app/api/v1/service-categories/requests/review?token=tok-EN_unbroken-123";

        log.debug("Act: render and inspect the href for the unbroken token");
        realEmailService.sendCategoryRequestNotification(
                "admin@beautica.app", "user-123", "NAIL_ART", "Нейл-арт", reviewUrl);

        String html = realMessage.getContent().toString();
        assertThat(html)
                .as("the approval href must contain the full reviewUrl with its token intact")
                .contains(reviewUrl);
    }

    // ── phase 441: one ERROR per failed send at every MailSendFailures call site ─────

    private static final String ADMIN_EMAIL = "admin@beautica.app";

    /** Runs {@code action} with an SMTP send failure and returns every event EmailService logged. */
    private java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> logsOfFailedSend(Runnable action) {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        when(templateEngine.process(org.mockito.ArgumentMatchers.anyString(),
                any(org.thymeleaf.context.IContext.class))).thenReturn("<html>x</html>");
        doThrow(new MailSendException("550 rejected " + ADMIN_EMAIL)).when(mailSender).send(any(MimeMessage.class));
        ch.qos.logback.classic.Logger serviceLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EmailService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            action.run();
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }
        return appender.list;
    }

    private static void assertOneSmtpError(java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> events,
                                           String context) {
        assertThat(events).filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR).singleElement()
                .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .isEqualTo("External call failed service=brevo-smtp op=send http=- reason=rejected to=- detail="
                        + context + " MailSendException");
        assertThat(events).noneMatch(e -> e.getFormattedMessage().contains("550 rejected"));
    }

    @Test
    @DisplayName("phase 441: category-request SMTP failure — exactly one brevo-smtp ERROR")
    void should_logOneBrevoSmtpError_when_categoryRequestSendFails() {
        var events = logsOfFailedSend(() -> emailService.sendCategoryRequestNotification(ADMIN_EMAIL,
                java.util.UUID.randomUUID().toString(), "Нігті", "Owner", "https://beautica.app/r?t=x"));

        assertOneSmtpError(events, "category-request");
    }

    @Test
    @DisplayName("phase 441: service-type-suggestion SMTP failure — exactly one brevo-smtp ERROR")
    void should_logOneBrevoSmtpError_when_serviceTypeSuggestionSendFails() {
        var events = logsOfFailedSend(() -> emailService.sendServiceTypeSuggestionNotification(ADMIN_EMAIL,
                java.util.UUID.randomUUID().toString(), "Нігті", "Гель", "desc", "https://beautica.app/r?t=x"));

        assertOneSmtpError(events, "service-type-suggestion");
    }

    @Test
    @DisplayName("phase 441: support-contact SMTP failure — one brevo-smtp ERROR, the audit line stays WARN")
    void should_logOneErrorAndAuditWarn_when_supportContactSendFails() {
        var events = logsOfFailedSend(() -> emailService.sendSupportContactEmail(ADMIN_EMAIL,
                java.util.UUID.randomUUID().toString(), "user@example.com", "Help", "body", java.util.List.of()));

        assertOneSmtpError(events, "support-contact");
        assertThat(events).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                && e.getFormattedMessage().startsWith("Failed to send support contact email"));
    }

    @Test
    @DisplayName("phase 441: content-report SMTP failure — one brevo-smtp ERROR, the report-id line stays WARN")
    void should_logOneErrorAndReportWarn_when_contentReportSendFails() {
        var reportId = java.util.UUID.randomUUID();
        var model = new com.beautica.moderation.dto.ContentReportEmailModel(reportId,
                com.beautica.moderation.entity.ReportTargetType.REVIEW, java.util.UUID.randomUUID(),
                com.beautica.moderation.entity.ReportReason.SPAM, java.util.UUID.randomUUID(), false,
                com.beautica.moderation.dto.ReportedContent.review("text", (short) 2, true), null, 1,
                java.util.List.of());

        var events = logsOfFailedSend(() -> emailService.sendContentReportEmail(ADMIN_EMAIL, model));

        assertOneSmtpError(events, "content-report");
        assertThat(events).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                && e.getFormattedMessage().contains(reportId.toString()));
    }
}
