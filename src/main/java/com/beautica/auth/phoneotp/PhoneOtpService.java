package com.beautica.auth.phoneotp;

import com.beautica.auth.filter.ClientIpResolver;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.config.AuthLimitsProperties;
import com.beautica.config.ClientIpProperties;
import com.beautica.config.DeploymentProperties;
import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.SmsDeliveryAmbiguousException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Locale;
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
    /** Production default of {@code app.auth-limits.phone-otp-max-sends-per-window}. */
    public static final int MAX_SENDS_PER_WINDOW = 3;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(15);
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    /** AUTH-D26 (b) — a password-reset code by SMS lives 10 min, like the emailed reset code (was 15 min, B516). */
    private static final Duration PASSWORD_RESET_OTP_TTL = Duration.ofMinutes(10);
    /** Production default of {@code app.auth-limits.phone-failed-verifies-per-day} (AUTH-D26 c). */
    public static final int MAX_FAILED_VERIFIES_PER_DAY = 10;
    /** Production default of {@code app.auth-limits.otp-resend-cooldown-seconds} (AUTH-D27 a). */
    public static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final Duration FAILED_VERIFY_WINDOW = Duration.ofHours(24);
    private static final int CODE_BOUND_EXCLUSIVE = 1_000_000; // 000000..999999
    private static final String GENERIC_VERIFY_FAILURE = "Invalid or expired code";
    /** Phase 440 — delivered OTP SMS per format (tag {@code format}); naming as {@code beautica.guest_message}. */
    static final String SENT_METRIC = "beautica.otp_sms.sent";
    /** Phase 440 — a non-blank {@code smsFormat} that named no format (sent as {@code DOMAIN_BOUND}). */
    static final String FORMAT_FALLBACK_METRIC = "beautica.otp_sms.format_fallback";
    /** B7 — every send attempt, tags {@code purpose} and {@code result} ({@link SendResult}). */
    public static final String SEND_METRIC = "beautica.otp.send";
    /** B7 — every verify, tags {@code purpose} and {@code result} ({@link VerifyResult}). */
    public static final String VERIFY_METRIC = "beautica.otp.verify";

    /** {@code result} tag of {@value #SEND_METRIC}. */
    enum SendResult {
        OK, PROVIDER_ERROR, AMBIGUOUS, RATE_LIMITED, COOLDOWN;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code result} tag of {@value #VERIFY_METRIC}. */
    enum VerifyResult {
        OK, INVALID, EXHAUSTED, DAILY_CAP;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

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
    private final int maxSendsPerWindow;
    private final int maxVerifyAttempts;
    private final int maxFailedVerifiesPerDay;
    private final Duration resendCooldown;
    private final MeterRegistry meterRegistry;
    /** AUTH-D26 (c) audit-fix — the second half of the per-(phone, client network) failed-verify key. */
    private final OtpClientNetwork clientNetwork;

    /** Production defaults ({@link #MAX_SENDS_PER_WINDOW}, {@link PhoneOtp#MAX_VERIFY_ATTEMPTS}); kept for tests. */
    public PhoneOtpService(PhoneOtpRepository phoneOtpRepository,
                           PhoneOtpAttemptRecorder attemptRecorder,
                           GuestTokenProvider guestTokenProvider,
                           OtpSmsSender otpSmsSender,
                           Clock clock,
                           PlatformTransactionManager transactionManager,
                           DeploymentProperties deploymentProperties,
                           MeterRegistry meterRegistry) {
        this(phoneOtpRepository, attemptRecorder, guestTokenProvider, otpSmsSender, clock, transactionManager,
                deploymentProperties, meterRegistry, AuthLimitsProperties.defaults());
    }

    /** As the Spring constructor with a socket-address-only network resolver (unit tests bind no request anyway). */
    public PhoneOtpService(PhoneOtpRepository phoneOtpRepository,
                           PhoneOtpAttemptRecorder attemptRecorder,
                           GuestTokenProvider guestTokenProvider,
                           OtpSmsSender otpSmsSender,
                           Clock clock,
                           PlatformTransactionManager transactionManager,
                           DeploymentProperties deploymentProperties,
                           MeterRegistry meterRegistry,
                           AuthLimitsProperties limits) {
        this(phoneOtpRepository, attemptRecorder, guestTokenProvider, otpSmsSender, clock, transactionManager,
                deploymentProperties, meterRegistry, limits,
                new OtpClientNetwork(new ClientIpResolver(new ClientIpProperties(false))));
    }

    @Autowired
    public PhoneOtpService(PhoneOtpRepository phoneOtpRepository,
                           PhoneOtpAttemptRecorder attemptRecorder,
                           GuestTokenProvider guestTokenProvider,
                           OtpSmsSender otpSmsSender,
                           Clock clock,
                           PlatformTransactionManager transactionManager,
                           DeploymentProperties deploymentProperties,
                           MeterRegistry meterRegistry,
                           AuthLimitsProperties limits,
                           OtpClientNetwork clientNetwork) {
        this.clientNetwork = clientNetwork;
        this.maxSendsPerWindow = limits.phoneOtpMaxSendsPerWindow();
        this.maxVerifyAttempts = limits.phoneOtpMaxVerifyAttempts();
        this.maxFailedVerifiesPerDay = limits.phoneFailedVerifiesPerDay();
        this.resendCooldown = Duration.ofSeconds(limits.otpResendCooldownSeconds());
        this.meterRegistry = meterRegistry;
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
        send(phone, requestedFormat, purpose, userId, clientNetwork.current());
    }

    /**
     * AUTH-D26 (c) audit-fix — as {@link #sendCode(String, String, PhoneOtpPurpose)} for a send that runs AFTER its
     * request (the forgot-password SMS on {@code smsSendExecutor}): {@code clientNet} is the network the caller
     * captured on the request thread ({@link OtpClientNetwork#current()}), so the per-(phone, network) cap still
     * applies.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendCodeFromNetwork(String phone, PhoneOtpPurpose purpose, String clientNet) {
        send(phone, null, purpose, null, clientNet);
    }

    /** The network key of the current request, for a caller that sends later on another thread. */
    public String currentClientNetwork() {
        return clientNetwork.current();
    }

    private void send(String phone, String requestedFormat, PhoneOtpPurpose purpose, UUID userId, String clientNet) {
        requireOwnerMatchesPurpose(purpose, userId);
        if (phone == null || !E164_UA.matcher(phone).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Invalid phone format");
        }
        OtpSmsFormat format = resolveFormat(requestedFormat);

        String code = generateCode();
        Long otpId = issueOrCount(phone, code, purpose, userId, clientNet);

        dispatchCode(phone, code, otpId, format, purpose);
        countSend(purpose, SendResult.OK);
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

    /** Tx 1 ({@link #issueOtpRow}), counting a refusal on {@value #SEND_METRIC} before rethrowing it. */
    private Long issueOrCount(String phone, String code, PhoneOtpPurpose purpose, UUID userId, String clientNet) {
        try {
            return requiresNewTx.execute(status -> issueOtpRow(phone, code, purpose, userId, clientNet));
        } catch (ResendThrottledException e) {
            countSend(purpose, SendResult.COOLDOWN);
            throw e;
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                countSend(purpose, SendResult.RATE_LIMITED);
            }
            throw e;
        }
    }

    /**
     * Tx 1: per-phone advisory lock, send limits ({@link #enforceSendLimits}), persist ONLY the new hashed row. Runs
     * in REQUIRES_NEW. The previous code is deliberately left valid until delivery succeeds, so a provider outage
     * cannot be used to kill a victim's live code. The lock serialises the count-then-insert per phone so concurrent
     * sends cannot all pass the cap.
     */
    private Long issueOtpRow(String phone, String code, PhoneOtpPurpose purpose, UUID userId, String clientNet) {
        if (userId != null) {
            // B516: the per-account cap is counted under a per-ACCOUNT lock (always taken before the phone lock).
            phoneOtpRepository.lockUserForSend(userId);
        }
        phoneOtpRepository.lockPhoneForSend(phone);

        Instant now = clock.instant();
        enforceSendLimits(phone, purpose, userId, clientNet, now);

        Duration ttl = purpose == PhoneOtpPurpose.PASSWORD_RESET ? PASSWORD_RESET_OTP_TTL : OTP_TTL;
        PhoneOtp otp = PhoneOtp.issue(phone, sha256Hex(code), now.plus(ttl), purpose, userId);
        phoneOtpRepository.save(otp);
        return otp.getId();
    }

    /**
     * Every per-phone send limit, checked under the phone lock from ONE aggregate read
     * ({@link PhoneOtpRepository#sendLimitStats}): {@value #MAX_SENDS_PER_WINDOW} per 15 min per phone (and per
     * signed-in account, a separate count), then {@value #MAX_FAILED_VERIFIES_PER_DAY} failed verifies per phone + client
     * network per 24 h (AUTH-D26 c), both 429 "OTP limit exceeded"; then the per-phone+purpose resend cooldown
     * (AUTH-D27 a), 429 with {@code Retry-After} = the seconds left.
     */
    private void enforceSendLimits(String phone, PhoneOtpPurpose purpose, UUID userId, String clientNet,
                                   Instant now) {
        Instant windowStart = now.minus(RATE_LIMIT_WINDOW);
        Instant cooldownStart = now.minus(resendCooldown);
        Instant rangeStart = windowStart.isBefore(cooldownStart) ? windowStart : cooldownStart;
        PhoneOtpRepository.SendLimitStats stats = phoneOtpRepository.sendLimitStats(phone, purpose.name(), clientNet,
                rangeStart, windowStart, cooldownStart, now.minus(FAILED_VERIFY_WINDOW));
        // B516: per-account cap for signed-in sends, counted under the account lock taken above.
        boolean accountCapped = userId != null
                && phoneOtpRepository.countByUserIdAndCreatedAtAfter(userId, windowStart) >= maxSendsPerWindow;
        if (stats.getRecentSends() >= maxSendsPerWindow || accountCapped
                || stats.getRecentFailures() >= maxFailedVerifiesPerDay) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "OTP limit exceeded");
        }
        Long latestMillis = stats.getLatestSamePurposeMillis();
        if (latestMillis != null) {
            throw new ResendThrottledException(cooldownRemainingSeconds(Instant.ofEpochMilli(latestMillis), now));
        }
    }

    /** Seconds until {@code latest + cooldown}, rounded up, clamped to {@code [1, cooldown]} (DB vs app clock skew). */
    private long cooldownRemainingSeconds(Instant latest, Instant now) {
        long remainingMillis = Duration.between(now, latest.plus(resendCooldown)).toMillis();
        long seconds = (remainingMillis + 999) / 1000;
        return Math.max(1, Math.min(resendCooldown.getSeconds(), seconds));
    }

    /**
     * AUTH-D27 (c) — a forgot-password request for a UA mobile number that sends NO SMS (unknown or inactive number,
     * email channel, account lock or cooldown) still takes one per-phone send slot: under the same phone lock and the
     * same limits as a real send it stores a burned row ({@link PhoneOtp#burned}: never sent, never redeemable) that
     * counts toward the 15-min cap and the resend cooldown — so a per-phone 429 on another purpose tells nothing
     * about the account. When a limit already refuses a send, nothing is stored (a real send would be refused too).
     * Own committed transaction; never throws (the forgot-password answer stays the uniform 200).
     *
     * @param phoneE164 an already-parsed {@code +380…} mobile number
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void burnSlot(String phoneE164, PhoneOtpPurpose purpose) {
        requireOwnerMatchesPurpose(purpose, null);
        try {
            requiresNewTx.executeWithoutResult(status -> {
                phoneOtpRepository.lockPhoneForSend(phoneE164);
                Instant now = clock.instant();
                enforceSendLimits(phoneE164, purpose, null, clientNetwork.current(), now);
                phoneOtpRepository.save(PhoneOtp.burned(phoneE164, sha256Hex(generateCode() + UUID.randomUUID()),
                        now.plus(OTP_TTL), purpose));
            });
        } catch (BusinessException | ResendThrottledException e) {
            // A limit already holds the slot — exactly what a real send would have hit. Nothing to store.
        } catch (RuntimeException e) {
            log.warn("OTP slot burn failed for {}: {}", maskPhone(phoneE164), e.getClass().getSimpleName());
        }
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
    private void dispatchCode(String phone, String code, Long otpId, OtpSmsFormat format, PhoneOtpPurpose purpose) {
        try {
            otpSmsSender.send(phone, otpSmsText.render(code, format));
            sentCounters.get(format).increment();
        } catch (RuntimeException e) {
            log.warn("OTP send failed for {}: {}", maskPhone(phone), e.getClass().getSimpleName());
            countSend(purpose, e instanceof SmsDeliveryAmbiguousException
                    ? SendResult.AMBIGUOUS : SendResult.PROVIDER_ERROR);
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
        String clientNet = clientNetwork.current();
        if (dailyCapRefuses(phone, clientNet, purpose, now)) {
            return false;
        }
        return redeem(phoneOtpRepository
                .findTopByPhoneAndPurposeAndUsedFalseAndExpiresAtAfterOrderByIdDesc(phone, purpose, now), code,
                purpose, clientNet);
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
        String clientNet = clientNetwork.current();
        if (dailyCapRefuses(phone, clientNet, purpose, now)) {
            return false;
        }
        return redeem(phoneOtpRepository
                .findTopByPhoneAndPurposeAndUserIdAndUsedFalseAndExpiresAtAfterOrderByIdDesc(
                        phone, purpose, userId, now), code, purpose, clientNet);
    }

    /**
     * B516 (AUTH-D19) — a wrong current password on a {@link PhoneOtpPurpose#PHONE_CHANGE} verify counts as one failed
     * try on the caller's newest live code: the same per-code cap ({@link PhoneOtp#MAX_VERIFY_ATTEMPTS}, then the code
     * is burnt) and the same autocommit recorder as a wrong code, so the count survives the caller's rollback.
     *
     * @return {@code false} when {@code userId} holds no live code for {@code phone} (nothing to count against)
     */
    @Transactional
    public boolean recordFailedAttempt(String phone, PhoneOtpPurpose purpose, UUID userId) {
        requireOwnerMatchesPurpose(purpose, userId);
        Optional<PhoneOtp> active = phoneOtpRepository
                .findTopByPhoneAndPurposeAndUserIdAndUsedFalseAndExpiresAtAfterOrderByIdDesc(
                        phone, purpose, userId, clock.instant());
        active.ifPresent(otp -> {
            if (attemptRecorder.reserveAttempt(otp.getId(), maxVerifyAttempts)) {
                attemptRecorder.recordMismatch(otp.getId(), maxVerifyAttempts, clientNetwork.current());
            }
        });
        return active.isPresent();
    }

    private static void requireOwnerMatchesPurpose(PhoneOtpPurpose purpose, UUID userId) {
        if (purpose.isUserBound() != (userId != null)) {
            throw new IllegalArgumentException("Owner does not match OTP purpose " + purpose);
        }
    }

    /**
     * AUTH-D26 (c) — once {@value #MAX_FAILED_VERIFIES_PER_DAY} wrong codes were typed for the phone from this client
     * network in 24 h, every verify from that network fails before any code row is read or touched (the same generic
     * error as a wrong code). Per (phone, network), user decision 2026-11-19: a stranger cannot lock the owner out.
     */
    private boolean dailyCapRefuses(String phone, String clientNet, PhoneOtpPurpose purpose, Instant now) {
        if (phoneOtpRepository.countFailuresSince(phone, clientNet, now.minus(FAILED_VERIFY_WINDOW))
                < maxFailedVerifiesPerDay) {
            return false;
        }
        countVerify(purpose, VerifyResult.DAILY_CAP);
        return true;
    }

    /**
     * Phone-first hardening B1 — race-free redeem, no {@code FOR UPDATE}:
     * <ol>
     *   <li>reserve one compare with an autocommit UPDATE guarded by {@code attempts < cap} on the dedicated
     *       {@code otpAttemptDataSource} (0 rows → fail, so concurrent verifies can never exceed the per-code cap of
     *       compares; never a second connection of the main pool);</li>
     *   <li>constant-time hash compare;</li>
     *   <li>mismatch → autocommit {@code recordMismatch}: {@code failed_attempts + 1}, burnt at the cap, one row in
     *       {@code phone_otp_failures} for (phone, network) — committed before the caller's generic error rolls its
     *       own transaction back;</li>
     *   <li>match → {@code markUsedIfUnused} in the CALLER's transaction: exactly one concurrent correct verify gets
     *       the row (1 row updated), every other fails.</li>
     * </ol>
     */
    private boolean redeem(Optional<PhoneOtp> active, String code, PhoneOtpPurpose purpose, String clientNet) {
        if (active.isEmpty()) {
            countVerify(purpose, VerifyResult.INVALID);
            return false;
        }
        Long otpId = active.get().getId();
        if (!attemptRecorder.reserveAttempt(otpId, maxVerifyAttempts)) {
            countVerify(purpose, VerifyResult.EXHAUSTED);
            return false;
        }
        byte[] candidate = sha256Hex(code).getBytes(StandardCharsets.UTF_8);
        byte[] stored = active.get().getCodeHash().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(candidate, stored)) {
            attemptRecorder.recordMismatch(otpId, maxVerifyAttempts, clientNet);
            countVerify(purpose, VerifyResult.INVALID);
            return false;
        }
        if (phoneOtpRepository.markUsedIfUnused(otpId) != 1) {
            countVerify(purpose, VerifyResult.INVALID);
            return false;
        }
        countVerify(purpose, VerifyResult.OK);
        return true;
    }

    private void countSend(PhoneOtpPurpose purpose, SendResult result) {
        meterRegistry.counter(SEND_METRIC, "purpose", purposeTag(purpose), "result", result.tag()).increment();
    }

    private void countVerify(PhoneOtpPurpose purpose, VerifyResult result) {
        meterRegistry.counter(VERIFY_METRIC, "purpose", purposeTag(purpose), "result", result.tag()).increment();
    }

    private static String purposeTag(PhoneOtpPurpose purpose) {
        return purpose.name().toLowerCase(Locale.ROOT);
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
        phoneOtpRepository.deleteFailuresByPhone(e164Phone); // V258 failure log stores the phone too
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
