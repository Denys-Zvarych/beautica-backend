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
 * Phase 399 D3 — the SMS invite that lets a LINK guest review one completed visit (one row per appointment).
 *
 * <p>Never stores a raw secret: {@code phoneHash} is the {@code PhoneHasher} HMAC of the guest phone (phase 397, the
 * same key as {@code client_blocks} and {@code sms_opt_outs}), {@code tokenHash} is the SHA-256 of the raw invite
 * token, which is generated at send time and lives only in the SMS body (phase 400 D4). Created only through
 * {@link #schedule}; state transitions (send, consume, expire, skip, fail) belong to phases 400-402.
 *
 * <p>Lifecycle: deleted by FK cascade with its appointment, or by the retention purge (phase 402 D3). No external
 * storage. DB invariants: V213 {@code chk_review_invites_*}.
 */
@Entity
@Table(
        name = "review_invites",
        indexes = @Index(name = "idx_review_invites_status_send_after", columnList = "status, send_after")
        // ux_review_invites_appointment / ux_review_invites_token_hash are UNIQUE constraints (V213).
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewInvite {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "appointment_id", nullable = false, updatable = false, unique = true)
    private UUID appointmentId;

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
        ReviewInvite invite = new ReviewInvite();
        invite.id = UUID.randomUUID();
        invite.appointmentId = Objects.requireNonNull(appointmentId, "appointmentId");
        invite.phoneHash = Objects.requireNonNull(phone, "phone").hash();
        invite.status = ReviewInviteStatus.SCHEDULED;
        invite.sendAfter = Objects.requireNonNull(sendAfter, "sendAfter");
        invite.createdAt = Objects.requireNonNull(now, "now");
        return invite;
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
