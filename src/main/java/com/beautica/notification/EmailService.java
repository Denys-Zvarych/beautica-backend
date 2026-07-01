package com.beautica.notification;

import com.beautica.support.dto.SupportAttachment;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

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

    /**
     * Sends the platform admin a "new service-type suggestion" email with a
     * Review/Approve/Reject link carrying the raw single-use token.
     *
     * <p>All dynamic values are rendered through Thymeleaf with auto-escaping, so a
     * malicious suggested name or description cannot inject markup; the subject is a
     * constant prefix + the already-validated category name (no CR/LF can reach a
     * header because the fields come from the validated suggestion DTO).
     *
     * @param toEmail        admin recipient
     * @param requesterId    requester UUID (shown for audit, never the token)
     * @param categoryName   validated System-B category slug
     * @param suggestedName  suggested service-type name (auto-escaped on render)
     * @param description    optional description (auto-escaped on render; may be null)
     * @param reviewUrl      fully-built review link with the raw token query param
     */
    @Async("emailExecutor")
    public void sendServiceTypeSuggestionNotification(
            String toEmail,
            String requesterId,
            String categoryName,
            String suggestedName,
            String description,
            String reviewUrl) {
        try {
            Context ctx = new Context(Locale.of("uk"));
            ctx.setVariable("requesterId", requesterId);
            ctx.setVariable("categoryName", categoryName);
            ctx.setVariable("suggestedName", suggestedName);
            ctx.setVariable("description", description);
            ctx.setVariable("reviewUrl", reviewUrl);

            String html = templateEngine.process("email/service-type-suggestion", ctx);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Beautica: Нова пропозиція типу послуги — " + suggestedName);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MailException ex) {
            log.error("Failed to send service-type-suggestion notification email: {}", ex.getClass().getSimpleName());
        } catch (Exception ex) {
            log.error("Failed to send service-type-suggestion notification email: {}", ex.getClass().getSimpleName());
        }
    }

    /**
     * Sends the support inbox a Help / Contact-us message with the authenticated
     * sender's identity and any embedded file attachments.
     *
     * <p>The HTML body is rendered through Thymeleaf with auto-escaping, so a
     * malicious message body or subject cannot inject markup. The mail
     * {@code Subject} header is a constant prefix + the authenticated sender email
     * (a server-resolved, validated value), so no caller-supplied free text can
     * inject a CR/LF header. Attachments are added verbatim — their bytes, MIME
     * types, count, and total size are validated upstream in {@code SupportService}
     * before this method is called.
     *
     * <p>Returns a {@link CompletableFuture} so a fire-and-forget caller may ignore
     * it while a synchronous caller (or a test) can observe send failure rather than
     * having a {@code @Async} exception silently swallowed (Anti-Bug §H).
     *
     * @param toEmail       fixed support-inbox recipient (resolved from config)
     * @param senderUserId  authenticated user's UUID (audit, shown in body)
     * @param senderEmail   authenticated user's email (shown in body + subject)
     * @param subject       optional caller subject hint (auto-escaped; may be null/blank)
     * @param body          message text (auto-escaped on render)
     * @param attachments   already-validated attachments to embed (may be empty)
     */
    @Async("supportEmailExecutor")
    public CompletableFuture<Void> sendSupportContactEmail(
            String toEmail,
            String senderUserId,
            String senderEmail,
            String subject,
            String body,
            List<SupportAttachment> attachments) {
        try {
            Context ctx = new Context(Locale.of("uk"));
            ctx.setVariable("senderUserId", senderUserId);
            ctx.setVariable("senderEmail", senderEmail);
            ctx.setVariable("subject", subject);
            ctx.setVariable("body", body);

            String html = templateEngine.process("email/support-contact", ctx);

            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true: required so addAttachment can build a mixed body.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            // Reply-To is the authenticated sender so support can answer directly,
            // while From stays the platform no-reply (DMARC-aligned).
            helper.setReplyTo(senderEmail);
            helper.setSubject("[Beautica Support] message from " + senderEmail);
            helper.setText(html, true);

            for (SupportAttachment attachment : attachments) {
                helper.addAttachment(
                        attachment.filename(),
                        new ByteArrayResource(attachment.bytes()),
                        attachment.contentType());
            }

            mailSender.send(message);
            return CompletableFuture.completedFuture(null);
        } catch (Exception ex) {
            // Fire-and-forget: SupportService.contact returns 202 before this completes, so a
            // post-202 send failure would otherwise be silent. Log recipient inbox + sender
            // userId + exception type (no message body, no token bytes — no PII beyond the
            // audit identity already logged at accept time) so the drop is observable.
            log.error("Failed to send support contact email to {} from user {}: {}",
                    toEmail, senderUserId, ex.getClass().getSimpleName());
            return CompletableFuture.failedFuture(ex);
        }
    }
}
