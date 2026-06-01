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
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Locale;

/**
 * Legacy email transport retained for {@link #sendAdminNotification} (still wired
 * into {@code ServiceCatalogService} service-type suggestion path) and the new
 * {@link #sendCategoryRequestNotification} admin-approval email.
 *
 * <p>The invite/booking email paths are owned by {@code EmailNotificationService}
 * via the outbox drain worker. New transactional user-facing emails belong there;
 * the admin-notification emails live here because they are fire-and-forget ops
 * notifications, not outbox-tracked user mail.
 */
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
            log.error("Failed to send admin notification email: {}", ex.getClass().getSimpleName());
        } catch (Exception ex) {
            log.error("Failed to send admin notification email: {}", ex.getClass().getSimpleName());
        }
    }

    /**
     * Sends the platform admin a "new service-category request" email with an
     * Approve/Reject link carrying the raw single-use token.
     *
     * <p>All dynamic values are rendered through Thymeleaf with auto-escaping, so
     * a malicious display name cannot inject markup; the subject is plain text and
     * header-injection-sanitized (no CR/LF can reach a header because requester /
     * name come from validated fields and the subject is a constant prefix + the
     * already-sanitized category name).
     *
     * @param toEmail       admin recipient
     * @param requesterId   requester UUID (shown for audit, never the token)
     * @param categoryName  validated uppercase wire name
     * @param displayName   human-readable label (auto-escaped on render)
     * @param reviewUrl     fully-built approval link with the raw token query param
     */
    @Async("emailExecutor")
    public void sendCategoryRequestNotification(
            String toEmail,
            String requesterId,
            String categoryName,
            String displayName,
            String reviewUrl) {
        try {
            Context ctx = new Context(Locale.of("uk"));
            ctx.setVariable("requesterId", requesterId);
            ctx.setVariable("categoryName", categoryName);
            ctx.setVariable("displayName", displayName);
            ctx.setVariable("reviewUrl", reviewUrl);

            String html = templateEngine.process("email/category-request", ctx);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Beautica: Новий запит на категорію послуги — " + categoryName);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MailException ex) {
            log.error("Failed to send category-request notification email: {}", ex.getClass().getSimpleName());
        } catch (Exception ex) {
            log.error("Failed to send category-request notification email: {}", ex.getClass().getSimpleName());
        }
    }
}
