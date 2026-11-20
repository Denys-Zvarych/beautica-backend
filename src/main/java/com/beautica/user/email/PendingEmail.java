package com.beautica.user.email;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicInsert;

import java.time.Instant;
import java.util.UUID;

/**
 * B516 (AUTH-50, CLI-50, NTF-56) — an optional notifications email waiting for its emailed code (V245
 * {@code user_pending_emails}, at most one row per account). The address lives ONLY here until the code is confirmed;
 * {@code users.email} is written at verify time, never before, so no business email can reach an unproven address.
 *
 * <p>AUTH-03 code rules: 6 digits, 15 min, 5 attempts per code (then {@code CODE_EXPIRED}), 60 s resend cooldown.
 * {@code failedTotal} / {@code lockedUntil} survive a resend (cumulative brute-force bound, as
 * {@code users.verification_failed_total}). Only the peppered hash of the code is stored. No external storage; the row
 * goes with the user ({@code ON DELETE CASCADE}) or at a successful verify.
 */
@Entity
@Table(name = "user_pending_emails")
@DynamicInsert
public class PendingEmail {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @JsonIgnore
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempts", nullable = false)
    private short attempts;

    @Column(name = "failed_total", nullable = false)
    private short failedTotal;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected PendingEmail() {
    }

    private PendingEmail(UUID userId) {
        this.userId = userId;
    }

    /** A row for an account that never requested a code; {@link #issue} fills it before it is saved. */
    public static PendingEmail forUser(UUID userId) {
        return new PendingEmail(userId);
    }

    /** Points the row at {@code normalisedEmail} with a fresh code; the per-code attempt window restarts. */
    public void issue(String normalisedEmail, String newCodeHash, Instant now, Instant newExpiresAt) {
        this.email = normalisedEmail;
        this.codeHash = newCodeHash;
        this.sentAt = now;
        this.expiresAt = newExpiresAt;
        this.attempts = 0;
    }

    /** Counts one wrong code: per-code attempt and the resend-surviving total; locks once the total hits the cap. */
    public void recordFailure(int cumulativeThreshold, Instant lockUntilIfTripped) {
        this.attempts = (short) (attempts + 1);
        int total = failedTotal + 1;
        this.failedTotal = (short) Math.min(total, Short.MAX_VALUE);
        if (total >= cumulativeThreshold) {
            this.lockedUntil = lockUntilIfTripped;
        }
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public short getAttempts() {
        return attempts;
    }
}
