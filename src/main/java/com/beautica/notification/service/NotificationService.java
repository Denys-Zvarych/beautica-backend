package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.TimeZones;
import com.beautica.common.util.SchemeGuard;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.notification.sms.SmsService;
import com.beautica.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Thin notification-side facade that dispatches email, push, and (guest-only) SMS notifications.
 *
 * <p>Methods are synchronous — invoked by {@code NotificationOutboxDrainWorker}, which already
 * runs after the originating transaction has committed (Phase 3 of the drain: {@code
 * @Transactional(propagation = NOT_SUPPORTED)}), so SMS dispatch here needs no additional
 * afterCommit synchronization of its own (unlike {@code BookingCancellationService}, which fires
 * its guest SMS from inside the original request's transaction). URL composition (and the HTTPS
 * scheme guard) for invite links lives in {@code InviteService.buildInviteLink}, not in this
 * class.
 */
@Slf4j
@Service
public class NotificationService {

    private static final int PUSH_BODY_MAX_LENGTH = 256;

    /** Cyrillic SMS segments are ~70 chars; 120 keeps a decline note to two segments. */
    private static final int SMS_COMMENT_MAX_LENGTH = 120;
    private static final DateTimeFormatter SMS_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter SMS_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Splits a provider note into whitespace-delimited tokens for the link-neutralization pass
     * in {@link #stripUrlsForSms}. Deliberately not "preserve original whitespace" — a removed
     * token's surrounding gap, and any run of tabs/newlines in the note, both collapse to a
     * single rejoining space (see {@link #stripUrlsForSms}).
     */
    private static final Pattern TOKEN_DELIMITER = Pattern.compile("\\s+");

    /**
     * One of the four "link-shaped" tests in {@link #isLinkShapedToken}: a {@code .} immediately
     * followed by 2+ ASCII letters, anywhere in the token. This replaces a maintained TLD
     * allowlist (the prior implementation's MEDIUM finding — {@code .xyz}/{@code .top}/
     * {@code .click} etc. are real, cheap, ICANN-delegated TLDs an attacker can register to
     * bypass any fixed list). Any dot-plus-letters shape is treated as a domain label, full
     * stop — there is nothing left to bypass.
     *
     * <p>It cannot fire on Cyrillic prose: the note is Ukrainian free text and this character
     * class is ASCII-only, so a sentence-ending {@code .} followed by a Cyrillic word never
     * matches. It also spares a single-letter Latin abbreviation run like {@code "a.s.a.p."} —
     * each {@code .} there is followed by exactly one letter before the next {@code .}, never
     * two. And it spares a decimal/time value like {@code "15.30"} — the class is letters only,
     * not digits. All three claims are exercised as explicit test cases in
     * {@code NotificationServiceTest}, not asserted here without having run them.
     */
    private static final Pattern DOMAIN_DOT_LETTERS = Pattern.compile("\\.[A-Za-z]{2,}");

    /**
     * The other content-shape test in {@link #isLinkShapedToken}: a bare IPv4 literal
     * ({@code d.d.d.d}), with or without a trailing path — Android's {@code Linkify.WEB_URLS}
     * auto-links a bare IP exactly like a hostname, and {@link #DOMAIN_DOT_LETTERS} alone does
     * not catch it (digits, not letters, follow each dot).
     */
    private static final Pattern BARE_IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    private final EmailNotificationService emailService;
    private final PushNotificationService pushService;
    private final SmsService smsService;
    private final BookingSmsProperties smsProperties;
    private final String frontendBaseUrl;

    // Explicit constructor — @RequiredArgsConstructor cannot bind the @Value frontend base URL.
    public NotificationService(
            EmailNotificationService emailService,
            PushNotificationService pushService,
            SmsService smsService,
            BookingSmsProperties smsProperties,
            @Value("${app.frontend.base-url}") String frontendBaseUrl
    ) {
        this.emailService = emailService;
        this.pushService = pushService;
        this.smsService = smsService;
        this.smsProperties = smsProperties;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    // NOT @Async — called synchronously by NotificationOutboxDrainWorker

    public void notifyNewBooking(Booking booking) {
        String masterEmail = booking.getMaster().getUser().getEmail();
        UUID masterUserId = booking.getMaster().getUser().getId();
        String clientName = resolveClientName(booking);
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        emailService.sendNewBookingEmail(masterEmail, booking);
        pushService.sendToUser(
                masterUserId,
                "Нове бронювання",
                truncate("Клієнт " + clientName + " забронював " + serviceName),
                Map.of("type", "NEW_BOOKING", "bookingId", bookingId)
        );
    }

    public void notifyBookingStatusChanged(Booking booking) {
        // Guest (LINK) bookings have a null client (V89 chk_bookings_guest_fields) and no
        // account to notify by email/push. Repurposing PATCH /decline as provider-initiated
        // cancellation (Phase 24.2) makes "provider cancels a guest booking" a routine path —
        // without this guard, getClient().getEmail() below NPEs and the outbox entry dies
        // (mirrors the notifyReviewRequested guard).
        //
        // Phase 25.7: DECLINED is the one status a guest must still learn about — a declined
        // guest was previously told NOTHING. Sends an SMS carrying the provider's note instead
        // of the email/push a registered client gets. Every OTHER status (CONFIRMED/COMPLETED/
        // NOT_COMPLETED) stays the pre-25.7 clean no-op — do not widen this branch to a new
        // status without a dedicated guest-booking test (track 24.x fixed seven separate
        // client==null null-derefs in exactly this area).
        if (booking.getClient() == null) {
            if (booking.getStatus() == BookingStatus.DECLINED) {
                sendGuestDeclineSms(booking);
            } else {
                log.debug("Skipping STATUS_CHANGED notification for account-less guest booking {}", booking.getId());
            }
            return;
        }
        BookingStatus status = booking.getStatus();
        String clientEmail = booking.getClient().getEmail();
        UUID clientUserId = booking.getClient().getId();
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        switch (status) {
            case CONFIRMED -> {
                emailService.sendBookingConfirmedEmail(clientEmail, booking);
                pushService.sendToUser(
                        clientUserId,
                        "Бронювання підтверджено",
                        truncate("Ваше бронювання на " + serviceName + " підтверджено"),
                        Map.of("type", "BOOKING_CONFIRMED", "bookingId", bookingId)
                );
            }
            case DECLINED -> {
                emailService.sendBookingDeclinedEmail(clientEmail, booking);
                pushService.sendToUser(
                        clientUserId,
                        "Бронювання скасовано",
                        truncate("Ваше бронювання на " + serviceName + " скасовано"),
                        Map.of("type", "BOOKING_DECLINED", "bookingId", bookingId)
                );
            }
            default -> log.debug("No notification action for booking status [{}], bookingId={}", status, bookingId);
        }
    }

    /**
     * Notifies the provider (master / salon-admin) that the client moved the booking to a new
     * time (Phase 19.2; copy updated Phase 24.4 — the booking stays {@code CONFIRMED} at the new
     * time, there is no re-approval step). Targets the master's user — the same recipient as
     * {@link #notifyNewBooking(Booking)} — with «Бронювання перенесено» copy.
     */
    public void notifyBookingRescheduled(Booking booking) {
        String masterEmail = booking.getMaster().getUser().getEmail();
        UUID masterUserId = booking.getMaster().getUser().getId();
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        emailService.sendBookingRescheduledEmail(masterEmail, booking);
        pushService.sendToUser(
                masterUserId,
                "Бронювання перенесено",
                truncate("Клієнт переніс бронювання на " + serviceName),
                Map.of("type", "BOOKING_RESCHEDULED", "bookingId", bookingId)
        );
    }

    /**
     * Notifies the CLIENT (not the provider) that their completed visit can now be reviewed
     * (Phase 18.5). Builds a {@code /bookings/{id}/review} deep link from the configured frontend
     * base URL — scheme-guarded consistently with {@code InviteService.buildInviteLink} so an
     * unsafe {@code app.frontend.base-url} is rejected before the email is composed.
     */
    public void notifyReviewRequested(Booking booking) {
        // Guest (LINK) bookings have a null client (V89 chk_bookings_guest_fields) and no account
        // to leave a review with. A REVIEW_REQUESTED row should never be enqueued for one
        // (BookingService.completeBooking guards this), but the drain route must stay null-safe:
        // a clean no-op dispatch marks the entry SENT rather than NPEing it 3× into DEAD.
        if (booking.getClient() == null) {
            log.debug("Skipping REVIEW_REQUESTED for account-less guest booking {}", booking.getId());
            return;
        }
        String clientEmail = booking.getClient().getEmail();
        UUID clientUserId = booking.getClient().getId();
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        String reviewUrl = buildReviewUrl(bookingId);

        emailService.sendReviewRequestEmail(clientEmail, booking, reviewUrl);
        pushService.sendToUser(
                clientUserId,
                "Оцініть візит",
                truncate("Як пройшов ваш візит на " + serviceName + "? Залиште відгук"),
                Map.of("type", "REVIEW_REQUESTED", "bookingId", bookingId)
        );
    }

    public void notifyClientCancelled(Booking booking) {
        String masterEmail = booking.getMaster().getUser().getEmail();
        UUID masterUserId = booking.getMaster().getUser().getId();
        String clientName = resolveClientName(booking);
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        emailService.sendClientCancelledEmail(masterEmail, booking);
        pushService.sendToUser(
                masterUserId,
                "Клієнт скасував бронювання",
                truncate(clientName + " скасував бронювання на " + serviceName),
                Map.of("type", "CLIENT_CANCELLED", "bookingId", bookingId)
        );
    }

    /**
     * Forwards a pre-built invite acceptance URL to the email transport.
     *
     * <p>The caller is responsible for URL construction and validation
     * (scheme guard, encoding). See {@code InviteService.buildInviteLink}.
     *
     * @param email     recipient address
     * @param inviteUrl the fully-built invite acceptance URL — caller is
     *                  responsible for URL construction and validation
     * @param salonName salon display name shown in the email body
     */
    public void sendInviteEmail(String email, String inviteUrl, String salonName) {
        emailService.sendInviteEmail(email, inviteUrl, salonName);
    }

    /**
     * Builds the booking-scoped review deep link and guards the frontend base URL scheme
     * (mirrors {@code InviteService.buildInviteLink}). The booking id is a UUID string, so no
     * URL-encoding is required for the path segment.
     */
    private String buildReviewUrl(String bookingId) {
        if (!SchemeGuard.isAllowedScheme(frontendBaseUrl)) {
            throw new IllegalStateException(
                    "app.frontend.base-url must use HTTPS scheme for non-localhost origins, got: " + frontendBaseUrl);
        }
        return frontendBaseUrl + "/bookings/" + bookingId + "/review";
    }

    /**
     * Resolves the display name of the person who made this booking, for the master-facing
     * {@code NEW_BOOKING} / {@code CLIENT_CANCELLED} notifications.
     *
     * <p>A guest (LINK) booking has no registered account (V89 {@code chk_bookings_guest_fields}
     * — {@code client_id} is null), so {@code booking.getClient()} unconditionally would NPE the
     * outbox drain for every single guest booking (both on creation and on the guest's own
     * token-based cancellation via {@link com.beautica.booking.service.BookingCancellationService}
     * — {@code CLIENT_CANCELLED} is guest-only; there is no authenticated-client caller). Falls
     * back to the OTP-verified guest identity, mirroring {@code BookingDetailResponse.from}.
     * This is the master's own booking, so surfacing the guest's name is not a PII leak;
     * {@code guestPhone} is intentionally never read here.
     */
    private static String resolveClientName(Booking booking) {
        var client = booking.getClient();
        if (client != null) {
            return (safe(client.getFirstName()) + " " + safe(client.getLastName())).trim();
        }
        return (safe(booking.getGuestName()) + " " + safe(booking.getGuestSurname())).trim();
    }

    /**
     * Dispatches the guest-decline SMS (Phase 25.7). Failures are swallowed after logging the
     * exception class only — never the phone or message text (Anti-Bug §I) — mirroring
     * {@code BookingCancellationService.sendCancellationSms}: the booking is already committed
     * as DECLINED, so an SMS-provider failure must not fail outbox dispatch.
     */
    private void sendGuestDeclineSms(Booking booking) {
        String phone = booking.getGuestPhone();
        if (phone == null || phone.isBlank()) {
            log.warn("Guest DECLINED booking {} has no guestPhone — skipping decline SMS", booking.getId());
            return;
        }
        String text = buildGuestDeclineSms(booking);
        try {
            smsService.send(phone, text);
        } catch (RuntimeException e) {
            log.warn("Guest decline SMS failed: {}", e.getClass().getSimpleName());
        }
    }

    private String buildGuestDeclineSms(Booking booking) {
        OffsetDateTime kyiv = booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        return smsProperties.getSms().getDecline()
                .replace("{serviceName}", safe(booking.getMasterService().getServiceDefinition().getName()))
                .replace("{masterName}", masterName(booking.getMaster()))
                .replace("{date}", SMS_DATE_FMT.format(kyiv))
                .replace("{time}", SMS_TIME_FMT.format(kyiv))
                .replace("{comment}", truncateForSms(stripUrlsForSms(booking.getProviderComment())));
    }

    /**
     * Neutralizes link-shaped content in a provider note — SMS rendering path ONLY. The
     * persisted {@code provider_comment} column is never touched by this method (it takes and
     * returns a local {@code String}), and the decline EMAIL path never calls it at all — see
     * {@code NotificationServiceTest.should_notStripUrl_when_appBookingDeclinedEmailRendered},
     * which pins that structurally ({@code verify(booking, never()).getProviderComment()} on
     * that path).
     *
     * <p>This is token neutralization, not URL recognition — two prior regex-based attempts to
     * recognize "is this a URL" were each defeated by a real bypass (an allowlisted-TLD gap,
     * then a trailing-period edge case that made a possessive quantifier over-consume and never
     * backtrack). Trying to enumerate every shape a URL can take is an arms race that keeps
     * losing to the next shape. Instead: split the note into whitespace-delimited tokens and
     * drop, whole, any token that COULD plausibly be auto-linkified by iOS Data Detectors or
     * Android {@code Linkify} — see {@link #isLinkShapedToken}. The asymmetry is intentional and
     * accepted: over-stripping mangles one SMS; under-stripping delivers a phishing link, under
     * the Beautica brand name, to a real OTP-verified phone number.
     *
     * <p>Surviving tokens are rejoined with a single space — see {@link #TOKEN_DELIMITER} — so
     * any whitespace run in the original note (including the gap a dropped token leaves behind)
     * collapses to one space rather than reading with an odd gap.
     *
     * <p>This is one linear pass over whitespace-delimited tokens; neither {@link
     * #DOMAIN_DOT_LETTERS} nor {@link #BARE_IPV4} has a nested or backtracking quantifier, so
     * there is no ReDoS shape here — see
     * {@code NotificationServiceTest.should_stripAdversarialNote_withinBoundedTime} for a timed
     * regression guard on a 1000-char adversarial input (the DTO/DB ceiling on this field).
     */
    private static String stripUrlsForSms(String comment) {
        if (comment == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(comment.length());
        for (String token : TOKEN_DELIMITER.split(comment.strip())) {
            if (token.isEmpty() || isLinkShapedToken(token)) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(token);
        }
        return result.toString();
    }

    /**
     * A token is link-shaped — and therefore dropped whole by {@link #stripUrlsForSms} — if it:
     * contains a scheme delimiter ({@code ://}, which also catches an obfuscated scheme like
     * {@code hxxp://} — obfuscation does not change what a linkifier or a human eye reconstructs
     * it as); contains {@code @} (dropping the WHOLE token here, not just a domain half, is what
     * fixes the prior "dangling {@code admin@}" LOW — legitimate contact info in a note gets
     * fully removed rather than mangled into a fragment); matches {@link #DOMAIN_DOT_LETTERS}
     * (a dot-plus-letters domain-label shape, no TLD allowlist); or matches {@link #BARE_IPV4}
     * (a bare IPv4 literal, with or without a path).
     */
    private static boolean isLinkShapedToken(String token) {
        return token.contains("://")
                || token.contains("@")
                || DOMAIN_DOT_LETTERS.matcher(token).find()
                || BARE_IPV4.matcher(token).find();
    }

    /**
     * SMS-length guard for the decline note — see {@link #SMS_COMMENT_MAX_LENGTH}.
     *
     * <p>Backs the cut index off by one further when the character immediately before it is the
     * high half of a surrogate pair (e.g. an emoji) — otherwise a naive UTF-16-code-unit cut can
     * split the pair, leaving an unpaired high surrogate as the last character of the guest's
     * only notification about their booking (LOW finding — encoding corruption / possible
     * provider-side SMS rejection).
     */
    private static String truncateForSms(String comment) {
        if (comment == null || comment.isBlank()) {
            return "";
        }
        if (comment.length() <= SMS_COMMENT_MAX_LENGTH) {
            return comment;
        }
        int cut = SMS_COMMENT_MAX_LENGTH - 1;
        if (cut > 0 && Character.isHighSurrogate(comment.charAt(cut - 1))) {
            cut--;
        }
        return comment.substring(0, cut) + "…";
    }

    private static String masterName(Master master) {
        User u = master.getUser();
        String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
        String last = u.getLastName() == null ? "" : u.getLastName().trim();
        return (first + " " + last).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        if (value.length() <= PUSH_BODY_MAX_LENGTH) return value;
        return value.substring(0, PUSH_BODY_MAX_LENGTH - 1) + "…";
    }
}
