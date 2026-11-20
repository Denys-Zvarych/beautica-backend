package com.beautica.auth.phoneotp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;

import java.time.Instant;
import java.util.UUID;

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
 * transitions go through the {@link #issue(String, String, Instant)} factory and the single-statement UPDATEs of
 * {@link PhoneOtpRepository} (no read-modify-write of a managed row).
 *
 * <p>{@code created_at} is populated by the DB {@code DEFAULT now()} (V87), so
 * {@link DynamicInsert} omits the column from the INSERT to let the default apply.
 */
@Entity
@Table(
        name = "phone_otps",
        indexes = {
                @Index(name = "idx_phone_otps_lookup", columnList = "phone, used, expires_at"),
                @Index(name = "idx_phone_otps_phone_created_at", columnList = "phone, created_at"),
                // Partial in the DB (V244: WHERE user_id IS NOT NULL); JPA cannot express the predicate.
                @Index(name = "idx_phone_otps_user_id", columnList = "user_id")
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
     * Count of verify compares reserved against this code (phone-first hardening B1). Reserved atomically BEFORE the
     * compare by {@link PhoneOtpRepository#reserveAttempt(Long, int)} ({@code attempts < cap} in the WHERE clause), so N
     * concurrent verifies can never run more than {@link #MAX_VERIFY_ATTEMPTS} compares; a mismatch at the cap burns
     * the code ({@link PhoneOtpRepository#recordMismatch(Long, int)}).
     */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * Phone-first hardening (V255) — wrong codes typed against THIS row (a per-code tally). The 10-per-24 h cap
     * (AUTH-D26 c) counts {@code phone_otp_failures} per phone + client network (V258) instead; {@link #attempts}
     * counts the reserved compares of the per-code cap.
     */
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    /** B514 (D1, V244) — the flow this code belongs to; verify matches it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private PhoneOtpPurpose purpose;

    /** B514 (V244) — the signed-in owner of a PHONE_ADD / PHONE_CHANGE code (B516); NULL for anonymous purposes. */
    @Column(name = "user_id")
    private UUID userId;

    /**
     * Maximum wrong verify attempts before the OTP is invalidated. After this many wrong
     * codes the OTP is marked used, so even a subsequent <i>correct</i> code is rejected.
     */
    // Production default of app.auth-limits.phone-otp-max-verify-attempts (PhoneOtpService passes the bound value).
    public static final int MAX_VERIFY_ATTEMPTS = 5;

    private PhoneOtp(String phone, String codeHash, Instant expiresAt, PhoneOtpPurpose purpose, UUID userId) {
        this.phone = phone;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.used = false;
        this.attempts = 0;
        this.failedAttempts = 0;
        this.purpose = purpose;
        this.userId = userId;
    }

    /**
     * Factory for a freshly issued, unused OTP.
     *
     * @param phone     recipient phone in E.164 format ({@code +380XXXXXXXXX})
     * @param codeHash  64-char hex SHA-256 of the plaintext code (never the code itself)
     * @param expiresAt absolute expiry instant ({@code now + 10 min})
     */
    public static PhoneOtp issue(String phone, String codeHash, Instant expiresAt) {
        return issue(phone, codeHash, expiresAt, PhoneOtpPurpose.GUEST_BOOKING, null);
    }

    /**
     * B514 — as {@link #issue(String, String, Instant)} for any {@link PhoneOtpPurpose}.
     *
     * @param userId the signed-in owner (B516 PHONE_ADD / PHONE_CHANGE), {@code null} otherwise
     */
    public static PhoneOtp issue(String phone, String codeHash, Instant expiresAt, PhoneOtpPurpose purpose,
                                 UUID userId) {
        return new PhoneOtp(phone, codeHash, expiresAt, purpose, userId);
    }

    /**
     * Phone-first hardening (AUTH-D27 c) — a row that was never sent and can never be redeemed ({@code used = true},
     * a random hash nobody knows): it only occupies a per-phone send slot and the resend cooldown, so a forgot-password
     * request that sends nothing costs the same slot as one that sends an SMS.
     */
    public static PhoneOtp burned(String phone, String randomHash, Instant expiresAt, PhoneOtpPurpose purpose) {
        PhoneOtp otp = new PhoneOtp(phone, randomHash, expiresAt, purpose, null);
        otp.used = true;
        return otp;
    }
}
