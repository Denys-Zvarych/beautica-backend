package com.beautica.notification.service;

import com.beautica.common.util.SchemeGuard;
import com.beautica.notification.crypto.OutboxPayloadCipher;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes notification outbox entries atomically within the caller's transaction.
 *
 * <p><strong>Outbox pattern contract:</strong>
 * <ul>
 *   <li>Every public method uses {@code Propagation.MANDATORY} — the caller MUST hold
 *       an active transaction. If no transaction is present,
 *       {@link org.springframework.transaction.IllegalTransactionStateException} is thrown
 *       immediately, preventing a dangling outbox row from being created outside the
 *       domain event's transaction boundary.
 *   <li>These methods perform one INSERT each. They do NOT call
 *       {@link NotificationService} — notification delivery is the responsibility of
 *       the separate drain scheduler, which runs outside any booking transaction.
 *   <li>Do NOT annotate with {@code @Async} — async dispatch decouples the write from
 *       the transaction boundary, risking notifications for rolled-back domain events.
 * </ul>
 *
 * <p><strong>Security note ({@code enqueueInvite}):</strong> The raw invite URL is
 * never stored in plaintext; the AES-GCM ciphertext is persisted under
 * {@code inviteUrlSealed} (see {@link OutboxPayloadCipher}). The drain worker decrypts
 * the sealed value at send time (Phase 5.6).
 */
@Service
@RequiredArgsConstructor
public class NotificationOutboxService {

    /** Phase 431 — payload key of the acting user's id (performing-master rows). */
    public static final String ACTOR_USER_ID = "actorUserId";
    /** Phase 431 — payload key of a provider reschedule's NEW start, epoch seconds. */
    public static final String STARTS_AT = "startsAt";

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_SALON_NAME_LENGTH = 255;
    private static final int MAX_INVITE_URL_LENGTH = 2048;

    private final NotificationOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final OutboxPayloadCipher cipher;

    /**
     * Enqueues a {@code NEW_BOOKING} notification entry.
     *
     * @param bookingId the UUID of the newly created booking
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueNewBooking(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.NEW_BOOKING, bookingId, null);
    }

    /**
     * Enqueues a {@code STATUS_CHANGED} notification entry.
     *
     * @param bookingId the UUID of the booking whose status changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueStatusChanged(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.STATUS_CHANGED, bookingId, null);
    }

    /**
     * Enqueues a {@code REVIEW_REQUESTED} notification entry (Phase 18.2).
     *
     * <p>Fired AFTER the booking's {@code CONFIRMED → COMPLETED} transition, inside the same
     * completion transaction (MANDATORY propagation — rolls back with the booking if the
     * completion fails). Carries only {@code aggregateId = bookingId}; the drain worker
     * re-hydrates the full {@code Booking} graph at send time, so no client PII is duplicated
     * into the outbox payload.
     *
     * <p>The recipient is the <strong>client</strong> (not the provider) — recipient resolution
     * lives in {@code NotificationService.notifyReviewRequested}, not here.
     *
     * @param bookingId the UUID of the booking that was just completed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueReviewRequested(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.REVIEW_REQUESTED, bookingId, null);
    }

    /**
     * Enqueues a {@code BOOKING_RESCHEDULED} notification entry (Phase 19.2), defaulting to
     * CLIENT-initiated (Phase 27.3 — see the 2-arg overload). Convenience overload kept so any
     * existing caller that is unconditionally client-side need not pass the flag.
     *
     * @param bookingId the UUID of the rescheduled booking
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueBookingRescheduled(UUID bookingId) {
        enqueueBookingRescheduled(bookingId, false);
    }

    /**
     * Enqueues a {@code BOOKING_RESCHEDULED} notification entry (Phase 19.2), recording WHO moved
     * the booking (Phase 27.3 — reschedule widened to providers).
     *
     * <p>The drained event notifies whichever party did NOT initiate the move: a client-initiated
     * reschedule notifies the provider (master / salon-admin), unchanged pre-27.3 behaviour; a
     * provider-initiated reschedule notifies the client instead — see
     * {@code NotificationOutboxDrainWorker}'s {@code BOOKING_RESCHEDULED} case and
     * {@code NotificationService#notifyBookingRescheduledClient}.
     *
     * <p>The payload is a small, unencrypted JSON object ({@code {"initiatedBy":"PROVIDER"}} or
     * {@code {"initiatedBy":"CLIENT"}}) — the same plain-payload mechanism {@code save}'s third
     * argument already supports (only {@code INVITE} needs {@link OutboxPayloadCipher} sealing;
     * this carries no secret). An absent/null payload on an older PENDING row (written before this
     * overload existed) is read by the drain worker as CLIENT-initiated — see
     * {@code NotificationOutboxDrainWorker#resolveRescheduleInitiator} — for backward compatibility.
     *
     * @param bookingId          the UUID of the rescheduled booking
     * @param initiatedByProvider {@code true} when a provider (salon owner / assigned salon admin
     *                             / independent master) moved the booking, {@code false} when the
     *                             client did
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueBookingRescheduled(UUID bookingId, boolean initiatedByProvider) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        String payload = writeJson(Map.of("initiatedBy", initiatedByProvider ? "PROVIDER" : "CLIENT"));
        save(OutboxEventType.BOOKING_RESCHEDULED, bookingId, payload);
    }

    /**
     * Phase 431 — {@link #enqueueBookingRescheduled(UUID, boolean)} plus the NEW start in epoch seconds
     * ({@code {"initiatedBy":"PROVIDER","startsAt":"<epoch>"}}). The drain sends the guest reschedule
     * SMS only while the booking is still {@code CONFIRMED} AND still starts at that instant, so a burst
     * of moves collapses to one SMS (for the last move) and a booking cancelled before the drain gets
     * none. The {@code initiatedBy} field is written exactly as before. A row without {@code startsAt}
     * (written before phase 431) is sent only while the booking is {@code CONFIRMED}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueBookingRescheduled(UUID bookingId, boolean initiatedByProvider, Instant newStartsAt) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(newStartsAt, "newStartsAt must not be null");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("initiatedBy", initiatedByProvider ? "PROVIDER" : "CLIENT");
        fields.put(STARTS_AT, Long.toString(newStartsAt.getEpochSecond()));
        save(OutboxEventType.BOOKING_RESCHEDULED, bookingId, writeJson(fields));
    }

    /**
     * Phase 431 — enqueues the PERFORMING master's "the salon moved your booking" e-mail as its OWN
     * row ({@link OutboxEventType#MASTER_BOOKING_RESCHEDULED}), beside the client/guest row of a
     * provider reschedule. Nothing is enqueued when the master has no account (detached) or is the
     * actor — no self-notification. See {@link #enqueueMasterBookingDeclined} for why it is its own row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueMasterBookingRescheduled(UUID bookingId, UUID masterUserId, UUID actorUserId) {
        enqueueMasterRow(OutboxEventType.MASTER_BOOKING_RESCHEDULED, bookingId, masterUserId, actorUserId);
    }

    /**
     * Phase 431 — enqueues the PERFORMING master's "the salon cancelled your booking / visit" e-mail as
     * its OWN row ({@link OutboxEventType#MASTER_BOOKING_DECLINED}), beside the client/guest
     * {@code STATUS_CHANGED} row of a provider decline (the visit's first item for a whole-visit
     * decline — one e-mail per visit).
     *
     * <p>Separate rows, same transaction: each side has its own retry state, so a failing master mail
     * can neither block nor DEAD the client/guest notification, and an SMS back-pressure re-queue of
     * the guest row never repeats the master mail. Skipped when the master has no account or is the
     * actor. The payload is {@code {"actorUserId":"<uuid>"}} — a user id only, no name, phone or note.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueMasterBookingDeclined(UUID bookingId, UUID masterUserId, UUID actorUserId) {
        enqueueMasterRow(OutboxEventType.MASTER_BOOKING_DECLINED, bookingId, masterUserId, actorUserId);
    }

    private void enqueueMasterRow(OutboxEventType type, UUID bookingId, UUID masterUserId, UUID actorUserId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        if (masterUserId == null || masterUserId.equals(actorUserId)) {
            return;
        }
        save(type, bookingId, writeJson(Map.of(ACTOR_USER_ID, actorUserId.toString())));
    }

    /**
     * Enqueues a {@code CLIENT_CANCELLED} notification entry.
     *
     * <p><strong>Phase 5 wire-up note:</strong> Wire this in
     * {@code BookingService.cancelBooking} when role-specific notification templates that
     * distinguish client cancellations from other status changes are implemented.
     * Until then, {@code enqueueStatusChanged} covers all cancellation transitions.
     *
     * @param bookingId the UUID of the booking the client cancelled
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueClientCancelled(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.CLIENT_CANCELLED, bookingId, null);
    }

    /**
     * Enqueues an {@code INVITE} notification entry.
     *
     * <p><strong>Security:</strong> {@code inviteUrl} is encrypted with
     * {@link OutboxPayloadCipher} before persistence; plaintext never reaches the DB.
     * The sealed ciphertext is stored under the {@code inviteUrlSealed} key. The drain
     * worker decrypts the sealed value at send time (Phase 5.6).
     *
     * <p><strong>{@code inviteUrl} validation:</strong>
     * <ul>
     *   <li>Must not be {@code null} or blank — throws {@link IllegalArgumentException}.</li>
     *   <li>Length must be {@code <= 2048} characters — throws {@link IllegalArgumentException}.</li>
     *   <li>Must start with {@code https://} or {@code http://localhost} (mirroring the
     *       guard in {@code InviteService.buildInviteLink}) — throws
     *       {@link IllegalArgumentException}.</li>
     * </ul>
     * Validation runs <strong>before</strong> {@link OutboxPayloadCipher#seal(String)}
     * is invoked, so malformed input never reaches the cipher.
     *
     * @param inviteTokenId the UUID of the {@code InviteToken} row (used as aggregateId
     *                      by the drain worker to look up the row at send time)
     * @param toEmail       the recipient's e-mail address
     * @param inviteUrl     the fully-formed invite URL embedding the raw one-time token;
     *                      will be encrypted before persistence — never logged
     * @param salonName     the salon name displayed in the invite e-mail
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueInvite(UUID inviteTokenId, String toEmail, String inviteUrl, String salonName) {
        Objects.requireNonNull(inviteTokenId, "inviteTokenId must not be null");
        if (toEmail == null || toEmail.isBlank()) {
            throw new IllegalArgumentException("toEmail must not be blank");
        }
        if (toEmail.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("toEmail exceeds maximum length of " + MAX_EMAIL_LENGTH);
        }
        if (salonName == null || salonName.isBlank()) {
            throw new IllegalArgumentException("salonName must not be blank");
        }
        if (salonName.length() > MAX_SALON_NAME_LENGTH) {
            throw new IllegalArgumentException("salonName exceeds maximum length of " + MAX_SALON_NAME_LENGTH);
        }
        if (inviteUrl == null || inviteUrl.isBlank()) {
            throw new IllegalArgumentException("inviteUrl must not be blank");
        }
        if (inviteUrl.length() > MAX_INVITE_URL_LENGTH) {
            throw new IllegalArgumentException("inviteUrl exceeds maximum length of " + MAX_INVITE_URL_LENGTH);
        }
        if (!SchemeGuard.isAllowedScheme(inviteUrl)) {
            throw new IllegalArgumentException(
                    "inviteUrl must use https:// scheme or http://localhost for non-prod");
        }
        String sealedInviteUrl = cipher.seal(inviteUrl);
        String payload = writeJson(Map.of(
                "email", toEmail,
                "salonName", salonName,
                "inviteUrlSealed", sealedInviteUrl));
        save(OutboxEventType.INVITE, inviteTokenId, payload);
    }

    /**
     * Enqueues a {@code CLOSURE_REMINDER} notification entry (Phase 29.5).
     *
     * <p>Writes exactly ONE row into {@code notification_outbox} and performs no other write —
     * in particular, unlike {@link #enqueueReviewRequested(UUID)} (called from inside {@code
     * BookingService#completeBooking}, i.e. from a transaction that has just mutated {@code
     * booking.status}), this method is called from NO such place. It is invoked only by {@code
     * com.beautica.booking.closure.ClosureReminderClaimService}, itself called only for booking
     * ids an atomic native claim statement already {@code RETURNING}'d — this method never reads
     * or writes {@code bookings.status}. See {@code ClosureReminderArchitectureTest}, the
     * mechanical guard for that invariant.
     *
     * <p>Recipient resolution (the PROVIDER, never the client) lives in {@code
     * NotificationService#notifyClosureReminder}, not here — this method only writes the outbox
     * row; the drain worker re-hydrates the full {@link com.beautica.booking.entity.Booking}
     * graph at send time, so no client PII is duplicated into the outbox payload.
     *
     * @param bookingId the UUID of the booking still awaiting provider closure
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueClosureReminder(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.CLOSURE_REMINDER, bookingId, null);
    }

    /**
     * Enqueues a {@code SALON_CLOSED} notification entry (Phase 269/293) — one row per affected
     * VISIT, never per booking (D12). {@code bookingId} MUST be the visit's REPRESENTATIVE
     * booking: the lowest {@code starts_at} among the visit's declined rows, tied on {@code id} —
     * see {@code BookingService#declineFutureConfirmedBookingsForSalonClosure}, the sole caller,
     * for how that representative is chosen. Choosing it deterministically means a retried/re-run
     * cascade always picks the same aggregate id.
     *
     * <p>Same shape as {@link #enqueueClientCancelled(UUID)} — no payload, {@code aggregateId} is
     * the representative booking's id, and the drain worker re-hydrates the full booking (and, for
     * a multi-service visit, its siblings) at send time so no client PII or booking note is
     * duplicated into the outbox row.
     *
     * @param bookingId the representative booking of the closed visit
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueSalonClosed(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.SALON_CLOSED, bookingId, null);
    }

    /**
     * Enqueues a {@code MASTER_REMOVED} notification entry (Phase 298) — one row per affected
     * VISIT, never per booking, mirroring {@link #enqueueSalonClosed(UUID)}'s exact D12 contract.
     * {@code bookingId} MUST be the visit's REPRESENTATIVE booking: the lowest {@code starts_at}
     * among the visit's declined rows, tied on {@code id} — see {@code BookingService
     * #declineFutureConfirmedBookingsForMasterRemoval}, the sole caller, for how that
     * representative is chosen.
     *
     * <p>Same shape as {@link #enqueueSalonClosed(UUID)} — no payload, {@code aggregateId} is the
     * representative booking's id, and the drain worker re-hydrates the full booking (and, for a
     * multi-service visit, its siblings) at send time so no client PII or booking note is
     * duplicated into the outbox row.
     *
     * @param bookingId the representative booking of the visit whose master was removed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueMasterRemoved(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.MASTER_REMOVED, bookingId, null);
    }

    // --- private helpers ---

    private void save(OutboxEventType eventType, UUID aggregateId, String payload) {
        outboxRepository.save(NotificationOutboxEntry.builder()
                .eventType(eventType)
                .aggregateId(aggregateId)
                .payload(payload)
                .build()); // status defaults to PENDING, attempts to 0 via @Builder.Default
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize outbox payload", e);
        }
    }
}
