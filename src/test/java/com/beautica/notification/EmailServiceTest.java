package com.beautica.notification;

import com.beautica.common.exception.BusinessException;
import jakarta.mail.internet.MimeMessage;
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
import org.thymeleaf.context.IContext;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
    @DisplayName("sendInviteEmail calls mailSender.send with the correct recipient")
    void should_callMailSenderSend_when_sendInviteEmailCalled() throws Exception {
        var toEmail = "master@example.com";
        var inviteLink = "http://localhost:3000/invite/accept?token=abc123";
        var mimeMessage = mock(MimeMessage.class);
        log.debug("Arrange: mocked MimeMessage and Thymeleaf template rendering");

        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>invite</html>");

        log.debug("Act: sendInviteEmail to={} with invite link and salon name", toEmail);
        emailService.sendInviteEmail(toEmail, inviteLink, "Test Salon");

        verify(mailSender).send(mimeMessage);
    }

    @Test
    @DisplayName("sendInviteEmail throws BusinessException when MailException occurs")
    void should_throwBusinessException_when_mailSenderThrowsMailException() {
        var toEmail = "fail@example.com";
        var inviteLink = "http://localhost:3000/invite/accept?token=failtoken";
        var mimeMessage = mock(MimeMessage.class);
        log.debug("Arrange: mailSender.send will throw MailSendException");

        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>invite</html>");
        doThrow(new MailSendException("SMTP connection failed")).when(mailSender).send(any(MimeMessage.class));

        log.debug("Act: sendInviteEmail to={} when mailSender throws MailSendException — must be translated to BusinessException", toEmail);
        assertThatThrownBy(() -> emailService.sendInviteEmail(toEmail, inviteLink, "Test Salon"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Failed to send invite email");
    }
}
