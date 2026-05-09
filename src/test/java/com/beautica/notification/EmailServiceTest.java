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

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(mailSender, "noreply@beautica.app");
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
}
