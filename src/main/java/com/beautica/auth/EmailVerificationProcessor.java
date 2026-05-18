package com.beautica.auth;

import com.beautica.auth.dto.VerifyEmailRequest;
import com.beautica.common.exception.VerificationException;
import com.beautica.config.VerificationPolicyConfig;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Owns the locked, transactional core of email verification.
 *
 * <p>Extracted from {@link AuthService} (SRP) for a concrete reason: the
 * critical section must run inside a {@code @Transactional} proxy while token
 * issuance must run AFTER that transaction commits (so the
 * {@code PESSIMISTIC_WRITE} row lock is not held across JWT signing + the
 * refresh-token INSERT). A {@code this.}-call from a non-transactional method
 * to a {@code @Transactional} method in the same bean bypasses the proxy
 * entirely (Anti-Bug Playbook §F3 — self-invocation defeats AOP); a separate
 * bean is the canonical fix.
 *
 * <p>The narrow critical section enforces: cumulative resend-surviving lock,
 * anti-enumeration (unknown / already-verified → generic {@code INVALID_CODE}),
 * expiry, the per-OTP 5-attempt cap, and the constant-time OTP comparison.
 */
@Component
public class EmailVerificationProcessor {

    private static final short MAX_VERIFICATION_ATTEMPTS = 5;

    // Fixed same-length decoy for the unknown-email path so it performs the
    // same hashOtp + MessageDigest.isEqual work a real user would, closing the
    // timing oracle (found vs not-found were otherwise distinguishable).
    private static final String DECOY_HASH = "0".repeat(64);
    private static final String DECOY_OTP = "000000";

    private final UserRepository userRepository;
    private final TokenGenerator tokenGenerator;
    private final Clock clock;
    private final VerificationPolicyConfig verificationPolicy;

    public EmailVerificationProcessor(
            UserRepository userRepository,
            TokenGenerator tokenGenerator,
            Clock clock,
            VerificationPolicyConfig verificationPolicy) {
        this.userRepository = userRepository;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
        this.verificationPolicy = verificationPolicy;
    }

    /**
     * The locked critical section. Returns the id of the now-verified user;
     * the caller issues tokens OUTSIDE this transaction (lock released).
     *
     * <p>{@code noRollbackFor = VerificationException.class} is intentional and
     * load-bearing: the per-OTP attempt increment AND the resend-surviving
     * cumulative-failure increment must commit even when {@code INVALID_CODE}
     * is thrown — that is the anti-brute-force invariant. Do not "fix" it.
     */
    @Transactional(noRollbackFor = VerificationException.class)
    public UUID verifyAndReturnUserId(VerifyEmailRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT).strip();
        // Pessimistic write lock serializes concurrent OTP submissions for the
        // same account, preventing attempt-counter double-spend.
        var userOpt = userRepository.findByEmailForUpdate(email);

        if (userOpt.isEmpty()) {
            // Constant-time decoy: same hashOtp + isEqual work a found user
            // would do, so unknown-email is time-equivalent to wrong-code.
            String decoyIncoming = tokenGenerator.hashOtp(DECOY_OTP);
            MessageDigest.isEqual(
                    decoyIncoming.getBytes(StandardCharsets.UTF_8),
                    DECOY_HASH.getBytes(StandardCharsets.UTF_8));
            throw new VerificationException(VerificationException.Code.INVALID_CODE);
        }

        var user = userOpt.get();

        // Cumulative lock check first. While locked, reject with the generic
        // INVALID_CODE — no distinct status/body/code that would leak lock state.
        if (isLocked(user)) {
            throw new VerificationException(VerificationException.Code.INVALID_CODE);
        }

        // Anti-enumeration — ALREADY_VERIFIED would leak account existence.
        if (user.isEmailVerified()) {
            throw new VerificationException(VerificationException.Code.INVALID_CODE);
        }

        if (user.getVerificationCodeHash() == null || user.getVerificationCodeExpiresAt() == null) {
            throw new VerificationException(VerificationException.Code.CODE_EXPIRED);
        }

        // Phase 1.8 defence-in-depth: clear an expired code so a stale hash
        // cannot survive alongside a freshly-resent one.
        if (user.getVerificationCodeExpiresAt().isBefore(clock.instant())) {
            user.setVerificationCodeHash(null);
            user.setVerificationCodeExpiresAt(null);
            throw new VerificationException(VerificationException.Code.CODE_EXPIRED);
        }

        // Per-OTP attempt cap before the hash comparison. Treat exhausted
        // identically to expiry (CODE_EXPIRED) so the limit is not leaked.
        if (user.getVerificationAttempts() >= MAX_VERIFICATION_ATTEMPTS) {
            throw new VerificationException(VerificationException.Code.CODE_EXPIRED);
        }
        user.setVerificationAttempts((short) (user.getVerificationAttempts() + 1));

        String incomingHash = tokenGenerator.hashOtp(request.code());
        boolean match = MessageDigest.isEqual(
                incomingHash.getBytes(StandardCharsets.UTF_8),
                user.getVerificationCodeHash().getBytes(StandardCharsets.UTF_8));

        if (!match) {
            // Resend-surviving lifetime counter + lock trip. Both this and the
            // attempt increment commit (noRollbackFor) so a parallel request
            // cannot double-spend and resend cannot rewind progress.
            registerCumulativeFailure(user);
            // Managed entity flushes once at commit — no explicit save()
            // (collapses the former double-save).
            throw new VerificationException(VerificationException.Code.INVALID_CODE);
        }

        user.setEmailVerified(true);
        user.setVerificationCodeHash(null);
        user.setVerificationCodeExpiresAt(null);
        user.setVerificationAttempts((short) 0);
        user.setVerificationFailedTotal((short) 0);
        user.setVerificationLockedUntil(null);
        // Single flush at commit — collapses the former success-path double save().
        return user.getId();
    }

    /**
     * @return {@code true} when {@code user} is inside an active cumulative
     *         lockout window. Shared with the resend path so a locked account
     *         cannot rotate its OTP either.
     */
    public boolean isLocked(User user) {
        Instant lockedUntil = user.getVerificationLockedUntil();
        return lockedUntil != null && clock.instant().isBefore(lockedUntil);
    }

    private void registerCumulativeFailure(User user) {
        int total = user.getVerificationFailedTotal() + 1;
        user.setVerificationFailedTotal((short) Math.min(total, Short.MAX_VALUE));
        if (total >= verificationPolicy.cumulativeFailureThreshold()) {
            user.setVerificationLockedUntil(
                    clock.instant().plus(verificationPolicy.lockoutDuration()));
        }
    }
}
