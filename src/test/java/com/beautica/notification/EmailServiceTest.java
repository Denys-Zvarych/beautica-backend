package com.beautica.notification;

import com.beautica.common.exception.BusinessException;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IContext;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        emailService = new EmailService(mailSender, templateEngine, "noreply@beautica.app", 48L);
    }

    @Test
    @DisplayName("sendInviteEmail calls mailSender.send with the correct recipient and populates template context")
    void should_callMailSenderSend_when_sendInviteEmailCalled() throws Exception {
        var toEmail = "master@example.com";
        var inviteLink = "http://localhost:3000/invite/accept?token=abc123"; // EmailService accepts the link opaquely; HTTPS scheme validation belongs in InviteService
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        log.debug("Arrange: real MimeMessage from empty Session so recipient and subject are inspectable");

        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(any(String.class), any(IContext.class))).thenReturn("<html>invite</html>");

        ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        log.debug("Act: sendInviteEmail to={} with invite link and salon name", toEmail);
        emailService.sendInviteEmail(toEmail, inviteLink, "Test Salon");

        verify(templateEngine).process(templateCaptor.capture(), contextCaptor.capture());
        assertThat(templateCaptor.getValue())
                .as("template name must be email/invite")
                .isEqualTo("email/invite");

        Context capturedContext = (Context) contextCaptor.getValue();
        assertThat(capturedContext.getVariable("salonName"))
                .as("salonName must equal the passed salon name")
                .isEqualTo("Test Salon");
        assertThat(capturedContext.getVariable("expiresHours"))
                .as("expiresHours must equal the configured 48L")
                .isEqualTo(48L);
        assertThat(capturedContext.getVariable("inviteLink"))
                .as("inviteLink must equal the passed invite link")
                .isEqualTo(inviteLink);

        verify(mailSender).send(realMessage);

        assertThat(realMessage.getRecipients(Message.RecipientType.TO))
                .as("To: must be set to the toEmail parameter")
                .isNotNull()
                .hasSize(1);
        assertThat(realMessage.getRecipients(Message.RecipientType.TO)[0].toString())
                .as("To: address must contain the recipient email")
                .contains(toEmail);
    }

    @Test
    @DisplayName("sendInviteEmail throws BusinessException when MailException occurs")
    void should_throwBusinessException_when_mailSenderThrowsMailException() {
        var toEmail = "fail@example.com";
        var inviteLink = "http://localhost:3000/invite/accept?token=failtoken";
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        log.debug("Arrange: mailSender.send will throw MailSendException");

        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>invite</html>");
        doThrow(new MailSendException("SMTP connection failed")).when(mailSender).send(any(MimeMessage.class));

        log.debug("Act: sendInviteEmail to={} when mailSender throws MailSendException — must be translated to BusinessException", toEmail);
        assertThatThrownBy(() -> emailService.sendInviteEmail(toEmail, inviteLink, "Test Salon"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Failed to send invite email");
    }
}
