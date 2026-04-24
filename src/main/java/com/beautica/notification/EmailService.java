package com.beautica.notification;

import com.beautica.common.exception.BusinessException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;
    private final String fromEmail;

    public EmailService(
            JavaMailSender mailSender,
            SpringTemplateEngine templateEngine,
            @Value("${app.invite.from-email:noreply@beautica.app}") String fromEmail
    ) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.fromEmail = fromEmail;
    }

    public void sendInviteEmail(String toEmail, String inviteLink) {
        var context = new Context();
        context.setVariable("inviteLink", inviteLink);
        context.setVariable("expiresHours", 72);

        String htmlContent = templateEngine.process("email/invite", context);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("You've been invited to Beautica");
            helper.setText(htmlContent, true);
            mailSender.send(message);
        } catch (MailException ex) {
            log.error("Failed to send invite email", ex);
            throw new BusinessException("Failed to send invite email");
        } catch (Exception ex) {
            log.error("Failed to send invite email", ex);
            throw new BusinessException("Failed to send invite email");
        }
    }
}
