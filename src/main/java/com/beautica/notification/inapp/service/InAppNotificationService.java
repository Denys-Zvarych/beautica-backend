package com.beautica.notification.inapp.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.master.entity.Master;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.repository.InAppNotificationRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write path for the in-app notification feed (phase 333) — event → recipient fan-out, one row per
 * recipient per visit/booking event, written in the SAME transaction as the triggering domain
 * change.
 *
 * <p><b>Deliberately separate from {@code NotificationOutboxService}</b> (design decision 1 of the
 * phase doc): not called from it, not derived from its drain worker, and never touches it. Push /
 * email / SMS stay completely dark while this feed lights up — {@code
 * verifyNoInteractions(notificationOutboxService)} assertions elsewhere in the codebase keep their
 * exact meaning; this class shares none of that seam.
 *
 * <p><b>{@code Propagation.MANDATORY} everywhere</b> (design decision 2): every method here REQUIRES
 * an already-open transaction — the same one that is about to commit the booking/review/invite
 * change — so a feed row exists if and only if that change committed. Insert is {@code ON CONFLICT DO
 * NOTHING} ({@link InAppNotificationRepository#insertForRecipients}), so a re-entrant call (a
 * retried transition, a per-booking loop inside one whole-visit operation) collapses to one row.
 *
 * <p><b>Audit-fix cycle 1, finding 1 (HIGH perf) — takes the caller's already-loaded entity, never
 * reloads.</b> Every event method below now accepts the {@code Booking} (or, for a visit event, one
 * already-loaded item {@code Booking} of the visit) the caller already holds — {@code BookingService},
 * {@code StaffBookingService}, {@code BookingCancellationService}, {@code
 * AppointmentTransitionService} and {@code ReviewService} all load this exact graph for their OWN
 * provider-authority checks or persistence before they ever reach this class, so a second {@code
 * bookingRepository.findByIdWithFullGraph}/{@code findByAppointmentIdWithGraph} reload here was pure
 * waste — caught scaling {@code AppointmentItemCompleteIT}'s and {@code StaffBookingIT}'s pinned JDBC
 * statement-count gates. This SUPERSEDES the phase-333 design ("loads its own graph, by id, at every
 * call site, never trusts a caller's already-managed entity") — that decoupling bought call sites
 * nothing in practice (every one of them already had the graph loaded) and cost a full reload every
 * time. Reading {@code booking.getClient()}/{@code .getMaster()}/{@code .getSalon()} and then
 * {@code .getId()} off those associations is FREE even when the caller's query did not {@code JOIN
 * FETCH} them: each is a {@code @ManyToOne} keyed by a FK column already present on the owning row,
 * so Hibernate constructs the association's proxy directly from that column with zero extra
 * statements, and reading only the proxy's identifier never triggers initialisation (the same trick
 * already relied on throughout this codebase for {@code Salon.owner.getId()} and {@code
 * Appointment.client.getId()} — see {@code BookingRepository#findByIdWithFullGraph}'s own javadoc).
 * Only touching a NON-id property of an unfetched association would cost a statement; nothing here
 * ever does that. This is also why {@code AppointmentRepository#findClientIdById} (finding 2) is
 * gone entirely — the same free proxy-id read serves every visit event that used to call it.
 *
 * <p><b>Finding 3 (MEDIUM perf) — one INSERT per event, not one per recipient.</b> {@link
 * InAppNotificationRepository#insertForRecipients} writes every recipient of ONE event in a single
 * {@code INSERT ... SELECT ... WHERE id IN (:recipientIds) ON CONFLICT DO NOTHING} statement, so an
 * event with K recipients (master + owner + N admins) costs ONE round trip, not K.
 *
 * <p><b>No notes, no names, no copy</b> (rule 8 of the phase doc) — every write below carries only
 * ids and the {@link InAppNotificationType}; {@code clientComment}/{@code providerComment}/
 * cancellation notes are never read by this class at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InAppNotificationService {

    /** Max ids per bulk-insert statement — far below the JDBC 32,767 bind-parameter limit. */
    static final int BULK_ID_CHUNK_SIZE = 1000;

    private final InAppNotificationRepository repository;
    private final SalonRepository salonRepository;
    private final InAppRecipientResolver recipientResolver;

    /**
     * Rows 1/1w, 2, 3, 6, 7, 9, 10 of the matrix — every event keyed to ONE standalone booking (never
     * a visit).
     *
     * @param type        one of {@code BOOKING_CREATED}, {@code BOOKING_CANCELLED_BY_CLIENT},
     *                    {@code BOOKING_DECLINED}, {@code BOOKING_NOT_COMPLETED}, {@code
     *                    REVIEW_REQUESTED}, {@code BOOKING_CANCELLED_SALON_CLOSED}, {@code
     *                    BOOKING_CANCELLED_MASTER_REMOVED} — never {@code BOOKING_RESCHEDULED} (use
     *                    {@link #notifyRescheduled}), {@code REVIEW_RECEIVED} (use {@link
     *                    #notifyReviewReceived}) or {@code INVITE_ACCEPTED} (use {@link
     *                    #notifyInviteAccepted})
     * @param booking     the caller's own already-loaded, already-managed booking — becomes {@code
     *                    booking_id} on every row written, even when the booking also belongs to a
     *                    visit (a per-booking operation stays keyed to the booking, never the
     *                    appointment — see the class javadoc on {@code appointment_id} vs {@code
     *                    booking_id}). Never reloaded (finding 1) — {@code booking.getClient()}/
     *                    {@code .getMaster()}/{@code .getSalon()} are read directly.
     * @param actorUserId the user who performed the action — never itself a recipient (system jobs,
     *                    e.g. a guest link-cancel with no acting user, pass {@code null}, which is a
     *                    safe no-op against {@link Set#remove})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyBookingEvent(InAppNotificationType type, Booking booking, UUID actorUserId) {
        UUID clientId = clientIdOf(booking);
        boolean walkIn = booking.getBookingSource() == BookingSource.STAFF;
        Set<UUID> recipients = recipientsForSimpleEvent(type, booking.getMaster(), booking.getSalon(), clientId, walkIn);
        recipients.remove(actorUserId);
        if (recipients.isEmpty()) {
            return;
        }
        UUID bookingId = booking.getId();
        UUID salonId = salonIdOf(booking.getSalon());
        String dedupKey = dedupKey(type, bookingId);
        write(type, recipients, bookingId, null, salonId, null, dedupKey);
        log.debug("in-app notify type={} bookingId={} recipients={}", type, bookingId, recipients.size());
    }

    /**
     * The visit-level twin of {@link #notifyBookingEvent} — called ONCE PER APPOINTMENT by
     * whole-visit operations ({@code AppointmentTransitionService}), never once per chained booking
     * (design decision 7 — visit granularity).
     *
     * @param type            one of the 7 types listed on {@link #notifyBookingEvent}
     * @param appointmentId   the visit; becomes {@code appointment_id} on every row written
     * @param representative  ANY one already-loaded item {@code Booking} of the visit (every item of
     *                        a visit shares one master/salon by construction — see {@code
     *                        AppointmentService#doCreateAppointment}'s single-master chain), typically
     *                        {@code items.get(0)} of the caller's own already-loaded item list. Its
     *                        {@code client}/{@code master}/{@code salon} associations are read the
     *                        same free, no-reload way {@link #notifyBookingEvent} reads them — see
     *                        this class's javadoc for why that is free even when the caller's query
     *                        did not {@code JOIN FETCH} {@code client}.
     * @param actorUserId     the user who performed the action — never itself a recipient
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyVisitEvent(
            InAppNotificationType type, UUID appointmentId, Booking representative, UUID actorUserId) {
        UUID clientId = clientIdOf(representative);
        boolean walkIn = representative.getBookingSource() == BookingSource.STAFF;
        Set<UUID> recipients = recipientsForSimpleEvent(
                type, representative.getMaster(), representative.getSalon(), clientId, walkIn);
        recipients.remove(actorUserId);
        if (recipients.isEmpty()) {
            return;
        }
        UUID salonId = salonIdOf(representative.getSalon());
        String dedupKey = dedupKey(type, appointmentId);
        write(type, recipients, null, appointmentId, salonId, null, dedupKey);
        log.debug("in-app notify type={} appointmentId={} recipients={}", type, appointmentId, recipients.size());
    }

    /**
     * Row 3 (decline) and rows 6/7/9/10 (client-only) share one recipient shape switch; row 1/1w and
     * row 2 share {@link InAppRecipientResolver#providerSet}/{@link
     * InAppRecipientResolver#masterOnly}. Kept as ONE switch, called by both {@link
     * #notifyBookingEvent} and {@link #notifyVisitEvent}, so the matrix has a single implementation
     * regardless of which granularity triggered it.
     */
    private Set<UUID> recipientsForSimpleEvent(
            InAppNotificationType type, Master master, Salon salon, UUID clientId, boolean walkIn) {
        return switch (type) {
            // Row 1 / 1w — walk-ins notify ONLY the performing master (owner/admins excluded even
            // when they are the salon's staff, per the architect's "board already shows it" ruling);
            // client/APP/LINK creates notify the whole provider set.
            case BOOKING_CREATED -> walkIn
                    ? recipientResolver.masterOnly(master)
                    : recipientResolver.providerSet(master, salon);
            // Row 2 — client-initiated cancel; the client is always the actor, so it is excluded by
            // the uniform actor-removal step the two public callers perform, never by this switch.
            case BOOKING_CANCELLED_BY_CLIENT -> recipientResolver.providerSet(master, salon);
            // Row 3 — the client (never for a walk-in/guest, since clientId is null there by
            // construction) PLUS the performing master unconditionally ("your day changed" rule 6);
            // owner/admins who did not act get nothing.
            case BOOKING_DECLINED -> declineRecipients(master, clientId);
            // Rows 6, 7, 9, 10 — client only. A STAFF/guest booking's clientId is null, which makes
            // "walk-ins don't apply" and "guests get nothing" fall out for free rather than needing a
            // second guard here.
            case BOOKING_NOT_COMPLETED, REVIEW_REQUESTED,
                 BOOKING_CANCELLED_SALON_CLOSED, BOOKING_CANCELLED_MASTER_REMOVED -> clientOnly(clientId);
            default -> throw new IllegalArgumentException(
                    "notifyBookingEvent/notifyVisitEvent does not support " + type);
        };
    }

    private Set<UUID> declineRecipients(Master master, UUID clientId) {
        Set<UUID> ids = recipientResolver.masterOnly(master);
        if (clientId != null) {
            ids.add(clientId);
        }
        return ids;
    }

    private static Set<UUID> clientOnly(UUID clientId) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (clientId != null) {
            ids.add(clientId);
        }
        return ids;
    }

    /**
     * Row 4 — the one event whose recipient set depends on WHO initiated it, so it gets its own
     * entry point rather than a branch inside {@link #notifyBookingEvent}/{@link #notifyVisitEvent}.
     *
     * <p>The performing master ALWAYS gets an item (rule 6, "your day changed"), unless they are the
     * actor. For a non-walk-in booking, the OTHER side of the transaction also gets one: the client
     * when a provider moved it, or the owner + admins when the client moved it — an independent
     * master's own reschedule notification is already covered by the "always" master row, so no
     * second branch is needed for that case. Walk-ins ({@code source == STAFF}) restrict to the
     * performing-master cell only, per the matrix's walk-in rule — client/owner/admin never apply.
     *
     * @param booking             the already-loaded, already-managed booking that was rescheduled
     * @param initiatedByProvider mirrors the outbox's own {@code enqueueBookingRescheduled} flag
     * @param newStart            the new start instant — becomes the dedup-key suffix (epoch
     *                            seconds) so a SECOND reschedule to a DIFFERENT time is not silently
     *                            swallowed by the first reschedule's dedup row
     * @param actorUserId         the user who moved the booking — never itself a recipient
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyRescheduled(
            Booking booking, boolean initiatedByProvider, Instant newStart, UUID actorUserId) {
        writeReschedule(
                booking.getMaster(), booking.getSalon(), clientIdOf(booking),
                booking.getBookingSource() == BookingSource.STAFF,
                booking.getId(), null, initiatedByProvider, newStart, actorUserId);
    }

    /**
     * The visit-level twin of {@link #notifyRescheduled} — {@code AppointmentTransitionService
     * #rescheduleAppointment}'s whole-visit reschedule, once per appointment.
     *
     * @param appointmentId       the visit that was rescheduled; becomes {@code appointment_id}
     * @param representative      any one already-loaded item {@code Booking} of the visit — see
     *                            {@link #notifyVisitEvent}'s javadoc for why this is free to read
     * @param initiatedByProvider see {@link #notifyRescheduled}
     * @param newStart            see {@link #notifyRescheduled}
     * @param actorUserId         see {@link #notifyRescheduled}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyRescheduledVisit(
            UUID appointmentId, Booking representative, boolean initiatedByProvider,
            Instant newStart, UUID actorUserId) {
        writeReschedule(
                representative.getMaster(), representative.getSalon(), clientIdOf(representative),
                representative.getBookingSource() == BookingSource.STAFF,
                null, appointmentId, initiatedByProvider, newStart, actorUserId);
    }

    private void writeReschedule(
            Master master, Salon salon, UUID clientId, boolean walkIn,
            UUID bookingId, UUID appointmentId, boolean initiatedByProvider, Instant newStart, UUID actorUserId) {
        Set<UUID> recipients = recipientResolver.masterOnly(master); // rule 6 — always, unless actor
        if (!walkIn) {
            if (initiatedByProvider) {
                if (clientId != null) {
                    recipients.add(clientId);
                }
            } else {
                recipients.addAll(recipientResolver.ownerAndAdmins(salon));
            }
        }
        recipients.remove(actorUserId);
        if (recipients.isEmpty()) {
            return;
        }
        UUID salonId = salonIdOf(salon);
        UUID keyId = appointmentId != null ? appointmentId : bookingId;
        String dedupKey = dedupKey(InAppNotificationType.BOOKING_RESCHEDULED, keyId, newStart.getEpochSecond());
        write(InAppNotificationType.BOOKING_RESCHEDULED, recipients, bookingId, appointmentId, salonId, null, dedupKey);
        log.debug("in-app notify type=BOOKING_RESCHEDULED bookingId={} appointmentId={} recipients={}",
                bookingId, appointmentId, recipients.size());
    }

    /**
     * Row 8 — a client→provider review just landed. Recipients are the rated master + the business
     * owner (never admins — the architect's noise-limiting decision). The client (the review's
     * author) is always the actor and is excluded by the same uniform removal every other method
     * uses, even though {@code ownerAndMasterOnly} would never have included them anyway.
     *
     * @param reviewId    the review's own id — carried for parity with the phase doc's signature and
     *                    for logging; NOT part of the dedup key (1 review per booking, by the locked
     *                    "review unit = the booking" rule, so the booking id alone is already unique)
     * @param booking     the already-loaded, already-managed reviewed booking
     * @param actorUserId the reviewing client — never itself a recipient
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyReviewReceived(UUID reviewId, Booking booking, UUID actorUserId) {
        Set<UUID> recipients = recipientResolver.ownerAndMasterOnly(booking.getMaster(), booking.getSalon());
        recipients.remove(actorUserId);
        if (recipients.isEmpty()) {
            return;
        }
        UUID bookingId = booking.getId();
        UUID salonId = salonIdOf(booking.getSalon());
        String dedupKey = dedupKey(InAppNotificationType.REVIEW_RECEIVED, bookingId);
        write(InAppNotificationType.REVIEW_RECEIVED, recipients, bookingId, null, salonId, null, dedupKey);
        log.debug("in-app notify type=REVIEW_RECEIVED reviewId={} bookingId={} recipients={}",
                reviewId, bookingId, recipients.size());
    }

    /**
     * Row 5 — a new teammate ({@code SALON_MASTER} or {@code SALON_ADMIN}) just accepted an invite.
     * Recipients are the owner + every OTHER active admin; the new teammate is the actor and is
     * excluded — including from their own freshly-created {@code SALON_ADMIN} row, which {@link
     * InAppRecipientResolver#ownerAndAdmins} would otherwise include (their {@code users} row is
     * already persisted with {@code role = SALON_ADMIN} by the time this is called).
     *
     * <p>Unlike every other method on this class, this one still resolves its {@code Salon} by id
     * (out of audit-fix cycle 1's scope — {@code InviteService#acceptInvite} only conditionally loads
     * a {@code Salon} several statements earlier, for a check whose scope does not overlap this
     * class's, so folding the two together is a separate, not-yet-audited change).
     *
     * @param salonId       the salon the invite was bound to
     * @param newMemberUserId the accepting user's own new id — {@code subject_user_id} on every row,
     *                      and the actor excluded from the recipient set
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyInviteAccepted(UUID salonId, UUID newMemberUserId) {
        salonRepository.findById(salonId).ifPresent(salon -> {
            Set<UUID> recipients = recipientResolver.ownerAndAdmins(salon);
            recipients.remove(newMemberUserId);
            if (recipients.isEmpty()) {
                return;
            }
            String dedupKey = InAppNotificationType.INVITE_ACCEPTED.name() + ":" + salonId + ":" + newMemberUserId;
            write(InAppNotificationType.INVITE_ACCEPTED, recipients, null, null, salonId, newMemberUserId, dedupKey);
            log.debug("in-app notify type=INVITE_ACCEPTED salonId={} newMemberUserId={} recipients={}",
                    salonId, newMemberUserId, recipients.size());
        });
    }

    /**
     * Bulk counterpart of {@link #notifyBookingEvent} for the salon-closure / master-removal /
     * master-self-delete cascades — rows 9/10, client-only. ONE statement regardless of how many
     * visits {@code bookingIds} spans; see {@link InAppNotificationRepository#insertClientOnlyBulk}'s
     * javadoc for why a per-visit loop calling {@link #notifyBookingEvent} once per representative id
     * is a regression, not a simplification, on this call site.
     *
     * @param type        {@code BOOKING_CANCELLED_SALON_CLOSED} or {@code
     *                    BOOKING_CANCELLED_MASTER_REMOVED}
     * @param bookingIds  the cascade's per-visit representative booking ids — MUST be non-empty
     *                    (an empty {@code IN (...)} is a native-query syntax error, so callers guard
     *                    this the same way they already guard the outbox enqueue loop)
     * @param actorUserId the user who triggered the closure/removal — excluded defensively, though
     *                    structurally never a recipient of this row shape
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyClientOnlyBulk(InAppNotificationType type, Collection<UUID> bookingIds, UUID actorUserId) {
        if (bookingIds.isEmpty()) {
            return;
        }
        int written = 0;
        for (List<UUID> chunk : chunked(bookingIds)) {
            written += repository.insertClientOnlyBulk(type.name(), chunk, actorUserId);
        }
        log.debug("in-app notify(bulk) type={} visits={} rowsWritten={}", type, bookingIds.size(), written);
    }

    /**
     * Bulk counterpart of {@link #notifyBookingEvent} for the CLIENT self-delete cascade — row 2,
     * provider set. ONE statement regardless of how many standalone-booking "visits" {@code
     * bookingIds} spans. See {@link InAppNotificationRepository#insertProviderSetBulk}'s javadoc.
     *
     * @param type        {@code BOOKING_CANCELLED_BY_CLIENT}
     * @param bookingIds  the cascade's per-visit representative booking ids — MUST be non-empty
     * @param actorUserId the deleting client's id — structurally never a recipient (the provider set
     *                    never includes the client), excluded defensively for uniformity
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyProviderSetBulk(InAppNotificationType type, Collection<UUID> bookingIds, UUID actorUserId) {
        if (bookingIds.isEmpty()) {
            return;
        }
        int written = 0;
        for (List<UUID> chunk : chunked(bookingIds)) {
            written += repository.insertProviderSetBulk(type.name(), chunk, actorUserId);
        }
        log.debug("in-app notify(bulk) type={} visits={} rowsWritten={}", type, bookingIds.size(), written);
    }

    /**
     * Splits {@code ids} into chunks of at most {@link #BULK_ID_CHUNK_SIZE} so a huge salon / master
     * cascade can never exceed the JDBC 32,767 bind-parameter ceiling of the {@code IN (:bookingIds)}
     * bulk inserts. At or below one chunk (the overwhelmingly common case) this yields exactly one
     * chunk — the statement count is unchanged.
     */
    static List<List<UUID>> chunked(Collection<UUID> ids) {
        List<UUID> all = ids instanceof List<UUID> list ? list : new ArrayList<>(ids);
        List<List<UUID>> chunks = new ArrayList<>((all.size() + BULK_ID_CHUNK_SIZE - 1) / BULK_ID_CHUNK_SIZE);
        for (int from = 0; from < all.size(); from += BULK_ID_CHUNK_SIZE) {
            chunks.add(all.subList(from, Math.min(from + BULK_ID_CHUNK_SIZE, all.size())));
        }
        return chunks;
    }

    /**
     * Audit-fix cycle 1, finding 3 — the single write point every per-event method above funnels
     * through: ONE {@link InAppNotificationRepository#insertForRecipients} statement for the WHOLE
     * {@code recipients} set, never a per-recipient loop. {@code recipients} is never empty here —
     * every caller already returned early on {@link Set#isEmpty()}.
     */
    private void write(
            InAppNotificationType type, Set<UUID> recipients, UUID bookingId, UUID appointmentId,
            UUID salonId, UUID subjectUserId, String dedupKey) {
        repository.insertForRecipients(
                type.name(), recipients, bookingId, appointmentId, salonId, subjectUserId, dedupKey);
    }

    /**
     * Free even when the caller's query never {@code JOIN FETCH}ed {@code client} — see this class's
     * javadoc for why reading only the FK-backed proxy's identifier costs no statement.
     */
    private static UUID clientIdOf(Booking booking) {
        return booking.getClient() != null ? booking.getClient().getId() : null;
    }

    private static UUID salonIdOf(Salon salon) {
        return salon != null ? salon.getId() : null;
    }

    private static String dedupKey(InAppNotificationType type, UUID id) {
        return type.name() + ":" + id;
    }

    private static String dedupKey(InAppNotificationType type, UUID id, long suffix) {
        return type.name() + ":" + id + ":" + suffix;
    }
}
