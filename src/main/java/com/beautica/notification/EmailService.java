package com.beautica.notification;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Legacy email transport retained only for {@link #sendAdminNotification}, which is
 * still wired into {@code ServiceCatalogService} (admin notification path, not the
 * outbox-driven booking/invite flow).
 *
 * <p>The invite email path is now owned by {@code NotificationService} →
 * {@code EmailNotificationService} via the outbox drain worker (Phase 5.10+).
 * Do not add new methods here — extend {@code EmailNotificationService} instead.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String fromEmail;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${app.invite.from-email:noreply@beautica.app}") String fromEmail
    ) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    @Async("emailExecutor")
    public void sendAdminNotification(String toEmail, String subject, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } catch (MailException ex) {
            log.error("Failed to send admin notification email: {}", ex.getMessage());
        } catch (Exception ex) {
            log.error("Failed to send admin notification email: {}", ex.getMessage());
        }
    }
}
