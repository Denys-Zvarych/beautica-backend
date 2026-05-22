package com.beautica.user;

import com.beautica.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists a single-use password-reset credential.
 *
 * <p>Only the SHA-256 hex hash of the raw token is stored here; the raw token is
 * emailed once and never persisted, so a DB dump yields no usable reset links.
 *
 * <p>Lifecycle: ISSUED → CONSUMED (is_used = true) or EXPIRED (expires_at &lt; now).
 * Both terminal states produce the same generic 400 at the API layer — no oracle.
 *
 * <p><strong>External-storage contract:</strong> this table holds no R2 / S3 reference,
 * so ON DELETE CASCADE to users is safe with no pre-CASCADE storage sweep required.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** SHA-256 hex of the raw token — never the raw value. Length mirrors invite_tokens. */
    @Column(name = "token", nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "is_used", nullable = false)
    private boolean isUsed = false;

    /** Required by JPA — no business use. */
    protected PasswordResetToken() {
    }

    /**
     * Creates a new, unused password-reset token row.
     *
     * @param token     SHA-256 hex digest of the raw token (64 chars typical).
     * @param userId    FK to the owning user.
     * @param expiresAt absolute expiry instant; token is invalid after this point.
     */
    public PasswordResetToken(String token, UUID userId, Instant expiresAt) {
        this.token = token;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.isUsed = false;
    }

    public UUID getId() {
        return id;
    }

    public String getToken() {
        return token;
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

    /** Marks this token consumed. Idempotent — calling twice is harmless. */
    public void markUsed() {
        this.isUsed = true;
    }
}
