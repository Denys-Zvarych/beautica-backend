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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * <p><strong>Security note ({@code enqueueStaffInviteSms}):</strong> the invited phone and the raw invite URL are
 * never stored in plaintext; their AES-GCM ciphertexts are persisted under {@code phoneSealed} /
 * {@code inviteUrlSealed} (see {@link OutboxPayloadCipher}). The drain worker opens them at send time.
 */
@Service
@RequiredArgsConstructor
public class NotificationOutboxService {

    /** Phase 431 — payload key of the acting user's id (performing-master rows). */
    public static final String ACTOR_USER_ID = "actorUserId";
    /** Phase 431 — payload key of a provider reschedule's NEW start, epoch seconds. */
    public static final String STARTS_AT = "startsAt";

    /** Phase 436 — {@code CLIENT_CANCELLED} payload key: the request's cancelled booking ids, comma-separated. */
    public static final String CANCELLED_BOOKING_IDS = "cancelledBookingIds";

    /** Phase 491 — {@code REQUEST_DECIDED} payload keys (ids and the flag only, no free text). */
    public static final String REQUEST_KIND = "kind";
    public static final String REQUEST_ID = "requestId";
    public static final String REQUEST_APPROVED = "approved";
    public static final String RECIPIENT_USER_ID = "recipientUserId";

    /** Phase 494 — {@code BOOKING_REMINDER} payload keys (ids, the kind and the claim instant only, NTF-03). */
    public static final String REMINDER_BOOKING_ID = "bookingId";
    public static final String REMINDER_KIND = "kind";
    /** Epoch seconds of the sweep tick that claimed the booking, read from the job's injected {@code Clock}. */
    public static final String REMINDER_CLAIMED_AT = "claimedAt";

    /** B520 — {@code INVITE} payload keys: the sealed phone, the salon name and the sealed join link. */
    public static final String INVITE_PHONE_SEALED = "phoneSealed";
    public static final String INVITE_SALON_NAME = "salonName";
    public static final String INVITE_URL_SEALED = "inviteUrlSealed";

    private static final Pattern UA_E164 = Pattern.compile("^\\+380\\d{9}$");
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
     * Phase 496 (NTF-49) — the registered client's cancel receipt as its OWN row
     * ({@link OutboxEventType#CLIENT_CANCEL_RECEIPT}), beside the master's {@code CLIENT_CANCELLED} row: one retry
     * state per recipient, so a failed receipt never re-sends the master's e-mail (audit-fix cycle 1). Called solely by
     * the authenticated client self-cancel, one row per cancelled booking (NTF-D11, D4) — never by a guest cancel or
     * the account-deletion cascade. No payload (ids only).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueClientCancelReceipt(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.CLIENT_CANCEL_RECEIPT, bookingId, null);
    }

    /**
     * Phase 496 (NTF-49) — the registered client's «ви перенесли запис» receipt as its OWN row
     * ({@link OutboxEventType#CLIENT_RESCHEDULE_RECEIPT}), beside the client-initiated {@code BOOKING_RESCHEDULED}
     * row that mails the master (audit-fix cycle 1, same reason as {@link #enqueueClientCancelReceipt}). Called solely
     * by a client-initiated reschedule. No payload (ids only).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueClientRescheduleReceipt(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        save(OutboxEventType.CLIENT_RESCHEDULE_RECEIPT, bookingId, null);
    }

    /**
     * Phase 436 — a {@code CLIENT_CANCELLED} row for a guest's cancel by link that names exactly the services
     * cancelled in that request: ONE row per request, keyed to {@code leadBookingId}, whose payload carries the
     * request's booking ids ({@value #CANCELLED_BOOKING_IDS}, comma-separated) so the drain worker lists only those
     * services in the provider e-mail. Booking ids only — no note, no PII.
     *
     * @param leadBookingId       the first booking cancelled in the request (visit order)
     * @param cancelledBookingIds every booking cancelled in the request, {@code leadBookingId} included
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueClientCancelled(UUID leadBookingId, List<UUID> cancelledBookingIds) {
        Objects.requireNonNull(leadBookingId, "leadBookingId must not be null");
        Objects.requireNonNull(cancelledBookingIds, "cancelledBookingIds must not be null");
        String ids = cancelledBookingIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        save(OutboxEventType.CLIENT_CANCELLED, leadBookingId, writeJson(Map.of(CANCELLED_BOOKING_IDS, ids)));
    }

    /**
     * B520 (NTF-59) — enqueues the staff invite SMS as an {@code INVITE} entry, in the caller's (invite-insert)
     * transaction: the invite row and its SMS commit or roll back together, and the SMS is sent by the drain only after
     * that commit.
     *
     * <p><strong>Security:</strong> both the phone and {@code inviteUrl} (it embeds the raw one-time token) are
     * encrypted with {@link OutboxPayloadCipher} before persistence, under {@value #INVITE_PHONE_SEALED} and
     * {@value #INVITE_URL_SEALED}; plaintext never reaches the DB. The drain opens them at send time. A pre-B520 row
     * (an {@code email} key, no {@value #INVITE_PHONE_SEALED}) is a retired e-mail invite and is skipped by the drain.
     *
     * <p>Validation runs <strong>before</strong> {@link OutboxPayloadCipher#seal(String)}, so malformed input never
     * reaches the cipher: {@code phoneE164} must be {@code +380} + 9 digits; {@code salonName} non-blank and
     * {@code <= 255}; {@code inviteUrl} non-blank, {@code <= 2048} and {@code https://} (or {@code http://localhost},
     * mirroring {@code InviteService.buildInviteLink}) — else {@link IllegalArgumentException}.
     *
     * @param inviteTokenId the {@code invite_tokens} row id (aggregate id, traceability only)
     * @param phoneE164     the invited number — sealed, never logged
     * @param inviteUrl     the join link — sealed, never logged
     * @param salonName     the inviting salon's name (public data)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueStaffInviteSms(UUID inviteTokenId, String phoneE164, String inviteUrl, String salonName) {
        Objects.requireNonNull(inviteTokenId, "inviteTokenId must not be null");
        if (phoneE164 == null || !UA_E164.matcher(phoneE164).matches()) {
            throw new IllegalArgumentException("phoneE164 must be a +380 number");
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
        String payload = writeJson(Map.of(
                INVITE_PHONE_SEALED, cipher.seal(phoneE164),
                INVITE_SALON_NAME, salonName,
                INVITE_URL_SEALED, cipher.seal(inviteUrl)));
        save(OutboxEventType.INVITE, inviteTokenId, payload);
    }

    /**
     * Phase 488 (SUP-67) — discards every still-queued {@code INVITE} e-mail for the salons of an owner whose
     * account is being deleted. Must be called BEFORE those salons' invite tokens are deleted.
     *
     * @param ownerId the owner being deleted
     * @return number of outbox rows discarded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int discardPendingInvitesForOwnerSalons(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        return outboxRepository.deletePendingInvitesForOwnerSalons(ownerId);
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

    /**
     * Phase 491 — enqueues the REQUESTER's "your category request / service-type suggestion was approved
     * or rejected" e-mail ({@link OutboxEventType#REQUEST_DECIDED}), once per decision, in the decision
     * transaction (NTF-01). Nothing is enqueued when the request has no requester (legacy row or deleted
     * account) — the same skip the feed item makes.
     *
     * <p>The payload carries ids and the flag only (D2): {@code kind}, {@code requestId},
     * {@code approved}, {@code recipientUserId}. The name and the admin's reason are re-read at drain time,
     * so no admin free text and no e-mail address sits in {@code notification_outbox.payload}.
     * {@code aggregate_id} is the recipient: a category id is a BIGINT and cannot be the UUID aggregate.
     *
     * @param kind            which request type was decided
     * @param requestId       the decided row's id as text (BIGINT for a category, UUID for a suggestion)
     * @param approved        {@code true} for approve, {@code false} for reject
     * @param recipientUserId the requester; {@code null} skips the row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueRequestDecided(RequestKind kind, String requestId, boolean approved, UUID recipientUserId) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        if (recipientUserId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(REQUEST_KIND, kind.name());
        payload.put(REQUEST_ID, requestId);
        payload.put(REQUEST_APPROVED, approved);
        payload.put(RECIPIENT_USER_ID, recipientUserId.toString());
        save(OutboxEventType.REQUEST_DECIDED, recipientUserId, writeJson(payload));
    }

    /**
     * Phase 494 — enqueues a registered client's 24 h / 2 h reminder e-mail ({@link OutboxEventType#BOOKING_REMINDER})
     * for ONE booking (NTF-D11), in the caller's sweep transaction, right after the caller won the
     * {@code reminder_sent} / {@code reminder_2h_sent} claim — the flag and the row commit together (NTF-01).
     * Payload {@code {"bookingId","kind","claimedAt"}} only — no name, address or note; {@code aggregate_id} = the
     * booking id. {@code claimedAt} (epoch seconds of the sweep tick, from the job's {@code Clock}) lets the drain
     * re-check the claimed window without relying on the row's wall-clock {@code created_at}.
     *
     * @param bookingId the claimed APP booking
     * @param kind      which reminder
     * @param claimedAt the sweep tick's instant
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueBookingReminder(UUID bookingId, ReminderKind kind, Instant claimedAt) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(claimedAt, "claimedAt must not be null");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(REMINDER_BOOKING_ID, bookingId.toString());
        payload.put(REMINDER_KIND, kind.name());
        payload.put(REMINDER_CLAIMED_AT, String.valueOf(claimedAt.getEpochSecond()));
        save(OutboxEventType.BOOKING_REMINDER, bookingId, writeJson(payload));
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
