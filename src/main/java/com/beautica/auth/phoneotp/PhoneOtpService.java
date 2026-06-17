package com.beautica.auth.phoneotp;

import com.beautica.common.exception.BusinessException;
import com.beautica.notification.sms.SmsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Issues and verifies SMS one-time codes for the guest-booking flow (Phase 13.2).
 *
 * <p><b>Security posture:</b>
 * <ul>
 *   <li>The plaintext code is never persisted or logged — only {@code SHA-256(code)}
 *       hex is stored; logs carry the masked phone only ({@code +380***1234}).</li>
 *   <li>{@link #verifyOtp} returns a single generic 401 for every failure
 *       (wrong / expired / nonexistent) so the response is no oracle, and compares
 *       hashes in constant time via {@link MessageDigest#isEqual}.</li>
 *   <li>A per-phone rate limit (service layer) complements the per-IP Bucket4j layer
 *       on {@code POST /book/otp/send} — dual-layer defence.</li>
 * </ul>
 */
@Service
public class PhoneOtpService {

    private static final Logger log = LoggerFactory.getLogger(PhoneOtpService.class);

    private static final Pattern E164_UA = Pattern.compile("\\+380[0-9]{9}");
    private static final int MAX_SENDS_PER_WINDOW = 3;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(15);
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final int CODE_BOUND_EXCLUSIVE = 1_000_000; // 000000..999999
    private static final String GENERIC_VERIFY_FAILURE = "Invalid or expired code";

    private final PhoneOtpRepository phoneOtpRepository;
    private final PhoneOtpAttemptRecorder attemptRecorder;
    private final GuestTokenProvider guestTokenProvider;
    private final SmsService smsService;
    private final SecureRandom secureRandom;
    private final Clock clock;

    public PhoneOtpService(PhoneOtpRepository phoneOtpRepository,
                           PhoneOtpAttemptRecorder attemptRecorder,
                           GuestTokenProvider guestTokenProvider,
                           SmsService smsService,
                           Clock clock) {
        this.phoneOtpRepository = phoneOtpRepository;
        this.attemptRecorder = attemptRecorder;
        this.guestTokenProvider = guestTokenProvider;
        this.smsService = smsService;
        this.secureRandom = new SecureRandom();
        this.clock = clock;
    }

    /**
     * Validates the phone, enforces the per-phone rate limit, expires any prior unused
     * code, persists a fresh hashed code, and dispatches it by SMS.
     *
     * @throws BusinessException 400 on a malformed phone, 429 when the per-phone window
     *                           cap is exceeded
     */
    @Transactional
    public void sendOtp(String phone) {
        if (!E164_UA.matcher(phone).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Invalid phone format");
        }

        Instant now = clock.instant();
        int recentSends = phoneOtpRepository.countByPhoneAndCreatedAtAfter(
                phone, now.minus(RATE_LIMIT_WINDOW));
        if (recentSends >= MAX_SENDS_PER_WINDOW) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "OTP limit exceeded");
        }

        // Invalidate any still-active code so only the new one is redeemable.
        phoneOtpRepository.markAllUnusedAsUsed(phone);

        String code = generateCode();
        String codeHash = sha256Hex(code);
        phoneOtpRepository.save(PhoneOtp.issue(phone, codeHash, now.plus(OTP_TTL)));

        smsService.send(phone, "Beautica: " + code + " — код підтвердження");
        log.info("OTP sent to {}", maskPhone(phone));
    }

    /**
     * Verifies a code against the active OTP for the phone and, on success, marks the
     * code used and mints a guest JWT.
     *
     * @return a short-lived guest token authorising a single guest booking
     * @throws BusinessException 401 for any failure (wrong / expired / nonexistent) — a
     *                           single generic message with no distinguishing oracle
     */
    @Transactional
    public String verifyOtp(String phone, String code) {
        Instant now = clock.instant();
        Optional<PhoneOtp> active = phoneOtpRepository
                .findTopByPhoneAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(phone, now);

        if (active.isEmpty()) {
            throw unauthorized();
        }

        PhoneOtp otp = active.get();
        byte[] candidate = sha256Hex(code).getBytes(StandardCharsets.UTF_8);
        byte[] stored = otp.getCodeHash().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(candidate, stored)) {
            // Per-OTP brute-force cap: count this wrong attempt and invalidate the OTP once
            // the cap is reached, so the code space cannot be enumerated within the TTL.
            // A single atomic conditional UPDATE (not a read-modify-write of the managed
            // entity) so concurrent wrong-code verifies of the same row cannot each read
            // attempts < cap and overshoot the cap — the DB serialises the increments.
            // Runs in a separate REQUIRES_NEW transaction so the increment commits BEFORE the
            // generic-401 throw rolls back this verify transaction (otherwise the counter
            // could never accumulate). Must precede the throw.
            attemptRecorder.recordFailedAttempt(otp.getId(), PhoneOtp.MAX_VERIFY_ATTEMPTS);
            throw unauthorized();
        }

        otp.markUsed();
        return guestTokenProvider.generate(phone);
    }

    private String generateCode() {
        return String.format("%06d", secureRandom.nextInt(CODE_BOUND_EXCLUSIVE));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec on every JVM — unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Masks a phone for logging: keeps the {@code +380} prefix and last 4 digits, masking
     * the middle ({@code +380***1234}). Never logs the full PII number (§I).
     */
    private static String maskPhone(String phone) {
        if (phone.length() < 8) {
            return "+380***";
        }
        return phone.substring(0, 4) + "***" + phone.substring(phone.length() - 4);
    }

    private static BusinessException unauthorized() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, GENERIC_VERIFY_FAILURE);
    }
}
