package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.common.util.SchemeGuard;
import com.beautica.user.User;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

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
    private static final ClassPathResource LOGO =
            new ClassPathResource("static/email/beautica-logo.png");

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

    public void sendVerificationEmail(String to, String rawOtp) {
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject("Код підтвердження Beautica");

            // Render template — code is ONLY passed as a context variable, never logged
            var ctx = new Context();
            ctx.setVariable("code", rawOtp);
            String html = templateEngine.process("email/verify-email", ctx);
            helper.setText(html, true);

            // Embed logo as CID inline attachment
            helper.addInline("beauticaLogo", LOGO);

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
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
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
