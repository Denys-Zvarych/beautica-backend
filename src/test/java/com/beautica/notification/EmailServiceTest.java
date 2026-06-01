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
}
