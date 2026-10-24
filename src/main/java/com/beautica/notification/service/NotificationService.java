package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.service.BookingSmsDispatcher;
import com.beautica.common.util.SchemeGuard;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Thin notification-side facade that dispatches email and (guest-only) SMS notifications. Push is
 * NOT sent from here (phase 339): every Android push is fanned out from the in-app feed write path
 * via the {@code INAPP_PUSH} outbox event ({@code InAppPushDispatcher}), so each event has exactly
 * one push source.
 *
 * <p>Methods are synchronous — invoked by {@code NotificationOutboxDrainWorker}, which already
 * runs after the originating transaction has committed (Phase 3 of the drain: {@code
 * @Transactional(propagation = NOT_SUPPORTED)}), so SMS dispatch here needs no additional
 * afterCommit synchronization of its own (unlike {@code BookingCancellationService}, which fires
 * its guest SMS from inside the original request's transaction). The guest SMS is handed to
 * {@link BookingSmsDispatcher} (phase 390), so a provider stall never holds the drain batch. URL composition (and the HTTPS
 * scheme guard) for invite links lives in {@code InviteService.buildInviteLink}, not in this
 * class.
 */
@Slf4j
@Service
public class NotificationService {

    private final EmailNotificationService emailService;
    private final BookingSmsDispatcher bookingSmsDispatcher;
    private final GuestProviderCancellationSmsText guestCancellationSmsText;
    private final String frontendBaseUrl;

    // Explicit constructor — @RequiredArgsConstructor cannot bind the @Value frontend base URL.
    public NotificationService(
            EmailNotificationService emailService,
            BookingSmsDispatcher bookingSmsDispatcher,
            GuestProviderCancellationSmsText guestCancellationSmsText,
            @Value("${app.frontend.base-url}") String frontendBaseUrl
    ) {
        this.emailService = emailService;
        this.bookingSmsDispatcher = bookingSmsDispatcher;
        this.guestCancellationSmsText = guestCancellationSmsText;
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
        emailService.sendNewBookingEmail(masterUser.getEmail(), visit);
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
        // guest was previously told NOTHING. Sends an SMS instead of the email/push a registered
        // client gets. Phase 390: the SMS is the fixed GuestProviderCancellationSmsText copy — it
        // no longer carries the provider's note, service or master name (no provider free text). Every OTHER status (CONFIRMED/COMPLETED/
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
                sendLinkGuestProviderCancellationSms(booking, "DECLINED",
                        guestCancellationSmsText.forDecline(booking, declinedStart(visit)));
            } else {
                log.debug("Skipping STATUS_CHANGED notification for account-less guest booking {}", booking.getId());
            }
            return;
        }
        BookingStatus status = booking.getStatus();
        String clientEmail = booking.getClient().getEmail();
        String bookingId = booking.getId().toString();

        switch (status) {
            case CONFIRMED -> emailService.sendBookingConfirmedEmail(clientEmail, visit);
            // A decline arrives by TWO different routes and they need OPPOSITE copy — see
            // isWholeVisitDecline. Getting this wrong in either direction misinforms the client:
            // naming the whole visit for a per-item decline cancels services that are still on;
            // naming only the lead for a whole-visit decline (the pre-fix behaviour) leaves the
            // client believing the rest of the visit still stands, and they turn up for it.
            case DECLINED -> {
                if (isWholeVisitDecline(visit)) {
                    emailService.sendVisitDeclinedEmail(clientEmail, visit);
                } else {
                    emailService.sendBookingDeclinedEmail(clientEmail, booking);
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
        emailService.sendBookingRescheduledEmail(masterUser.getEmail(), booking);
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
        emailService.sendBookingRescheduledClientEmail(booking.getClient().getEmail(), booking);
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
        String reviewUrl = buildReviewUrl(booking.getId().toString());

        emailService.sendReviewRequestEmail(clientEmail, booking, reviewUrl);
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
     * here never depends on the client existing.
     */
    public void notifyClosureReminder(Booking booking) {
        User masterUser = providerRecipient(booking, "CLOSURE_REMINDER");
        if (masterUser == null) {
            return;
        }
        String masterEmail = masterUser.getEmail();
        String bookingId = booking.getId().toString();

        emailService.sendClosureReminderEmail(masterEmail, booking, buildBookingUrl(bookingId));
    }

    /**
     * Notifies the CLIENT (or guest) that the salon they had a future booking with was deleted by
     * its owner, and the booking was auto-declined (Phase 269/293 — {@code SALON_CLOSED}).
     *
     * <p>Takes a {@link BookingVisit}, not a bare {@code Booking}, because exactly ONE
     * {@code SALON_CLOSED} outbox row is enqueued per VISIT (D12) — {@code visit} may therefore
     * describe several declined services at once; the email names all of them, and the guest SMS
     * (one per visit) names the visit start only.
     *
     * <p><b>Per-audience delivery (D8).</b> A registered client (an account exists —
     * {@code booking.getClient() != null}) always has an email on file (schema-guaranteed,
     * {@code users.email NOT NULL}) — email + push. A guest ({@code LINK}) visit has no account
     * and no email; its {@code guestPhone} is OTP-verified, so it gets an SMS instead
     * (phase 390 — the fixed {@link GuestProviderCancellationSmsText#forSalonClosed} copy via
     * {@link #sendLinkGuestProviderCancellationSms}). A STAFF walk-in's phone was typed by an
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
                sendLinkGuestProviderCancellationSms(booking, "SALON_CLOSED",
                        guestCancellationSmsText.forSalonClosed(visit.startsAt()));
            } else {
                log.debug("Skipping SALON_CLOSED notification for account-less non-LINK booking {}",
                        booking.getId());
            }
            return;
        }
        emailService.sendSalonClosedEmail(booking.getClient().getEmail(), visit);
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
     * registered client gets email + push, a guest ({@code LINK}) visit gets the phase 390 SMS
     * ({@link GuestProviderCancellationSmsText#forMasterRemoved}, rebook link to the salon page
     * only), and a STAFF walk-in's unverified phone is left a
     * silent no-op — identical gate to the salon-closure path.
     *
     * <p>The email states the master is no longer working at this salon and the appointment was
     * cancelled — it must never imply the salon closed and must never name a replacement master
     * (no reassignment machinery exists). The guest SMS says only that the appointment was
     * cancelled, which is true for both cascades. No booking note is ever read here (same D10 posture as
     * {@link #notifySalonClosed(BookingVisit)} — {@link BookingVisit} exposes no note accessor).
     */
    public void notifyMasterRemoved(BookingVisit visit) {
        Booking booking = visit.lead();
        if (booking.getClient() == null) {
            if (booking.getBookingSource() == BookingSource.LINK) {
                sendLinkGuestProviderCancellationSms(booking, "MASTER_REMOVED",
                        guestCancellationSmsText.forMasterRemoved(booking, visit.startsAt()));
            } else {
                log.debug("Skipping MASTER_REMOVED notification for account-less non-LINK booking {}",
                        booking.getId());
            }
            return;
        }
        emailService.sendMasterRemovedEmail(booking.getClient().getEmail(), visit);
    }

    public void notifyClientCancelled(Booking booking) {
        User masterUser = providerRecipient(booking, "CLIENT_CANCELLED");
        if (masterUser == null) {
            return;
        }
        emailService.sendClientCancelledEmail(masterUser.getEmail(), booking);
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
                    "app.frontend.base-url must use HTTPS scheme for non-localhost origins");
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
                    "app.frontend.base-url must use HTTPS scheme for non-localhost origins");
        }
        return frontendBaseUrl + "/bookings/" + bookingId;
    }

    /**
     * Phase 390 — queues the provider-cancellation SMS for a {@code LINK} guest. One call per outbox
     * row, and every caller's row is per VISIT (whole-visit decline, salon closure, master removal)
     * or per declined service line, so a guest gets one SMS per provider action. The text comes
     * from {@link GuestProviderCancellationSmsText} and carries no provider-authored content.
     *
     * <p>Named for {@code LINK} rather than "guest" deliberately (Phase 22.1): a STAFF walk-in is
     * also account-less, but its phone was typed by an employee about a third party rather than
     * proven by the recipient's own OTP, so it is NOT an eligible destination. Only the LINK
     * branches above may call this — do not re-generalise it to "any booking with a guestPhone".
     *
     * <p>{@link BookingSmsDispatcher} owns the send-failure swallow-and-log (kind + exception class only, never
     * the phone or the text — Anti-Bug §I) and the {@code app.booking.sms.enabled} gate (it holds
     * the gated {@code SmsService} bean). This runs on the outbox drain thread, which only ever sees
     * committed rows, so a rolled-back cancellation can never reach it.
     */
    private void sendLinkGuestProviderCancellationSms(Booking lead, String eventType, String text) {
        String phone = lead.getGuestPhone();
        if (phone == null || phone.isBlank()) {
            log.warn("Guest {} (booking {}) has no guestPhone — skipping SMS", eventType, lead.getId());
            return;
        }
        // dispatchOrThrow, never dispatch: this runs on the outbox drain, which can retry. A
        // saturated smsSendExecutor must reach the drain's backpressure branch (row back to PENDING,
        // no attempt counted) instead of being swallowed and the row marked SENT with the SMS lost.
        // Safe to retry because the SMS is the ONLY side effect of every LINK-guest branch.
        bookingSmsDispatcher.dispatchOrThrow(BookingSmsDispatcher.Kind.GUEST_PROVIDER_CANCELLATION, phone, text);
    }

    /**
     * Whether dispatching an outbox row of {@code type} for {@code lead} would hand an SMS to
     * {@link BookingSmsDispatcher#dispatchOrThrow} — i.e. whether it reaches
     * {@link #sendLinkGuestProviderCancellationSms} through the {@code STATUS_CHANGED}/DECLINED,
     * {@code SALON_CLOSED} or {@code MASTER_REMOVED} LINK-guest branch, with a phone to send to.
     *
     * <p>Read-only, no side effects. Lets {@code NotificationOutboxDrainWorker} defer the batch's
     * remaining SMS rows once the SMS pool has pushed back, without submitting each one to be refused.
     * Must mirror those three branches exactly; parity is pinned in {@code NotificationServiceTest}.
     */
    public boolean routesToLinkGuestSms(OutboxEventType type, Booking lead) {
        if (lead.getClient() != null || lead.getBookingSource() != BookingSource.LINK
                || lead.getGuestPhone() == null || lead.getGuestPhone().isBlank()) {
            return false;
        }
        return switch (type) {
            case STATUS_CHANGED -> lead.getStatus() == BookingStatus.DECLINED;
            case SALON_CLOSED, MASTER_REMOVED -> true;
            default -> false;
        };
    }

    /**
     * The appointment time the guest is told was cancelled: the whole visit's start for a
     * whole-visit decline, otherwise the declined line's own start (siblings of a per-item decline
     * stay booked, so naming the visit start could point at a service that is still on).
     */
    private static OffsetDateTime declinedStart(BookingVisit visit) {
        return isWholeVisitDecline(visit) ? visit.startsAt() : visit.lead().getStartsAt();
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
}
