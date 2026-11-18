package com.beautica.notification.service;

import com.beautica.notification.inapp.push.SecurityNoticeExpectedCopy;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailNotificationService.sendContactChangedEmail — the AUTH-D19 security-notice email (B516)")
class ContactChangedEmailTest {

    @Mock private JavaMailSender mailSender;

    private EmailNotificationService service;
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
        service = new EmailNotificationService(mailSender, engine, "noreply@beautica.app");
        message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
    }

    private String renderedHtml() throws Exception {
        message.saveChanges();
        var out = new ByteArrayOutputStream();
        message.writeTo(out);
        return decoded((MimeMultipart) message.getContent());
    }

    private static String decoded(MimeMultipart multipart) throws Exception {
        var html = new StringBuilder();
        for (int i = 0; i < multipart.getCount(); i++) {
            Object content = multipart.getBodyPart(i).getContent();
            if (content instanceof MimeMultipart nested) {
                html.append(decoded(nested));
            } else if (content instanceof String text) {
                html.append(text);
            }
        }
        return html.toString();
    }

    @Test
    @DisplayName("a phone-change email carries the phone subject, the masked new number and the support line")
    void should_renderMaskedPhoneAndSupportLine_when_phoneChanged() throws Exception {
        service.sendContactChangedEmail("olena@beautica.test", ContactChangeKind.PHONE, "+380*****4567");

        verify(mailSender).send(message);
        assertThat(message.getSubject()).isEqualTo(SecurityNoticeExpectedCopy.EMAIL_SUBJECT_PHONE);
        String html = renderedHtml();
        assertThat(html).contains("+380*****4567").contains(SecurityNoticeExpectedCopy.SUPPORT_LINE);
        assertThat(new String(html.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .doesNotContainPattern("\\b\\d{6}\\b");
    }

    @Test
    @DisplayName("an email-change email carries the email subject and the masked new address")
    void should_renderMaskedEmail_when_emailChanged() throws Exception {
        service.sendContactChangedEmail("old@beautica.test", ContactChangeKind.EMAIL, "n***@beautica.test");

        assertThat(message.getSubject()).isEqualTo(SecurityNoticeExpectedCopy.EMAIL_SUBJECT_EMAIL);
        assertThat(renderedHtml()).contains("n***@beautica.test").contains(SecurityNoticeExpectedCopy.SUPPORT_LINE);
    }
}
