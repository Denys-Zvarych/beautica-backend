package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.common.util.SchemeGuard;
import com.beautica.user.User;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Sends transactional emails for booking lifecycle events and master invitations.
 *
 * This service is intentionally synchronous — it is called from
 * {@link NotificationOutboxDrainWorker} which owns the async/retry boundary.
 * No {@code @Async} here.
 */
@Slf4j
@Service
public class EmailNotificationService {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("HH:mm, d MMMM yyyy", Locale.forLanguageTag("uk"));
    private static final String LOGO_CID = "beauticaLogo";

    /**
     * Logo bytes are read from the classpath exactly once at class load. A {@link ClassPathResource}
     * re-opens (and re-reads) the underlying stream on every {@code addInline} send, so the PNG was
     * being re-read per email. We cache the immutable byte[] and wrap a fresh {@link ByteArrayResource}
     * per attach: {@code MimeMessageHelper.addInline} only calls {@code getInputStream()} (read-only,
     * returns a new {@code ByteArrayInputStream} each time) — it never mutates the shared array.
     *
     * <p>Load failure is non-fatal: a missing/unreadable logo must never crash application boot
     * (a static-init throw becomes {@code ExceptionInInitializerError}) nor fail an email send.
     * On failure this stays {@code null}, a warning is logged once, and the send path skips
     * {@code addInline} — the email still goes out, just without the inline logo.
     */
    private static final byte[] LOGO_BYTES = loadLogoBytes();

    private static byte[] loadLogoBytes() {
        try {
            return new ClassPathResource("static/email/beautica-logo.png").getContentAsByteArray();
        } catch (IOException e) {
            // Graceful degradation: log once, send emails without the logo rather than crash boot.
            log.warn("Email logo not readable from classpath (static/email/beautica-logo.png); "
                    + "emails will be sent without the inline logo. exception={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** Fresh wrapper around the cached bytes per attach — JavaMail does not mutate the resource. */
    private static Resource logoResource() {
        return new ByteArrayResource(LOGO_BYTES) {
            @Override
            public String getFilename() {
                // MimeMessageHelper infers the inline part's content type from the filename extension.
                return "beautica-logo.png";
            }
        };
    }

    /** Attaches the inline logo when available; silently skips it when the logo failed to load. */
    private static void addLogoInline(MimeMessageHelper helper) throws MessagingException {
        if (LOGO_BYTES != null) {
            helper.addInline(LOGO_CID, logoResource());
        }
    }

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;
    private final String fromAddress;

    // Explicit constructor — @RequiredArgsConstructor cannot bind @Value defaults.
    public EmailNotificationService(
            JavaMailSender mailSender,
            SpringTemplateEngine templateEngine,
            @Value("${app.invite.from-email:noreply@beautica.app}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.fromAddress = fromAddress;
    }

    public void sendInviteEmail(String to, String inviteUrl, String salonName) {
        if (!SchemeGuard.isAllowedScheme(inviteUrl)) {
            throw new IllegalArgumentException(
                    "inviteUrl must use https:// scheme or http://localhost — caller must validate before reaching email transport");
        }
        var ctx = new Context();
        ctx.setVariable("salonName", salonName);
        ctx.setVariable("inviteUrl", inviteUrl);
        send(to, "Запрошення до салону", "email/invite-master", ctx);
    }

    public void sendNewBookingEmail(String to, Booking booking) {
        var ctx = new Context();
        ctx.setVariable("masterName", fullName(booking.getMaster().getUser()));
        ctx.setVariable("clientName", fullName(booking.getClient()));
        ctx.setVariable("serviceName", booking.getMasterService().getServiceDefinition().getName());
        ctx.setVariable("startsAt", formatStartsAt(booking));
        send(to, "Нове бронювання", "email/new-booking", ctx);
    }

    public void sendBookingRescheduledEmail(String to, Booking booking) {
        var ctx = new Context();
        ctx.setVariable("masterName", fullName(booking.getMaster().getUser()));
        ctx.setVariable("clientName", fullName(booking.getClient()));
        ctx.setVariable("serviceName", booking.getMasterService().getServiceDefinition().getName());
        ctx.setVariable("startsAt", formatStartsAt(booking));
        send(to, "Запит на перенесення", "email/booking-rescheduled-provider", ctx);
    }

    public void sendBookingConfirmedEmail(String to, Booking booking) {
        var ctx = new Context();
        ctx.setVariable("clientName", fullName(booking.getClient()));
        ctx.setVariable("serviceName", booking.getMasterService().getServiceDefinition().getName());
        ctx.setVariable("startsAt", formatStartsAt(booking));
        send(to, "Бронювання підтверджено", "email/booking-confirmed", ctx);
    }

    public void sendBookingDeclinedEmail(String to, Booking booking) {
        var ctx = new Context();
        ctx.setVariable("clientName", fullName(booking.getClient()));
        ctx.setVariable("serviceName", booking.getMasterService().getServiceDefinition().getName());
        ctx.setVariable("comment", booking.getProviderComment());
        send(to, "Бронювання відхилено", "email/booking-declined", ctx);
    }

    /**
     * Sends a password-reset link email.
     *
     * <p>Delivery failures are non-fatal and are swallowed after logging (template + exception
     * class only — {@code to} is PII and is never logged). The caller dispatches this method
     * via {@code emailExecutor} in an {@code afterCommit} synchronization so the email is
     * never sent if the DB transaction rolls back.
     *
     * @param to       recipient address — never logged
     * @param resetUrl absolute https URL containing the raw reset token as a query param;
     *                 must pass {@link com.beautica.common.util.SchemeGuard#isAllowedScheme}
     * @throws IllegalArgumentException if {@code resetUrl} fails the scheme guard (caller bug)
     */
    public void sendPasswordResetEmail(String to, String resetUrl) {
        if (!SchemeGuard.isAllowedScheme(resetUrl)) {
            throw new IllegalArgumentException(
                    "resetUrl must use https:// scheme or http://localhost — got an unsafe scheme");
        }
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to.replaceAll("[\r\n]", ""));
            helper.setSubject("Скидання паролю Beautica");

            var ctx = new Context();
            ctx.setVariable("resetUrl", resetUrl);
            String html = templateEngine.process("email/reset-password", ctx);
            helper.setText(html, true);

            addLogoInline(helper);

            mailSender.send(message);
        } catch (MessagingException | MailException e) {
            // Non-fatal: delivery failure is acceptable; the user can request a new link.
            // 'to' is PII — never log it. Log template + exception type only.
            log.error("sendPasswordResetEmail failed: template=email/reset-password exception={}", e.getClass().getSimpleName());
        }
    }

    public void sendVerificationEmail(String to, String rawOtp) {
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to.replaceAll("[\r\n]", ""));
            helper.setSubject("Код підтвердження Beautica");

            // Render template — code is ONLY passed as a context variable, never logged
            var ctx = new Context();
            ctx.setVariable("code", rawOtp);
            String html = templateEngine.process("email/verify-email", ctx);
            helper.setText(html, true);

            // Embed logo as CID inline attachment
            addLogoInline(helper);

            mailSender.send(message);
        } catch (MessagingException | MailException e) {
            log.error("sendVerificationEmail failed: template=email/verify-email exception={}", e.getClass().getSimpleName());
            // delivery failure is non-fatal — caller retries via resend endpoint
        }
    }

    public void sendClientCancelledEmail(String to, Booking booking) {
        var ctx = new Context();
        ctx.setVariable("masterName", fullName(booking.getMaster().getUser()));
        ctx.setVariable("clientName", fullName(booking.getClient()));
        ctx.setVariable("serviceName", booking.getMasterService().getServiceDefinition().getName());
        ctx.setVariable("startsAt", formatStartsAt(booking));
        ctx.setVariable("comment", booking.getClientComment());
        send(to, "Клієнт скасував бронювання", "email/booking-cancelled-provider", ctx);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void send(String to, String subject, String template, Context ctx) {
        try {
            String html = templateEngine.process(template, ctx);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to.replaceAll("[\r\n]", ""));
            helper.setSubject(subject);
            helper.setText(html, true);
            addLogoInline(helper);
            mailSender.send(message);
        } catch (MessagingException | MailException ex) {
            // Deliberate swallow: delivery failures must not crash the outbox drain loop.
            // 'to' is PII — never log it. Log template + exception type only.
            log.error("Failed to send email [template={}]: {}", template, ex.getClass().getSimpleName());
        } catch (Exception ex) {
            log.error("Unexpected error sending email [template={}]: {}", template, ex.getClass().getSimpleName());
        }
    }

    private static String fullName(User user) {
        return user.getFirstName() + " " + user.getLastName();
    }

    private static String formatStartsAt(Booking booking) {
        return booking.getStartsAt().atZoneSameInstant(KYIV).format(DATE_FMT);
    }
}
