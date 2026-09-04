package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.TimeZones;
import com.beautica.common.util.Placeholders;
import com.beautica.common.util.SchemeGuard;
import com.beautica.common.util.UkrainianPlurals;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.notification.sms.SmsService;
import com.beautica.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
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

    /**
     * Notifies the PROVIDER of a new booking — or of a whole new multi-service visit.
     *
     * <p>Takes a {@link BookingVisit}, not a bare {@code Booking}, because exactly ONE
     * {@code NEW_BOOKING} outbox row is enqueued per visit (keyed to the first chained booking):
     * without the sibling rows this described only the lead service. A single-service booking
     * arrives as {@link BookingVisit#single(Booking)} and every value below collapses to the
     * scalar this method always read — the legacy copy is unchanged, character for character.
     */
    public void notifyNewBooking(BookingVisit visit) {
        Booking booking = visit.lead();
        User masterUser = providerRecipient(booking, "NEW_BOOKING");
        if (masterUser == null) {
            return;
        }
        String masterEmail = masterUser.getEmail();
        UUID masterUserId = masterUser.getId();
        String clientName = resolveClientName(booking);
        String bookingId = booking.getId().toString();

        emailService.sendNewBookingEmail(masterEmail, visit);
        pushService.sendToUser(
                masterUserId,
                "Нове бронювання",
                truncate("Клієнт " + clientName + " забронював " + bookedSubject(visit)),
                Map.of("type", "NEW_BOOKING", "bookingId", bookingId)
        );
    }

    /**
     * What the push body says was booked: the service NAME for a single-service booking (unchanged
     * pre-visit wording), or a numeral phrase — «3 послуги», «5 послуг» — for a multi-service visit.
     * A visit's full service list is not pushed: the push body is capped at
     * {@link #PUSH_BODY_MAX_LENGTH} and up to ten names would be truncated mid-name.
     */
    private static String bookedSubject(BookingVisit visit) {
        return visit.isMultiService()
                ? UkrainianPlurals.servicesPhrase(visit.size())
                : safe(visit.leadServiceName());
    }

    /**
     * Whether this {@code DECLINED} outbox row describes the WHOLE visit or ONE service line of it.
     * There are two decline routes and only the appointment HEADER tells them apart:
     *
     * <ul>
     *   <li>{@code AppointmentTransitionService#declineAppointmentItem} — one service line is
     *       declined, its siblings stay {@code CONFIRMED}, the header stays {@code CONFIRMED}, and
     *       {@code STATUS_CHANGED} is enqueued against that ONE booking. Naming the whole visit here
     *       would tell the client that services they still have are cancelled.</li>
     *   <li>{@code AppointmentTransitionService#declineAppointment} — the header AND every item move
     *       to {@code DECLINED}, and {@code persistAndNotify} enqueues ONE {@code STATUS_CHANGED}
     *       against {@code items.get(0)}. This is the path that was silently telling a client only
     *       their first service was cancelled while the rest of the visit was gone too.</li>
     * </ul>
     *
     * <p>The {@code isMultiService()} half is what makes a null header safe: a legacy booking with
     * no appointment, or a caller that could not resolve one, falls to the per-item wording — the
     * conservative default, identical to the pre-visit copy.
     *
     * <p>It does NOT, however, insulate the per-item route from the whole-visit branch in every
     * case, and an earlier version of this note wrongly claimed it did. Declining the LAST still-
     * CONFIRMED line collapses the header to {@code DECLINED}; by then every sibling is
     * {@code DECLINED} too, so the resolver's status filter retains them all and this predicate is
     * true on what was a PER-ITEM action. The copy that results is nevertheless correct — the visit
     * genuinely is fully cancelled at that point, and every service it names is one this client
     * booked and has now lost — so the branch is left as is deliberately.
     */
    private static boolean isWholeVisitDecline(BookingVisit visit) {
        return visit.appointmentStatus() == BookingStatus.DECLINED && visit.isMultiService();
    }

    /** @see #notifyNewBooking(BookingVisit) for why this takes a visit rather than a booking. */
    public void notifyBookingStatusChanged(BookingVisit visit) {
        Booking booking = visit.lead();
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
        //
        // Phase 22.1 (security): the SMS half is gated on the SOURCE, not on the null client.
        // V137 makes client_id IS NULL reachable for a STAFF walk-in — a phone TYPED IN by a salon
        // employee about a third party, verified by nothing. A LINK guest, by contrast, proved
        // their number by answering an OTP before the booking existed. Keying the SMS on
        // `client == null` would treat the two identically and turn every staff-entered digit
        // string into a destination the platform sends Beautica-branded, provider-authored text
        // to with no consent step anywhere: a typo texts a stranger, and a deliberate entry makes
        // us an on-demand SMS relay (unsolicited-SMS amplification, sender-reputation damage,
        // Turbosms cost). The gate is "SMS iff LINK" and both halves are pinned by
        // NotificationServiceTest (#should_notSendDeclineSms_when_staffWalkInBookingDeclined /
        // #should_stillSendDeclineSms_when_linkGuestBookingDeclined).
        //
        // The null-client early return itself stays, and must: it is what keeps getClient()
        // .getEmail() below from NPEing. Narrowing this outer `if` to `client == null && LINK`
        // would drop a STAFF walk-in straight into the registered-client email/push path.
        //
        // Conjunct ORDER is deliberate — status first, source second. Both orders are equally
        // correct at runtime, but source-first short-circuits before reading the status on every
        // non-LINK row, which leaves the status stub unexercised in the CONFIRMED/COMPLETED/
        // NOT_COMPLETED no-op regression test and trips Mockito's UnnecessaryStubbingException.
        // Status-first also reads better: DECLINED is still the primary discriminator (25.7), and
        // the source gate narrows it.
        if (booking.getClient() == null) {
            if (booking.getStatus() == BookingStatus.DECLINED
                    && booking.getBookingSource() == BookingSource.LINK) {
                sendLinkGuestDeclineSms(booking);
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
                emailService.sendBookingConfirmedEmail(clientEmail, visit);
                pushService.sendToUser(
                        clientUserId,
                        "Бронювання підтверджено",
                        truncate("Ваше бронювання на " + bookedSubject(visit) + " підтверджено"),
                        Map.of("type", "BOOKING_CONFIRMED", "bookingId", bookingId)
                );
            }
            // A decline arrives by TWO different routes and they need OPPOSITE copy — see
            // isWholeVisitDecline. Getting this wrong in either direction misinforms the client:
            // naming the whole visit for a per-item decline cancels services that are still on;
            // naming only the lead for a whole-visit decline (the pre-fix behaviour) leaves the
            // client believing the rest of the visit still stands, and they turn up for it.
            case DECLINED -> {
                if (isWholeVisitDecline(visit)) {
                    emailService.sendVisitDeclinedEmail(clientEmail, visit);
                    pushService.sendToUser(
                            clientUserId,
                            "Бронювання скасовано",
                            truncate("Ваше бронювання на " + bookedSubject(visit) + " скасовано"),
                            Map.of("type", "BOOKING_DECLINED", "bookingId", bookingId)
                    );
                } else {
                    emailService.sendBookingDeclinedEmail(clientEmail, booking);
                    pushService.sendToUser(
                            clientUserId,
                            "Бронювання скасовано",
                            truncate("Ваше бронювання на " + serviceName + " скасовано"),
                            Map.of("type", "BOOKING_DECLINED", "bookingId", bookingId)
                    );
                }
            }
            default -> log.debug("No notification action for booking status [{}], bookingId={}", status, bookingId);
        }
    }

    /**
     * Notifies the provider (master / salon-admin) that the client moved the booking to a new
     * time (Phase 19.2; copy updated Phase 24.4 — the booking stays {@code CONFIRMED} at the new
     * time, there is no re-approval step). Targets the master's user — the same recipient as
     * {@link #notifyNewBooking(BookingVisit)} — with «Бронювання перенесено» copy.
     */
    public void notifyBookingRescheduled(Booking booking) {
        User masterUser = providerRecipient(booking, "BOOKING_RESCHEDULED");
        if (masterUser == null) {
            return;
        }
        String masterEmail = masterUser.getEmail();
        UUID masterUserId = masterUser.getId();
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
     * Notifies the CLIENT that the PROVIDER moved their booking to a new time (Phase 27.3 —
     * REVERSES the previously-locked "reschedule is client-only" decision; this is the
     * client-facing twin of {@link #notifyBookingRescheduled(Booking)}, which stays unchanged for
     * the client-initiated case).
     *
     * <p>A guest (LINK) booking has no client account ({@code booking.getClient() == null}) — no
     * email/push channel exists to reach. Skips cleanly, mirroring {@link
     * #notifyReviewRequested(Booking)}'s guest guard; a provider CAN reach this path for a guest
     * booking ({@code BookingService.rescheduleBooking}'s provider branch does not require a
     * client to exist), so this guard is load-bearing, not defensive-only.
     */
    public void notifyBookingRescheduledClient(Booking booking) {
        if (booking.getClient() == null) {
            log.debug("Skipping client-facing BOOKING_RESCHEDULED for account-less guest booking {}", booking.getId());
            return;
        }
        String clientEmail = booking.getClient().getEmail();
        UUID clientUserId = booking.getClient().getId();
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        emailService.sendBookingRescheduledClientEmail(clientEmail, booking);
        pushService.sendToUser(
                clientUserId,
                "Бронювання перенесено",
                truncate("Ваш майстер переніс бронювання на " + serviceName),
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

    /**
     * Notifies the PROVIDER (master / salon-admin) — never the client — that an elapsed {@code
     * CONFIRMED} booking is still awaiting closure (Phase 29.5/29.6). This is a work-queue nudge,
     * not a status change: dispatched purely from the {@code CLOSURE_REMINDER} outbox row a
     * separate, already-committed native claim wrote — this method (and everything it calls)
     * must never read or write {@code booking.status}, never call {@link
     * #notifyReviewRequested(Booking)}, and never call {@code
     * BookingService#computeProviderCanReviewClient} — see {@code ClosureReminderArchitectureTest}.
     *
     * <p>A guest (LINK) booking still has a real master to nudge (guests only lack a client
     * account — see {@link #notifyNewBooking(BookingVisit)}), so unlike the client-facing notify
     * methods above, there is no {@code booking.getClient() == null} guard to skip: the recipient
     * here never depends on the client existing. {@link #resolveClientName(Booking)} already
     * handles the guest case for the copy that names the client in the reminder.
     */
    public void notifyClosureReminder(Booking booking) {
        User masterUser = providerRecipient(booking, "CLOSURE_REMINDER");
        if (masterUser == null) {
            return;
        }
        String masterEmail = masterUser.getEmail();
        UUID masterUserId = masterUser.getId();
        String clientName = resolveClientName(booking);
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        emailService.sendClosureReminderEmail(masterEmail, booking, buildBookingUrl(bookingId));
        pushService.sendToUser(
                masterUserId,
                "Позначте візит",
                truncate("Візит з " + clientName + " на " + serviceName + " завершився — закрийте його"),
                Map.of("type", "CLOSURE_REMINDER", "bookingId", bookingId)
        );
    }

    /**
     * Notifies the CLIENT (or guest) that the salon they had a future booking with was deleted by
     * its owner, and the booking was auto-declined (Phase 269/293 — {@code SALON_CLOSED}).
     *
     * <p>Takes a {@link BookingVisit}, not a bare {@code Booking}, because exactly ONE
     * {@code SALON_CLOSED} outbox row is enqueued per VISIT (D12) — {@code visit} may therefore
     * describe several declined services at once, and the copy names all of them via
     * {@link #bookedSubject(BookingVisit)}, never just the representative.
     *
     * <p><b>Per-audience delivery (D8).</b> A registered client (an account exists —
     * {@code booking.getClient() != null}) always has an email on file (schema-guaranteed,
     * {@code users.email NOT NULL}) — email + push. A guest ({@code LINK}) visit has no account
     * and no email; its {@code guestPhone} is OTP-verified, so it gets an SMS instead
     * ({@link #sendSalonClosedGuestSms(BookingVisit)}, mirroring
     * {@link #sendLinkGuestDeclineSms(Booking)}). A STAFF walk-in's phone was typed by an
     * employee, not proven by the recipient (Phase 22.1) — it is NOT an eligible SMS destination
     * and is left a silent no-op, same gate as {@link #notifyBookingStatusChanged(BookingVisit)}.
     *
     * <p><b>No booking note is ever read here</b> (D10) — {@link BookingVisit} exposes no
     * accessor for {@code clientComment}/{@code clientCancellationNote}/{@code providerComment} by
     * construction, and this method does not read any of them off {@link BookingVisit#lead()}
     * either, unlike the decline path.
     */
    public void notifySalonClosed(BookingVisit visit) {
        Booking booking = visit.lead();
        if (booking.getClient() == null) {
            if (booking.getBookingSource() == BookingSource.LINK) {
                sendSalonClosedGuestSms(visit);
            } else {
                log.debug("Skipping SALON_CLOSED notification for account-less non-LINK booking {}",
                        booking.getId());
            }
            return;
        }
        String clientEmail = booking.getClient().getEmail();
        UUID clientUserId = booking.getClient().getId();
        String bookingId = booking.getId().toString();

        emailService.sendSalonClosedEmail(clientEmail, visit);
        pushService.sendToUser(
                clientUserId,
                "Салон закрито",
                truncate("Салон закрився, і ваше бронювання на " + bookedSubject(visit) + " скасовано"),
                Map.of("type", "SALON_CLOSED", "bookingId", bookingId)
        );
    }

    /**
     * Notifies the CLIENT (or guest) that the master they had a future booking with was removed
     * from the salon, and the booking was auto-declined (Phase 298 — {@code MASTER_REMOVED}).
     * Deliberately NOT a reuse of {@link #notifySalonClosed(BookingVisit)} — the salon did not
     * close, only the master left it, and copy claiming the salon closed would be false in
     * billable SMS traffic.
     *
     * <p>Same per-visit / per-audience shape as {@link #notifySalonClosed(BookingVisit)}: takes a
     * {@link BookingVisit} (one {@code MASTER_REMOVED} row is enqueued per VISIT, D12), a
     * registered client gets email + push, a guest ({@code LINK}) visit gets SMS via {@link
     * #sendMasterRemovedGuestSms(BookingVisit)}, and a STAFF walk-in's unverified phone is left a
     * silent no-op — identical gate to the salon-closure path.
     *
     * <p>Copy states the master is no longer working at this salon and the appointment was
     * cancelled — it must never imply the salon closed and must never name a replacement master
     * (no reassignment machinery exists). No booking note is ever read here (same D10 posture as
     * {@link #notifySalonClosed(BookingVisit)} — {@link BookingVisit} exposes no note accessor).
     */
    public void notifyMasterRemoved(BookingVisit visit) {
        Booking booking = visit.lead();
        if (booking.getClient() == null) {
            if (booking.getBookingSource() == BookingSource.LINK) {
                sendMasterRemovedGuestSms(visit);
            } else {
                log.debug("Skipping MASTER_REMOVED notification for account-less non-LINK booking {}",
                        booking.getId());
            }
            return;
        }
        String clientEmail = booking.getClient().getEmail();
        UUID clientUserId = booking.getClient().getId();
        String bookingId = booking.getId().toString();

        emailService.sendMasterRemovedEmail(clientEmail, visit);
        pushService.sendToUser(
                clientUserId,
                "Майстра більше немає в салоні",
                truncate("Майстер, який мав прийняти вас на " + bookedSubject(visit) + ", більше не "
                        + "працює в цьому салоні, і ваше бронювання скасовано"),
                Map.of("type", "MASTER_REMOVED", "bookingId", bookingId)
        );
    }

    public void notifyClientCancelled(Booking booking) {
        User masterUser = providerRecipient(booking, "CLIENT_CANCELLED");
        if (masterUser == null) {
            return;
        }
        String masterEmail = masterUser.getEmail();
        UUID masterUserId = masterUser.getId();
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
     * Builds the booking-detail deep link for the closure-reminder email's CTA (Phase 29.5) —
     * {@code {FRONTEND_BASE_URL}/bookings/{id}}, mirroring {@link #buildReviewUrl(String)}'s
     * scheme guard. Mobile phase 230 handles the app-side routing for this link.
     */
    private String buildBookingUrl(String bookingId) {
        if (!SchemeGuard.isAllowedScheme(frontendBaseUrl)) {
            throw new IllegalStateException(
                    "app.frontend.base-url must use HTTPS scheme for non-localhost origins, got: " + frontendBaseUrl);
        }
        return frontendBaseUrl + "/bookings/" + bookingId;
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
     * Dispatches the decline SMS for a {@code LINK} guest booking (Phase 25.7). Failures are
     * swallowed after logging the exception class only — never the phone or message text
     * (Anti-Bug §I) — mirroring {@code BookingCancellationService.sendCancellationSms}: the booking
     * is already committed as DECLINED, so an SMS-provider failure must not fail outbox dispatch.
     *
     * <p>Named for {@code LINK} rather than "guest" deliberately (Phase 22.1): a STAFF walk-in is
     * also account-less, but its phone was typed by an employee about a third party rather than
     * proven by the recipient's own OTP, so it is NOT an eligible destination. Only the LINK branch
     * of {@link #notifyBookingStatusChanged(BookingVisit)} may call this — do not re-generalise
     * this method to "any booking with a guestPhone".
     */
    private void sendLinkGuestDeclineSms(Booking booking) {
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

    /**
     * Phase 25.9: the provider's decline note is optional (for all roles). {@code decline} and
     * {@code declineReason} are two separate config templates — see {@link BookingSmsProperties}
     * — precisely so a null/blank note never leaves a dangling "Причина: " label with nothing
     * after it: the reason clause is only substituted and appended when a non-blank, truncated
     * comment actually survives {@link #stripUrlsForSms}/{@link #truncateForSms}.
     */
    private String buildGuestDeclineSms(Booking booking) {
        OffsetDateTime kyiv = booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        // Placeholders.format, not chained String.replace: a provider-controlled service name
        // containing a literal "{date}" would otherwise be expanded by the NEXT replace in the
        // chain, letting the name steer the layout of a message the guest reads as platform copy.
        String base = Placeholders.format(smsProperties.getSms().getDecline(), Map.of(
                "serviceName", safe(booking.getMasterService().getServiceDefinition().getName()),
                "masterName", masterName(booking.getMaster()),
                "date", SMS_DATE_FMT.format(kyiv),
                "time", SMS_TIME_FMT.format(kyiv)));
        String comment = truncateForSms(stripUrlsForSms(booking.getProviderComment()));
        if (comment.isBlank()) {
            return base;
        }
        return base + Placeholders.format(
                smsProperties.getSms().getDeclineReason(), Map.of("comment", comment));
    }

    /**
     * Dispatches the salon-closure SMS for a {@code LINK} guest visit (Phase 269/293). Mirrors
     * {@link #sendLinkGuestDeclineSms(Booking)} exactly — same guard, same swallow-and-log-class-
     * only failure handling (Anti-Bug §I: never log the phone or message text) — the one
     * difference being the text names the whole VISIT via {@link #bookedSubject(BookingVisit)}
     * rather than one booking's service, and carries no provider note (D10: {@link BookingVisit}
     * exposes no note accessor to read in the first place).
     *
     * <p>Only the LINK branch of {@link #notifySalonClosed(BookingVisit)} may call this — same
     * "not the STAFF walk-in" restriction {@link #sendLinkGuestDeclineSms(Booking)} documents.
     */
    private void sendSalonClosedGuestSms(BookingVisit visit) {
        Booking lead = visit.lead();
        String phone = lead.getGuestPhone();
        if (phone == null || phone.isBlank()) {
            log.warn("Guest SALON_CLOSED visit (lead booking {}) has no guestPhone — skipping SMS",
                    lead.getId());
            return;
        }
        String text = buildSalonClosedSms(visit);
        try {
            smsService.send(phone, text);
        } catch (RuntimeException e) {
            log.warn("Salon-closed guest SMS failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Renders the salon-closure SMS body. Never reads any booking note — {@link BookingVisit}
     * exposes no accessor for one (D10) — so there is no reason/comment clause to append, unlike
     * {@link #buildGuestDeclineSms(Booking)}.
     */
    private String buildSalonClosedSms(BookingVisit visit) {
        OffsetDateTime kyiv = visit.startsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        return Placeholders.format(smsProperties.getSms().getSalonClosed(), Map.of(
                "subject", bookedSubject(visit),
                "date", SMS_DATE_FMT.format(kyiv),
                "time", SMS_TIME_FMT.format(kyiv)));
    }

    /**
     * Dispatches the master-removal SMS for a {@code LINK} guest visit (Phase 298). Mirrors
     * {@link #sendSalonClosedGuestSms(BookingVisit)} exactly — same guard, same swallow-and-log-
     * class-only failure handling (Anti-Bug §I: never log the phone or message text), same
     * no-note posture (D10) — only the copy template differs.
     *
     * <p>Only the LINK branch of {@link #notifyMasterRemoved(BookingVisit)} may call this — same
     * "not the STAFF walk-in" restriction {@link #sendLinkGuestDeclineSms(Booking)} documents.
     */
    private void sendMasterRemovedGuestSms(BookingVisit visit) {
        Booking lead = visit.lead();
        String phone = lead.getGuestPhone();
        if (phone == null || phone.isBlank()) {
            log.warn("Guest MASTER_REMOVED visit (lead booking {}) has no guestPhone — skipping SMS",
                    lead.getId());
            return;
        }
        String text = buildMasterRemovedSms(visit);
        try {
            smsService.send(phone, text);
        } catch (RuntimeException e) {
            log.warn("Master-removed guest SMS failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Renders the master-removal SMS body. Never reads any booking note — {@link BookingVisit}
     * exposes no accessor for one (D10) — mirrors {@link #buildSalonClosedSms(BookingVisit)}
     * exactly, with a copy template that names the master leaving, not the salon closing.
     */
    private String buildMasterRemovedSms(BookingVisit visit) {
        OffsetDateTime kyiv = visit.startsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        return Placeholders.format(smsProperties.getSms().getMasterRemoved(), Map.of(
                "subject", bookedSubject(visit),
                "date", SMS_DATE_FMT.format(kyiv),
                "time", SMS_TIME_FMT.format(kyiv)));
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

    /**
     * The PROVIDER-side recipient of a booking notification, or {@code null} when there is nobody
     * left to notify.
     *
     * <p>V157 / phase 294 D1 made {@code masters.user_id} nullable: when a salon is deleted the
     * staff {@code users} row is hard-deleted and the historical {@code masters} row survives as a
     * name-only stub with no mailbox, no device token and no account. Every provider-facing
     * notification below therefore starts here and returns early on a detached master instead of
     * NPEing deep inside the mail/push transport.
     *
     * <p>Unreachable as of phase 294 — nothing detaches a master yet (D6). It exists so phase 295's
     * delete cannot turn a notification into a 500.
     *
     * <p>The log line carries identifiers only: no email, no name, no phone (Anti-Bug §I).
     */
    @Nullable
    private static User providerRecipient(Booking booking, String notificationType) {
        User masterUser = booking.getMaster().getUser();
        if (masterUser == null) {
            log.info("Skipping {} for booking {} — master {} is detached (staff account deleted)",
                    notificationType, booking.getId(), booking.getMaster().getId());
        }
        return masterUser;
    }

    // V157 / phase 294 D3: displayFirstName()/displayLastName(), never getUser().getFirstName().
    // A detached master (staff account hard-deleted, historical stub kept) has no user row, so the
    // old two-line walk NPEs; the accessors fall back to the name snapshot taken at detach time.
    // Still null-safe on either half — users.first_name / users.last_name are both nullable.
    private static String masterName(Master master) {
        String firstName = master.displayFirstName();
        String lastName = master.displayLastName();
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
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
