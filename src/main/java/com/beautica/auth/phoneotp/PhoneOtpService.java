package com.beautica.auth.phoneotp;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.config.DeploymentProperties;
import com.beautica.notification.sms.OtpSmsSender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 *   <li>The sender is {@link OtpSmsSender}, <b>not</b> {@code SmsService}: the guest OTP is an auth
 *       credential and is deliberately outside the {@code app.booking.sms.enabled} money gate. See
 *       {@link OtpSmsSender} and {@code SmsConfig} for the carve-out and the defect it closes.</li>
 * </ul>
 */
@Service
public class PhoneOtpService {

    private static final Logger log = LoggerFactory.getLogger(PhoneOtpService.class);

    private static final Pattern E164_UA = Pattern.compile("\\+380[0-9]{9}");
    private static final int MAX_SENDS_PER_WINDOW = 3;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(15);
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    /** B516 (AUTH-12) — a password-reset code by SMS keeps the reset OTP lifetime (15 min), not the 10-min OTP one. */
    private static final Duration PASSWORD_RESET_OTP_TTL = Duration.ofMinutes(15);
    private static final int CODE_BOUND_EXCLUSIVE = 1_000_000; // 000000..999999
    private static final String GENERIC_VERIFY_FAILURE = "Invalid or expired code";
    /** Phase 440 — delivered OTP SMS per format (tag {@code format}); naming as {@code beautica.guest_message}. */
    static final String SENT_METRIC = "beautica.otp_sms.sent";
    /** Phase 440 — a non-blank {@code smsFormat} that named no format (sent as {@code DOMAIN_BOUND}). */
    static final String FORMAT_FALLBACK_METRIC = "beautica.otp_sms.format_fallback";

    private final PhoneOtpRepository phoneOtpRepository;
    private final PhoneOtpAttemptRecorder attemptRecorder;
    private final GuestTokenProvider guestTokenProvider;
    private final OtpSmsSender otpSmsSender;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;
    /** Phase 389 — SMS template with the {@code beautica.sms.otp-domain} suffix pre-built (host validated once). */
    private final OtpSmsText otpSmsText;
    private final Map<OtpSmsFormat, Counter> sentCounters = new EnumMap<>(OtpSmsFormat.class);
    private final Counter formatFallbackCounter;

    public PhoneOtpService(PhoneOtpRepository phoneOtpRepository,
                           PhoneOtpAttemptRecorder attemptRecorder,
                           GuestTokenProvider guestTokenProvider,
                           OtpSmsSender otpSmsSender,
                           Clock clock,
                           PlatformTransactionManager transactionManager,
                           DeploymentProperties deploymentProperties,
                           MeterRegistry meterRegistry) {
        this.otpSmsText = OtpSmsText.forDomain(deploymentProperties.sms().otpDomainHost());
        this.phoneOtpRepository = phoneOtpRepository;
        this.attemptRecorder = attemptRecorder;
        this.guestTokenProvider = guestTokenProvider;
        this.otpSmsSender = otpSmsSender;
        this.secureRandom = new SecureRandom();
        this.clock = clock;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (OtpSmsFormat format : OtpSmsFormat.values()) {
            sentCounters.put(format, Counter.builder(SENT_METRIC)
                    .tag("format", format.metricTag())
                    .register(meterRegistry));
        }
        this.formatFallbackCounter = Counter.builder(FORMAT_FALLBACK_METRIC).register(meterRegistry);
    }

    /**
     * Validates the phone, enforces the per-phone rate limit and persists a fresh hashed code (own
     * short transaction, committed), dispatches it by SMS <b>outside any transaction</b>, and only
     * once delivered invalidates the phone's older unused codes.
     *
     * <p>{@code NOT_SUPPORTED} suspends any caller-supplied transaction (through the Spring proxy —
     * the controller injects this bean, so there is no self-invocation): the provider call can block
     * for up to connect + read timeout (~8 s); inside a transaction that holds a Hikari connection
     * for the whole wait, so a handful of concurrent sends during a Turbosms brown-out would
     * exhaust the pool and stall the whole API.
     *
     * @throws BusinessException 400 on a malformed phone, 429 when the per-phone window
     *                           cap is exceeded, 503 when the provider could not be reached
     *                           (see {@link #dispatchCode})
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendOtp(String phone) {
        sendOtp(phone, null);
    }

    /**
     * Phase 440 — as {@link #sendOtp(String)}, with the caller-requested SMS text. {@code requestedFormat} is the
     * raw request value: missing/blank/unknown sends {@link OtpSmsFormat#DOMAIN_BOUND} (never a 400); an unknown
     * non-blank value also increments {@value #FORMAT_FALLBACK_METRIC}.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendOtp(String phone, String requestedFormat) {
        sendCode(phone, requestedFormat, PhoneOtpPurpose.GUEST_BOOKING);
    }

    /**
     * B514 (D1) — the one send path for every {@link PhoneOtpPurpose}: same row lifecycle, same SMS sender and text,
     * same 503 compensation; the per-phone cap ({@value #MAX_SENDS_PER_WINDOW} per 15 min) counts the codes of EVERY
     * purpose together (one victim gets at most that many SMS per window, whatever the flow mix), and a newer send invalidates only older codes of the same purpose. {@code phone} must already be
     * {@code +380…} (callers normalise; a malformed value is a 400 here as before).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendCode(String phone, String requestedFormat, PhoneOtpPurpose purpose) {
        sendCode(phone, requestedFormat, purpose, null);
    }

    /**
     * B516 (AUTH-44) — as {@link #sendCode(String, String, PhoneOtpPurpose)}, with the signed-in owner of a
     * {@link PhoneOtpPurpose#isUserBound() user-bound} code. The row carries {@code userId}, only
     * {@link #consumeCode(String, String, PhoneOtpPurpose, UUID)} with the same id redeems it, and on top of the
     * per-phone cap the account gets the same {@value #MAX_SENDS_PER_WINDOW} sends per 15 min across all its phones.
     *
     * @throws IllegalArgumentException when {@code userId} is missing for a user-bound purpose or present otherwise
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendCode(String phone, String requestedFormat, PhoneOtpPurpose purpose, UUID userId) {
        requireOwnerMatchesPurpose(purpose, userId);
        if (phone == null || !E164_UA.matcher(phone).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Invalid phone format");
        }
        OtpSmsFormat format = resolveFormat(requestedFormat);

        String code = generateCode();
        Long otpId = requiresNewTx.execute(status -> issueOtpRow(phone, code, purpose, userId));

        dispatchCode(phone, code, otpId, format);
        invalidateOlderCodesQuietly(phone, purpose, otpId);
        if (purpose == PhoneOtpPurpose.GUEST_BOOKING) {
            log.info("OTP sent to {} format={}", maskPhone(phone), format.metricTag()); // pre-B514 line, unchanged
        } else {
            log.info("OTP sent to {} format={} purpose={}", maskPhone(phone), format.metricTag(), purpose);
        }
    }

    private OtpSmsFormat resolveFormat(String requestedFormat) {
        if (OtpSmsFormat.isUnrecognised(requestedFormat)) {
            formatFallbackCounter.increment();
        }
        return OtpSmsFormat.fromRequest(requestedFormat);
    }

    /**
     * Tx 1: per-phone advisory lock, rate-limit check, persist ONLY the new hashed row. Runs in
     * REQUIRES_NEW. The previous code is deliberately left valid until delivery succeeds, so a
     * provider outage cannot be used to kill a victim's live code. The lock serialises the
     * count-then-insert per phone so concurrent sends cannot all pass the cap.
     */
    private Long issueOtpRow(String phone, String code, PhoneOtpPurpose purpose, UUID userId) {
        if (userId != null) {
            // B516: the per-account cap is counted under a per-ACCOUNT lock (always taken before the phone lock).
            phoneOtpRepository.lockUserForSend(userId);
        }
        phoneOtpRepository.lockPhoneForSend(phone);

        Instant now = clock.instant();
        Instant windowStart = now.minus(RATE_LIMIT_WINDOW);
        int recentSends = phoneOtpRepository.countByPhoneAndCreatedAtAfter(phone, windowStart);
        // B516: per-account cap for signed-in sends, counted under the account lock taken above.
        boolean accountCapped = userId != null
                && phoneOtpRepository.countByUserIdAndCreatedAtAfter(userId, windowStart) >= MAX_SENDS_PER_WINDOW;
        if (recentSends >= MAX_SENDS_PER_WINDOW || accountCapped) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "OTP limit exceeded");
        }

        Duration ttl = purpose == PhoneOtpPurpose.PASSWORD_RESET ? PASSWORD_RESET_OTP_TTL : OTP_TTL;
        PhoneOtp otp = PhoneOtp.issue(phone, sha256Hex(code), now.plus(ttl), purpose, userId);
        phoneOtpRepository.save(otp);
        return otp.getId();
    }

    /**
     * Hands the code to the auth-channel sender, translating any provider failure into a clean
     * <b>503</b> and compensating by marking the freshly committed OTP row used (Tx 2). The
     * previous code stays valid (it was never invalidated): {@code verifyOtp} picks the newest
     * UNUSED row.
     *
     * <h4>Why NOT the booking paths' swallow-and-warn</h4>
     * Every booking sender catches, logs and carries on, because the booking is already committed
     * and a provider outage must not turn a successful create into a failed request. The OTP is the
     * exact inverse: the row is worthless to a guest who never receives the code, and answering 200
     * would recreate — from a different cause — the very silent-failure defect the
     * {@link OtpSmsSender} carve-out exists to close. So the failure is surfaced, and the row is
     * burnt ({@code used = true}, never deleted): it can never be redeemed, and it STILL counts
     * toward the per-phone window cap (the count query ignores {@code used}) — a provider timeout
     * may have delivered the SMS anyway, so failed attempts must not allow more than the cap of
     * SMS per window to a victim.
     *
     * <h4>Ordering</h4>
     * The row is committed BEFORE the send so no DB connection is held while the provider is slow
     * (pool exhaustion). The send is still synchronous, so a failure is reported to the caller as a
     * 503. If the compensating UPDATE itself fails, the 503 is still returned; the unsent row stays
     * UNUSED until its TTL (then the expiry cleanup job removes it). While it lives, {@code verifyOtp} (newest unused row wins)
     * would test codes against that undelivered row instead of the older delivered one — an
     * availability blip bounded by the TTL, never a security hole.
     *
     * <p>The text is {@link OtpSmsText} (Phase 389: fixed template, origin-bound last line when
     * {@code beautica.sms.otp-domain} is set) and is never logged.
     *
     * <p>Only the exception CLASS is logged, never the message (may echo the provider URL), never
     * the code and never the unmasked phone (Anti-Bug §I). The 503 body is generic for the same
     * reason.
     */
    private void dispatchCode(String phone, String code, Long otpId, OtpSmsFormat format) {
        try {
            otpSmsSender.send(phone, otpSmsText.render(code, format));
            sentCounters.get(format).increment();
        } catch (RuntimeException e) {
            log.warn("OTP send failed for {}: {}", maskPhone(phone), e.getClass().getSimpleName());
            burnUndeliveredRowQuietly(otpId);
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableMessages.OTP_SEND_FAILED);
        }
    }

    /** Tx 2 (compensation): marks the undelivered OTP row used; never masks the original 503. */
    private void burnUndeliveredRowQuietly(Long otpId) {
        if (otpId == null) {
            return;
        }
        try {
            requiresNewTx.executeWithoutResult(status -> phoneOtpRepository.markAsUsedById(otpId));
        } catch (RuntimeException e) {
            log.warn("OTP compensation update failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Tx 3 (post-delivery): one UPDATE marking every OLDER unused code for the phone as used (id below the
     * new row's), so the newest delivered code stays redeemable even when sends overlap. Failure is logged, not surfaced: the SMS is already
     * out, and {@code verifyOtp} redeems only the newest unused row regardless.
     */
    private void invalidateOlderCodesQuietly(String phone, PhoneOtpPurpose purpose, Long newId) {
        if (newId == null) {
            return;
        }
        try {
            requiresNewTx.executeWithoutResult(
                    status -> phoneOtpRepository.markOlderAsUsed(phone, purpose, newId));
        } catch (RuntimeException e) {
            log.warn("OTP older-code invalidation failed for {}: {}",
                    maskPhone(phone), e.getClass().getSimpleName());
        }
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
        if (!consumeCode(phone, code, PhoneOtpPurpose.GUEST_BOOKING)) {
            throw unauthorized();
        }
        return guestTokenProvider.generate(phone);
    }

    /**
     * B514 (D1) — redeems the newest live code of {@code phone} for {@code purpose}: on a match marks it used and
     * returns {@code true}; on a wrong, expired, missing or other-purpose code records the attempt (per-OTP cap) and
     * returns {@code false}. One result for every failure, so the caller answers with one generic error (no oracle).
     * Joins the caller's transaction (the used flag commits with the caller's own write).
     */
    @Transactional
    public boolean consumeCode(String phone, String code, PhoneOtpPurpose purpose) {
        requireOwnerMatchesPurpose(purpose, null);
        Instant now = clock.instant();
        return redeem(phoneOtpRepository
                .findTopByPhoneAndPurposeAndUsedFalseAndExpiresAtAfterOrderByIdDesc(phone, purpose, now), code);
    }

    /**
     * B516 (AUTH-44) — as {@link #consumeCode(String, String, PhoneOtpPurpose)} for a user-bound purpose: only a code
     * issued to {@code userId} for {@code phone} is considered, so one account can never redeem another account's
     * PHONE_ADD / PHONE_CHANGE code (the other account's row is invisible here and counts as "no code").
     */
    @Transactional
    public boolean consumeCode(String phone, String code, PhoneOtpPurpose purpose, UUID userId) {
        requireOwnerMatchesPurpose(purpose, userId);
        Instant now = clock.instant();
        return redeem(phoneOtpRepository
                .findTopByPhoneAndPurposeAndUserIdAndUsedFalseAndExpiresAtAfterOrderByIdDesc(
                        phone, purpose, userId, now), code);
    }

    /**
     * B516 (AUTH-D19) — a wrong current password on a {@link PhoneOtpPurpose#PHONE_CHANGE} verify counts as one failed
     * try on the caller's newest live code: the same per-code cap ({@link PhoneOtp#MAX_VERIFY_ATTEMPTS}, then the code
     * is burnt) and the same REQUIRES_NEW recorder as a wrong code, so the count survives the caller's rollback.
     *
     * @return {@code false} when {@code userId} holds no live code for {@code phone} (nothing to count against)
     */
    @Transactional
    public boolean recordFailedAttempt(String phone, PhoneOtpPurpose purpose, UUID userId) {
        requireOwnerMatchesPurpose(purpose, userId);
        Optional<PhoneOtp> active = phoneOtpRepository
                .findTopByPhoneAndPurposeAndUserIdAndUsedFalseAndExpiresAtAfterOrderByIdDesc(
                        phone, purpose, userId, clock.instant());
        active.ifPresent(otp -> attemptRecorder.recordFailedAttempt(otp.getId(), PhoneOtp.MAX_VERIFY_ATTEMPTS));
        return active.isPresent();
    }

    private static void requireOwnerMatchesPurpose(PhoneOtpPurpose purpose, UUID userId) {
        if (purpose.isUserBound() != (userId != null)) {
            throw new IllegalArgumentException("Owner does not match OTP purpose " + purpose);
        }
    }

    private boolean redeem(Optional<PhoneOtp> active, String code) {
        if (active.isEmpty()) {
            return false;
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
            // could never accumulate). Must precede the caller's throw.
            attemptRecorder.recordFailedAttempt(otp.getId(), PhoneOtp.MAX_VERIFY_ATTEMPTS);
            return false;
        }

        otp.markUsed();
        return true;
    }

    private String generateCode() {
        return String.format("%06d", secureRandom.nextInt(CODE_BOUND_EXCLUSIVE));
    }

    /**
     * Phase 373 D9 — deletes every OTP row of an already-normalised E.164 phone as part of the
     * internal guest-PII erase. {@code MANDATORY}: only ever runs inside the caller's erase
     * transaction, so the OTP delete commits or rolls back together with the booking wipe.
     *
     * @return number of OTP rows deleted
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteAllForPhone(String e164Phone) {
        return phoneOtpRepository.deleteByPhone(e164Phone);
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
