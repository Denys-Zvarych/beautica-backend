package com.beautica.user;

import com.beautica.common.AuditableEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists a single-use password-reset-CONFIRM credential.
 *
 * <p>Renamed from {@code PasswordResetToken} (Phase A1): a ticket is now minted only AFTER
 * the caller has proven ownership of the account by verifying a 6-digit OTP sent to their
 * email ({@code PasswordResetOtpProcessor}) — it is no longer minted directly off a
 * forgot-password request. Only the SHA-256 hex hash of the raw ticket is stored here; the
 * raw ticket is handed to the client once (as the {@code resetTicket} field of
 * {@code VerifyPasswordResetOtpResponse}) and never persisted, so a DB dump yields no usable
 * reset credential.
 *
 * <p>Lifecycle: ISSUED (on successful OTP verification) → CONSUMED ({@code is_used = true})
 * or EXPIRED ({@code expires_at < now}). Both terminal states produce the same generic 400 at
 * the API layer ({@code PasswordResetService.resetPassword}) — no oracle.
 *
 * <p><strong>External-storage contract:</strong> this table holds no R2 / S3 reference, so
 * {@code ON DELETE CASCADE} to users is safe with no pre-CASCADE storage sweep required.
 */
@Entity
@Table(name = "password_reset_tickets")
public class PasswordResetTicket extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** SHA-256 hex of the raw ticket — never the raw value. Length mirrors invite_tokens. */
    @Column(name = "ticket_hash", nullable = false, unique = true, length = 512)
    private String ticketHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // Indexed in DB by idx_password_reset_tickets_expires_at (Flyway V107, renamed from the
    // V100 index of the same shape) to back the daily cleanup sweep
    // (DELETE WHERE expires_at < cutoff). Not mirrored as a JPA @Index here to stay
    // consistent with the table's other indexes (ticket_hash/user_id), which are likewise
    // declared only in Flyway.
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "is_used", nullable = false)
    private boolean isUsed = false;

    /** Required by JPA — no business use. */
    protected PasswordResetTicket() {
    }

    /**
     * Creates a new, unused password-reset-ticket row.
     *
     * @param ticketHash SHA-256 hex digest of the raw ticket (64 chars typical).
     * @param userId     FK to the owning user.
     * @param expiresAt  absolute expiry instant; ticket is invalid after this point.
     */
    public PasswordResetTicket(String ticketHash, UUID userId, Instant expiresAt) {
        this.ticketHash = ticketHash;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.isUsed = false;
    }

    public UUID getId() {
        return id;
    }

    @JsonIgnore
    public String getTicketHash() {
        return ticketHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isUsed() {
        return isUsed;
    }

    /** Marks this ticket consumed. Idempotent — calling twice is harmless. */
    public void markUsed() {
        this.isUsed = true;
    }
}
