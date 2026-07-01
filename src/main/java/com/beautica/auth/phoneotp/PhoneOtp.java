package com.beautica.auth.phoneotp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;

import java.time.Instant;

/**
 * One-time SMS verification code issued to an unauthenticated client during the
 * guest-booking flow (Phase 13.2).
 *
 * <p><b>Security:</b> the plaintext 6-digit code is <b>never</b> persisted or logged.
 * Only {@code SHA-256(code)} as a 64-char lowercase hex digest is stored in
 * {@code code_hash}, and verification compares hashes in constant time
 * ({@code MessageDigest.isEqual}) so a timing side-channel cannot leak the code.
 *
 * <p>Rows are short-lived (10-minute expiry) and reclaimed daily by
 * {@link PhoneOtpCleanupJob}. Public setters are intentionally absent — state
 * transitions go through the {@link #issue(String, String, Instant)} factory and the
 * {@link #markUsed()} mutator.
 *
 * <p>{@code created_at} is populated by the DB {@code DEFAULT now()} (V87), so
 * {@link DynamicInsert} omits the column from the INSERT to let the default apply.
 */
@Entity
@Table(
        name = "phone_otps",
        indexes = {
                @Index(name = "idx_phone_otps_lookup", columnList = "phone, used, expires_at"),
                @Index(name = "idx_phone_otps_phone_created_at", columnList = "phone, created_at")
        }
)
@DynamicInsert
@Getter
@NoArgsConstructor
public class PhoneOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used;

    /**
     * Count of wrong verify attempts against this code. Incremented atomically by
     * {@link PhoneOtpRepository#recordFailedAttempt(Long, int)}, which also invalidates the
     * OTP once {@link #MAX_VERIFY_ATTEMPTS} is reached, so a brute-force run can never
     * enumerate the full code space within the TTL — and concurrent wrong-code verifies of
     * the same row cannot overshoot the cap (the increment is a single conditional UPDATE,
     * not a read-modify-write).
     */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * Maximum wrong verify attempts before the OTP is invalidated. After this many wrong
     * codes the OTP is marked used, so even a subsequent <i>correct</i> code is rejected.
     */
    public static final int MAX_VERIFY_ATTEMPTS = 5;

    private PhoneOtp(String phone, String codeHash, Instant expiresAt) {
        this.phone = phone;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.used = false;
        this.attempts = 0;
    }

    /**
     * Factory for a freshly issued, unused OTP.
     *
     * @param phone     recipient phone in E.164 format ({@code +380XXXXXXXXX})
     * @param codeHash  64-char hex SHA-256 of the plaintext code (never the code itself)
     * @param expiresAt absolute expiry instant ({@code now + 10 min})
     */
    public static PhoneOtp issue(String phone, String codeHash, Instant expiresAt) {
        return new PhoneOtp(phone, codeHash, expiresAt);
    }

    /** Marks this OTP as consumed so it cannot be redeemed again. */
    public void markUsed() {
        this.used = true;
    }
}
