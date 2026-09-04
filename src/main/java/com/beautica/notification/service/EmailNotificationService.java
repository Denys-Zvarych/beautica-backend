package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.common.util.SchemeGuard;
import com.beautica.master.entity.Master;
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
import java.time.OffsetDateTime;
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
    /** Visit end time in the «(до HH:mm)» suffix — same Kyiv zone as {@link #DATE_FMT}. */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MINUTES_PER_HOUR = 60;
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

    /**
     * Sends the PROVIDER the "new booking" e-mail — for a whole multi-service visit, not just its
     * lead service.
     *
     * <p>Takes a {@link BookingVisit} because exactly ONE {@code NEW_BOOKING} outbox row is
     * enqueued per visit; {@code serviceNames} carries every chained service in visit order and the
     * template loops over it. A single-service booking arrives as
     * {@link BookingVisit#single(Booking)}, so {@code serviceNames} is a one-element list,
     * {@code serviceLabel} is the singular «Послуга» and {@code visitDuration} is {@code null}
     * (its row is suppressed) — the rendered e-mail is byte-identical to the pre-visit output.
     */
    public void sendNewBookingEmail(String to, BookingVisit visit) {
        Booking booking = visit.lead();
        var ctx = new Context();
        ctx.setVariable("masterName", masterDisplayName(booking));
        ctx.setVariable("clientName", resolveClientName(booking));
        applyVisitVariables(ctx, visit);
        send(to, "Нове бронювання", "email/new-booking", ctx);
    }

    public void sendBookingRescheduledEmail(String to, Booking booking) {
        var ctx = new Context();
        ctx.setVariable("masterName", masterDisplayName(booking));
        ctx.setVariable("clientName", fullName(booking.getClient()));
        ctx.setVariable("serviceName", booking.getMasterService().getServiceDefinition().getName());
        ctx.setVariable("startsAt", formatStartsAt(booking));
        send(to, "Бронювання перенесено", "email/booking-rescheduled-provider", ctx);
    }

    /**
     * Client-facing twin of {@link #sendBookingRescheduledEmail} (Phase 27.3) — sent when a
     * PROVIDER moves a booking, mirroring that method's structure/variables exactly, re-addressed
     * to the client with client-appropriate copy.
     */
    public void sendBookingRescheduledClientEmail(String to, Booking booking) {
        var ctx = new Context();
        ctx.setVariable("masterName", masterDisplayName(booking));
        ctx.setVariable("clientName", fullName(booking.getClient()));
        ctx.setVariable("serviceName", booking.getMasterService().getServiceDefinition().getName());
        ctx.setVariable("startsAt", formatStartsAt(booking));
        send(to, "Бронювання перенесено", "email/booking-rescheduled-client", ctx);
    }

    /**
     * Sends the CLIENT the "booking confirmed" e-mail — the client-facing twin of
     * {@link #sendNewBookingEmail(String, BookingVisit)}, and visit-aware for the same reason and
     * with the same single-service guarantee.
     */
    public void sendBookingConfirmedEmail(String to, BookingVisit visit) {
        var ctx = new Context();
        ctx.setVariable("clientName", fullName(visit.lead().getClient()));
        applyVisitVariables(ctx, visit);
        send(to, "Бронювання підтверджено", "email/booking-confirmed", ctx);
    }

    /**
     * Sends the client a "please leave a review" email after a booking is completed (Phase 18.5).
     *
     * <p>The {@code reviewUrl} deep link is scheme-guarded via {@link SchemeGuard#isAllowedScheme}
     * <em>before</em> the template is rendered (mirrors {@link #sendInviteEmail}) so an unsafe scheme
     * can never reach the CTA. All template variables are system-derived — no free-text user input.
     *
     * @param to        the client's email address (CR/LF stripped by the shared {@code send} helper)
     * @param booking   the completed booking (client, master, service, startsAt read server-side)
     * @param reviewUrl the fully-built {@code https://.../bookings/{id}/review} deep link
     */
    public void sendReviewRequestEmail(String to, Booking booking, String reviewUrl) {
        if (!SchemeGuard.isAllowedScheme(reviewUrl)) {
            throw new IllegalArgumentException(
                    "reviewUrl must use https:// scheme or http://localhost — caller must validate before reaching email transport");
        }
        var ctx = new Context();
        ctx.setVariable("clientName", fullName(booking.getClient()));
        ctx.setVariable("masterName", masterDisplayName(booking));
        ctx.setVariable("serviceName", booking.getMasterService().getServiceDefinition().getName());
        ctx.setVariable("startsAt", formatStartsAt(booking));
        ctx.setVariable("reviewUrl", reviewUrl);
        send(to, "Оцініть візит", "email/review-request", ctx);
    }

    /**
     * Sends the PROVIDER a "this visit is still open — close it" nudge (Phase 29.5). {@code
     * bookingUrl} is scheme-guarded before rendering (mirrors {@link #sendReviewRequestEmail}).
     * The template renders only service name, client display name, and visit date/time — per the
     * locked track-25 rule, {@code clientComment}/{@code clientCancellationNote}/{@code
     * providerComment} are never passed into this context and never appear in this email.
     *
     * @param to        the provider's (master's) email address
     * @param booking   the elapsed, still-{@code CONFIRMED} booking
     * @param bookingUrl the fully-built {@code https://.../bookings/{id}} deep link
     */
    public void sendClosureReminderEmail(String to, Booking booking, String bookingUrl) {
        if (!SchemeGuard.isAllowedScheme(bookingUrl)) {
            throw new IllegalArgumentException(
                    "bookingUrl must use https:// scheme or http://localhost — caller must validate before reaching email transport");
        }
        var ctx = new Context();
        ctx.setVariable("clientName", resolveClientName(booking));
        ctx.setVariable("serviceName", booking.getMasterService().getServiceDefinition().getName());
        ctx.setVariable("startsAt", formatStartsAt(booking));
        ctx.setVariable("bookingUrl", bookingUrl);
        send(to, "Візит завершився — позначте його статус", "email/closure-reminder", ctx);
    }

    /**
     * Sends the CLIENT (or, via {@code NotificationService}, notifies a guest by SMS instead) the
     * "the salon closed and your booking is cancelled" e-mail (Phase 269/293 — {@code
     * SALON_CLOSED}). Visit-aware for the same reason {@link #sendNewBookingEmail} is: exactly ONE
     * outbox row is enqueued per VISIT (D12), keyed to the visit's representative booking, and the
     * template must name every declined service of that visit, not just the representative's.
     *
     * <p>Renders only the service name(s) and visit date/time via {@link #applyVisitVariables} —
     * no booking note of any kind ({@code clientComment}/{@code clientCancellationNote}/
     * {@code providerComment}) is read here or passed into the template context (locked track-25
     * rule, D10). {@link BookingVisit} exposes no accessor for any of them, so there is nothing to
     * accidentally wire in.
     */
    public void sendSalonClosedEmail(String to, BookingVisit visit) {
        var ctx = new Context();
        ctx.setVariable("clientName", fullName(visit.lead().getClient()));
        applyVisitVariables(ctx, visit);
        send(to, "Салон закрито — ваше бронювання скасовано", "email/salon-closed", ctx);
    }

    /**
     * Sends the CLIENT the "one service line was cancelled" e-mail — the PER-ITEM decline
     * ({@code AppointmentTransitionService#declineAppointmentItem}, and every legacy single-service
     * decline). Exactly one service is named, because exactly one was cancelled; the rest of the
     * visit is still on. See {@link #sendVisitDeclinedEmail(String, BookingVisit)} for the
     * whole-visit twin, and {@code NotificationService#isWholeVisitDecline} for how the two routes
     * are told apart.
     */
    public void sendBookingDeclinedEmail(String to, Booking booking) {
        sendDeclined(to, BookingVisit.single(booking), booking.getProviderComment());
    }

    /**
     * Sends the CLIENT the "the whole visit was cancelled" e-mail — the provider declined the
     * appointment header and every service line with it
     * ({@code AppointmentTransitionService#declineAppointment}).
     *
     * <p>Naming every service is the point: one {@code STATUS_CHANGED} row is enqueued for the whole
     * visit, keyed to the lead booking, so this e-mail previously named ONE service out of N and the
     * client turned up for the others.
     *
     * <p>The note is read off {@link BookingVisit#lead()}, exactly as the per-item path reads it off
     * its booking. <b>On this route it is in practice always {@code null}</b>:
     * {@code AppointmentTransitionService#declineAppointment} writes the provider's reason onto the
     * APPOINTMENT header, not onto any item row, so {@code lead().getProviderComment()} has nothing
     * to render and the e-mail goes out reason-less. That is a known product gap awaiting a decision
     * — do not "fix" it by reaching into the header here; the whole-visit reason has to be threaded
     * through {@link BookingVisit} deliberately, or the header write has to fan out to the items.
     */
    public void sendVisitDeclinedEmail(String to, BookingVisit visit) {
        sendDeclined(to, visit, visit.lead().getProviderComment());
    }

    /** The one renderer both decline routes share, so their markup can never drift. */
    private void sendDeclined(String to, BookingVisit visit, String comment) {
        var ctx = new Context();
        ctx.setVariable("clientName", fullName(visit.lead().getClient()));
        ctx.setVariable("serviceNames", visit.serviceNames());
        ctx.setVariable("serviceLabel", visit.isMultiService() ? "Послуги" : "Послуга");
        ctx.setVariable("comment", comment);
        send(to, "Бронювання скасовано", "email/booking-declined", ctx);
    }

    /**
     * Sends a password-reset 6-digit OTP email (Phase A4 — replaces
     * {@code sendPasswordResetEmail}'s emailed reset-link with an OTP, mirroring
     * {@link #sendVerificationEmail} exactly).
     *
     * <p>Delivery failures are non-fatal and are swallowed after logging (template + exception
     * class only — {@code user}'s email is PII and is never logged). The caller dispatches
     * this method via {@code emailExecutor} in an {@code afterCommit} synchronization so the
     * email is never sent if the DB transaction rolls back.
     *
     * @param user   the account requesting the reset — {@code user.getEmail()} is the
     *               recipient; never logged. {@code reset-password-otp.html} does not
     *               reference the user's name, so it is deliberately NOT passed into the
     *               template context here (avoids exposing PII to a future template edit
     *               that adds a {@code ${firstName}} reference without re-auditing this
     *               method)
     * @param rawOtp the 6-digit code — passed ONLY as a template variable, never logged
     */
    public void sendPasswordResetOtpEmail(User user, String rawOtp) {
        try {
            var helper = buildMimeHelper(user.getEmail(), true);
            helper.setSubject("Код для скидання паролю Beautica");

            var ctx = new Context();
            ctx.setVariable("code", rawOtp);
            String html = templateEngine.process("email/reset-password-otp", ctx);
            helper.setText(html, true);

            addLogoInline(helper);

            mailSender.send(helper.getMimeMessage());
        } catch (MessagingException | MailException e) {
            // Non-fatal: delivery failure is acceptable; the user can request a new code.
            // The email is PII — never log it. Log template + exception type only.
            log.error("sendPasswordResetOtpEmail failed: template=email/reset-password-otp exception={}",
                    e.getClass().getSimpleName());
        }
    }

    public void sendVerificationEmail(String to, String rawOtp) {
        try {
            var helper = buildMimeHelper(to, true);
            helper.setSubject("Код підтвердження Beautica");

            // Render template — code is ONLY passed as a context variable, never logged
            var ctx = new Context();
            ctx.setVariable("code", rawOtp);
            String html = templateEngine.process("email/verify-email", ctx);
            helper.setText(html, true);

            // Embed logo as CID inline attachment
            addLogoInline(helper);

            mailSender.send(helper.getMimeMessage());
        } catch (MessagingException | MailException e) {
            log.error("sendVerificationEmail failed: template=email/verify-email exception={}", e.getClass().getSimpleName());
            // delivery failure is non-fatal — caller retries via resend endpoint
        }
    }

    /**
     * Sends the provider a "client cancelled" notification (Phase 24.x / fixed track 25.x — D3).
     *
     * <p><b>Fix D3.</b> This previously set {@code comment} from {@code booking.getClientComment()}
     * — the booking-CREATION note (set once at POST /bookings, from
     * {@code CreateBookingRequest.clientComment}) — and rendered it under the template's
     * «Причина скасування» (cancellation reason) label. A client who wrote a creation-time note
     * ("please, no fragrance") and later cancelled with an unrelated reason would cause the
     * provider to see their creation note mislabeled as the cancellation reason, while the
     * client's ACTUAL cancellation note ({@code clientCancellationNote}, persisted by
     * {@code BookingService.cancelBooking} since the D2 fix) was silently dropped. Now
     * {@code cancellationNote} carries the real cancellation-time note under its own label, and
     * {@code creationNote} carries the booking-creation note under a separate, distinctly-labeled
     * row — the two can never swap slots again.
     */
    public void sendClientCancelledEmail(String to, Booking booking) {
        var ctx = new Context();
        ctx.setVariable("masterName", masterDisplayName(booking));
        ctx.setVariable("clientName", resolveClientName(booking));
        ctx.setVariable("serviceName", booking.getMasterService().getServiceDefinition().getName());
        ctx.setVariable("startsAt", formatStartsAt(booking));
        ctx.setVariable("cancellationNote", booking.getClientCancellationNote());
        ctx.setVariable("creationNote", booking.getClientComment());
        send(to, "Клієнт скасував бронювання", "email/booking-cancelled-provider", ctx);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link MimeMessageHelper} carrying the envelope every send path in this
     * service shares: the {@code UTF-8} charset, the platform {@code From} address, and the
     * CR/LF-stripped {@code To} (header-injection guard). Callers then set the subject + body
     * and add any inline CID attachments before handing {@link MimeMessageHelper#getMimeMessage()}
     * to {@link JavaMailSender#send}.
     *
     * <p>Extracted so future hardening of the common envelope propagates to <em>every</em> path
     * uniformly — the multipart verification/reset paths previously hand-rolled this setup and
     * could silently drift from {@link #send}.
     *
     * @param to        recipient address — CR/LF stripped to prevent header injection
     * @param multipart whether a multipart body is needed (required for inline CID images)
     */
    private MimeMessageHelper buildMimeHelper(String to, boolean multipart) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(to.replaceAll("[\r\n]", ""));
        return helper;
    }

    private void send(String to, String subject, String template, Context ctx) {
        try {
            String html = templateEngine.process(template, ctx);
            MimeMessageHelper helper = buildMimeHelper(to, true);
            helper.setSubject(subject);
            helper.setText(html, true);
            addLogoInline(helper);
            mailSender.send(helper.getMimeMessage());
        } catch (MessagingException | MailException ex) {
            // Deliberate swallow: delivery failures must not crash the outbox drain loop.
            // 'to' is PII — never log it. Log template + exception type only.
            log.error("Failed to send email [template={}]: {}", template, ex.getClass().getSimpleName());
        } catch (Exception ex) {
            log.error("Unexpected error sending email [template={}]: {}", template, ex.getClass().getSimpleName());
        }
    }

    /**
     * Joins a registered account's name parts, skipping any that are absent (2026-09 audit finding
     * 10 — the sibling of {@link #masterDisplayName(Booking)}'s defect).
     *
     * <p>{@code users.last_name} is NULLABLE ({@code V1__init_schema.sql}); {@code first_name} is
     * not, but is guarded on the same footing so this helper cannot render the literal string
     * {@code "null"} into a client- or provider-facing mail body from ANY input. Byte-identical
     * output to the previous concatenation for every both-parts-present name — the only case any
     * existing assertion pins.
     */
    private static String fullName(User user) {
        return joinNameParts(user.getFirstName(), user.getLastName());
    }

    /**
     * The single name-joining kernel for this class: {@code "First Last"}, {@code "First"},
     * {@code "Last"}, or {@code ""} — never {@code "First null"} and never a stray separator.
     *
     * <p>Extracted rather than inlined twice so {@link #fullName(User)} and
     * {@link #masterDisplayName(Booking)} cannot drift on spacing; {@link #resolveClientName(Booking)}
     * routes its guest branch through it for the same reason.
     */
    private static String joinNameParts(String firstName, String lastName) {
        boolean hasFirst = firstName != null && !firstName.isBlank();
        boolean hasLast = lastName != null && !lastName.isBlank();
        if (hasFirst && hasLast) {
            return firstName + " " + lastName;
        }
        if (hasFirst) {
            return firstName;
        }
        return hasLast ? lastName : "";
    }

    /**
     * The provider's name for the mail body — {@code Master#displayFirstName()} /
     * {@code displayLastName()}, never {@code getMaster().getUser()} (V157 / phase 294 D3).
     *
     * <p>An email is written about a HISTORICAL booking, and its master's staff account may have
     * been hard-deleted (salon deletion, 2026-09-04 reversal), leaving a detached {@code masters}
     * stub whose only content is the name snapshot taken at detach time. Reading the deleted
     * {@code users} row here would NPE; reading the snapshot renders exactly the name the client
     * saw when they booked.
     *
     * <p>Spacing is byte-identical to {@link #fullName(User)} for every both-parts-present name, so
     * no rendered mail body changes for any attached master (the existing assertions on
     * {@code "Майстер Іванов"} and friends are untouched).
     *
     * <p><b>Null-handling is NOT the raw concatenation it used to be</b> (2026-09 audit finding 9).
     * A DETACHED master legitimately carries a null {@code detached_last_name} —
     * {@code chk_masters_detachment_coherent} (V157) requires only the FIRST name, precisely because
     * {@code users.last_name} is itself nullable — so the old
     * {@code displayFirstName() + " " + displayLastName()} rendered the literal
     * {@code "Олена null"} into a CLIENT-FACING mail body. Both parts now route through
     * {@link #joinNameParts}, which emits present parts only.
     */
    private static String masterDisplayName(Booking booking) {
        Master master = booking.getMaster();
        return joinNameParts(master.displayFirstName(), master.displayLastName());
    }

    /**
     * Resolves the display name of the person who made this booking, for the master-facing
     * "new booking" / "client cancelled" emails. A guest (LINK) booking has no registered
     * account (V89 {@code chk_bookings_guest_fields} — {@code client_id} is null), so
     * {@code fullName(booking.getClient())} would NPE unconditionally for every guest
     * booking. Falls back to the OTP-verified guest identity, mirroring
     * {@code NotificationService.resolveClientName} and {@code BookingDetailResponse.from}.
     * This is the master's own booking, so surfacing the guest's name is not a PII leak;
     * {@code guestPhone} is intentionally never read here.
     */
    private static String resolveClientName(Booking booking) {
        User client = booking.getClient();
        if (client != null) {
            return fullName(client);
        }
        // Routed through the shared kernel (2026-09 audit finding 10) — same output as the previous
        // null-to-empty + trim() form for every input, including a guest with only one name part,
        // but now it cannot drift from fullName()/masterDisplayName() on spacing.
        return joinNameParts(booking.getGuestName(), booking.getGuestSurname());
    }

    private static String formatStartsAt(Booking booking) {
        return formatStartsAt(booking.getStartsAt());
    }

    private static String formatStartsAt(OffsetDateTime startsAt) {
        return startsAt.atZoneSameInstant(KYIV).format(DATE_FMT);
    }

    /**
     * Sets the three visit-shaped template variables the two visit-aware templates share, so
     * {@code new-booking.html} and {@code booking-confirmed.html} can never disagree about how a
     * multi-service visit is described:
     *
     * <ul>
     *   <li>{@code serviceNames} — every chained service name, in visit order; the template's
     *       {@code th:each} emits one table row per entry.</li>
     *   <li>{@code serviceLabel} — «Послуга» for one service, «Послуги» for several. Resolved in
     *       Java, not in Thymeleaf, so the wording is unit-testable and shared.</li>
     *   <li>{@code visitDuration} — total duration + visit end time, e.g. «2 год 30 хв (до 13:15)».
     *       <b>{@code null} for a single-service booking</b>, which suppresses the row entirely
     *       ({@code th:if}) and is what keeps the legacy single-service render byte-identical.</li>
     * </ul>
     *
     * <p>{@code startsAt} is taken from the visit ({@code items[0]}), not from {@code lead} — the
     * two are the same row for every create-time notification, but ordering by {@code startsAt}
     * survives a later per-item reschedule while "the row the outbox points at" does not.
     *
     * <p>No note field ({@code clientComment} / {@code clientCancellationNote} /
     * {@code providerComment}) is read here or passed into either context — locked track-25 rule.
     */
    private static void applyVisitVariables(Context ctx, BookingVisit visit) {
        ctx.setVariable("serviceNames", visit.serviceNames());
        ctx.setVariable("serviceLabel", visit.isMultiService() ? "Послуги" : "Послуга");
        ctx.setVariable("startsAt", formatStartsAt(visit.startsAt()));
        ctx.setVariable("visitDuration", visit.isMultiService() ? formatVisitDuration(visit) : null);
    }

    /** «2 год 30 хв (до 13:15)» — total service time (buffers excluded) plus the visit's end. */
    private static String formatVisitDuration(BookingVisit visit) {
        return formatDuration(visit.totalDurationMinutes())
                + " (до " + visit.endsAt().atZoneSameInstant(KYIV).format(TIME_FMT) + ")";
    }

    /** «45 хв» / «2 год» / «2 год 30 хв». */
    private static String formatDuration(int totalMinutes) {
        int hours = totalMinutes / MINUTES_PER_HOUR;
        int minutes = totalMinutes % MINUTES_PER_HOUR;
        if (hours == 0) {
            return minutes + " хв";
        }
        return minutes == 0 ? hours + " год" : hours + " год " + minutes + " хв";
    }
}
