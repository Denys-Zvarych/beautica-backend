package com.beautica.review.invite.entity;

import com.beautica.common.security.PhoneHasher.HashedPhone;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 399 D3 — the SMS invite that lets a LINK guest review one completed visit (one row per appointment, or —
 * phase 400 D7 — per single LINK booking with no visit header).
 *
 * <p>Never stores a raw secret: {@code phoneHash} is the {@code PhoneHasher} HMAC of the guest phone (phase 397, the
 * same key as {@code client_blocks} and {@code sms_opt_outs}), {@code tokenHash} is the SHA-256 of the raw invite
 * token, which is generated at send time and lives only in the SMS body (phase 400 D4). Created only through
 * {@link #schedule}; state transitions (send, consume, expire, skip, fail) belong to phases 400-402.
 *
 * <p>Lifecycle: deleted by FK cascade with its appointment (or booking, D7), or by the retention purge (phase 402 D3). No external
 * storage. DB invariants: V213 {@code chk_review_invites_*}.
 */
@Entity
@Table(
        name = "review_invites",
        // Both indexes are PARTIAL in the DB (V216) — JPA @Index cannot express a WHERE clause, so the predicate is
        // recorded here only: idx_review_invites_scheduled_send_after (send_after) WHERE status = 'SCHEDULED'
        // (claimDue) and idx_review_invites_sent_expires (expires_at) WHERE status = 'SENT' (expireSent). Never let
        // Hibernate generate them. ux_review_invites_appointment / ux_review_invites_token_hash are UNIQUE
        // constraints (V213); ux_review_invites_booking (V215).
        indexes = {
                @Index(name = "idx_review_invites_scheduled_send_after", columnList = "send_after"),
                @Index(name = "idx_review_invites_sent_expires", columnList = "expires_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewInvite {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The visit subject; {@code null} on a booking-keyed invite (D7, V215 {@code chk_review_invites_one_subject}). */
    @Column(name = "appointment_id", nullable = true, updatable = false, unique = true)
    private UUID appointmentId;

    /**
     * Phase 400 D7 — the single-LINK-booking subject ({@code bookings.appointment_id IS NULL}); {@code null} on a
     * visit-keyed invite. Exactly one of {@code appointmentId} / {@code bookingId} is set.
     */
    @Column(name = "booking_id", updatable = false, unique = true)
    private UUID bookingId;

    @Column(name = "phone_hash", nullable = false, updatable = false)
    private byte[] phoneHash;

    @Column(name = "token_hash", unique = true)
    private byte[] tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReviewInviteStatus status;

    @Column(name = "send_after", nullable = false)
    private Instant sendAfter;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "attempts", nullable = false)
    private short attempts;

    /** Set exactly on {@link ReviewInviteStatus#SKIPPED} rows; values mirror V213 {@code chk_review_invites_skip_reason}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "skip_reason", length = 32)
    private ReviewInviteSkipReason skipReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * A new {@link ReviewInviteStatus#SCHEDULED} invite for {@code appointmentId}. Takes a {@link HashedPhone}, which
     * only {@code PhoneHasher} can mint, so the stored hash is always the shared phone key — never a raw phone.
     */
    public static ReviewInvite schedule(UUID appointmentId, HashedPhone phone, Instant sendAfter, Instant now) {
        ReviewInvite invite = newScheduled(phone, sendAfter, now);
        invite.appointmentId = Objects.requireNonNull(appointmentId, "appointmentId");
        return invite;
    }

    /** Phase 400 D7 — {@link #schedule} for a single LINK booking with no visit header. */
    public static ReviewInvite scheduleForBooking(UUID bookingId, HashedPhone phone, Instant sendAfter, Instant now) {
        ReviewInvite invite = newScheduled(phone, sendAfter, now);
        invite.bookingId = Objects.requireNonNull(bookingId, "bookingId");
        return invite;
    }

    private static ReviewInvite newScheduled(HashedPhone phone, Instant sendAfter, Instant now) {
        ReviewInvite invite = new ReviewInvite();
        invite.id = UUID.randomUUID();
        invite.phoneHash = Objects.requireNonNull(phone, "phone").hash();
        invite.status = ReviewInviteStatus.SCHEDULED;
        invite.sendAfter = Objects.requireNonNull(sendAfter, "sendAfter");
        invite.createdAt = Objects.requireNonNull(now, "now");
        return invite;
    }

    /**
     * Phase 400 D2 — an invite that is terminal from birth: the visit was completed but a gate (opt-out, policy
     * version, nothing to review) already fails, so the row records why no SMS will go out.
     */
    public static ReviewInvite skippedAtCreate(UUID appointmentId, HashedPhone phone, Instant sendAfter,
                                               ReviewInviteSkipReason reason, Instant now) {
        ReviewInvite invite = schedule(appointmentId, phone, sendAfter, now);
        invite.skip(reason);
        return invite;
    }

    /** Phase 400 D7 — {@link #skippedAtCreate} for a single LINK booking with no visit header. */
    public static ReviewInvite skippedAtCreateForBooking(UUID bookingId, HashedPhone phone, Instant sendAfter,
                                                         ReviewInviteSkipReason reason, Instant now) {
        ReviewInvite invite = scheduleForBooking(bookingId, phone, sendAfter, now);
        invite.skip(reason);
        return invite;
    }

    /** Phase 400 D7 — {@code true} when the subject is a single booking, {@code false} for a visit. */
    public boolean isBookingKeyed() {
        return bookingId != null;
    }

    /**
     * Phase 400 D4 — the SMS is about to go out: store the SHA-256 of the raw token (never the token), stamp
     * {@code sentAt} and {@code expiresAt}. Only from {@link ReviewInviteStatus#SCHEDULED}.
     */
    public void markSent(byte[] tokenHash, Instant sentAt, Instant expiresAt) {
        requireStatus(ReviewInviteStatus.SCHEDULED);
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash").clone();
        this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.status = ReviewInviteStatus.SENT;
    }

    /** Phase 400 D2/D3 — a gate failed; terminal. Only from {@link ReviewInviteStatus#SCHEDULED}. */
    public void skip(ReviewInviteSkipReason reason) {
        requireStatus(ReviewInviteStatus.SCHEDULED);
        this.skipReason = Objects.requireNonNull(reason, "reason");
        this.status = ReviewInviteStatus.SKIPPED;
    }

    /**
     * Phase 400 D4 — the SMS provider refused a {@link ReviewInviteStatus#SENT} invite: the issued token is void
     * (hash and timestamps cleared, so it can never be redeemed), {@code attempts} grows, and the row goes back to
     * {@link ReviewInviteStatus#SCHEDULED} for the next tick — or to {@link ReviewInviteStatus#FAILED} once
     * {@code maxAttempts} is spent.
     */
    public void recordFailedSend(int maxAttempts) {
        requireStatus(ReviewInviteStatus.SENT);
        this.attempts++;
        this.tokenHash = null;
        this.sentAt = null;
        this.expiresAt = null;
        this.status = attempts >= maxAttempts ? ReviewInviteStatus.FAILED : ReviewInviteStatus.SCHEDULED;
    }

    /**
     * Phase 400 audit cycle 1 — the provider call for a {@link ReviewInviteStatus#SENT} invite ended with an UNKNOWN
     * outcome (read timeout after the request was written): the SMS may have been delivered. Retrying could send a
     * second SMS, so the row goes straight to {@link ReviewInviteStatus#FAILED} with the attempt counted. The token is
     * voided like on any failure, so a link that did arrive cannot be redeemed (the guest gets no second chance
     * rather than a duplicate message).
     */
    public void recordAmbiguousSend() {
        requireStatus(ReviewInviteStatus.SENT);
        this.attempts++;
        voidToken();
        this.status = ReviewInviteStatus.FAILED;
    }

    /**
     * Phase 400 audit cycle 1 — a claimed {@link ReviewInviteStatus#SENT} invite whose SMS was never attempted (the
     * tick's send budget ran out): back to {@link ReviewInviteStatus#SCHEDULED} with the token voided and
     * {@code attempts} unchanged — nothing was sent, so nothing failed. A row is never left SENT without a send.
     */
    public void releaseUnsent() {
        requireStatus(ReviewInviteStatus.SENT);
        voidToken();
        this.status = ReviewInviteStatus.SCHEDULED;
    }

    private void voidToken() {
        this.tokenHash = null;
        this.sentAt = null;
        this.expiresAt = null;
    }

    private void requireStatus(ReviewInviteStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Review invite " + id + " is " + status + ", expected " + expected);
        }
    }

    /** Defensive copy — the array is never exposed mutable. */
    @JsonIgnore
    public byte[] getPhoneHash() {
        return phoneHash.clone();
    }

    /** Defensive copy; {@code null} until the invite is sent. */
    @JsonIgnore
    public byte[] getTokenHash() {
        return tokenHash == null ? null : tokenHash.clone();
    }
}
