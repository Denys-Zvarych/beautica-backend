package com.beautica.auth;

import com.beautica.auth.dto.VerifyPasswordResetOtpRequest;
import com.beautica.common.exception.VerificationException;
import com.beautica.config.PasswordResetOtpPolicyConfig;
import com.beautica.user.PasswordResetTicket;
import com.beautica.user.PasswordResetTicketRepository;
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
 * Owns the locked, transactional core of password-reset OTP verification.
 *
 * <p>Structurally mirrors {@link EmailVerificationProcessor}: a pessimistic-write row lock
 * serializes concurrent OTP submissions for the same account, a per-code attempt cap and a
 * resend-surviving cumulative lockout bound brute-force, and the OTP hash comparison is
 * constant-time. Reuses {@link TokenGenerator#hashOtp} / {@link MessageDigest#isEqual} exactly
 * as {@link EmailVerificationProcessor} does.
 *
 * <p><strong>Deliberate divergence from {@code EmailVerificationProcessor}:</strong> that
 * processor defers JWT minting + the refresh-token INSERT to {@code AuthService} so the
 * {@code PESSIMISTIC_WRITE} lock is not held across JWT signing. Minting a
 * {@link PasswordResetTicket} is comparatively cheap — a random-byte generation, a SHA-256
 * hash, and a single INSERT, no signing — so this processor mints the ticket INSIDE the same
 * locked critical section that clears the OTP columns. This keeps "verify + clear + mint" a
 * single atomic unit and leaves {@code PasswordResetService.verifyOtp} a thin delegate.
 *
 * <p>The narrow critical section enforces: cumulative resend-surviving lock, anti-enumeration
 * (unknown account → generic {@code INVALID_CODE}), expiry, the per-code attempt cap, and the
 * constant-time OTP comparison.
 *
 * <p><strong>Timing-oracle defense scope:</strong> every branch that rejects without a real
 * OTP comparison — unknown email, locked account, no active code, expired code, and exhausted
 * attempt cap — pays the same decoy {@link #decoyCompare()} hashOtp+isEqual cost as a genuine
 * wrong-code attempt. Without this, "known account, no matchable code right now" would answer
 * measurably faster than "unknown email" or "wrong code", letting a caller learn an email
 * belongs to a real account purely from response latency, without ever calling
 * {@code /forgot-password} first. The returned {@link VerificationException.Code} may still
 * legitimately differ between these branches (that is a UX need, not a leak on its own) — only
 * the TIMING/WORK must be uniform.
 */
@Component
public class PasswordResetOtpProcessor {

    // Fixed same-length decoy for the unknown-email path so it performs the same hashOtp +
    // MessageDigest.isEqual work a real user would, closing the timing oracle (found vs
    // not-found were otherwise distinguishable). Mirrors EmailVerificationProcessor exactly.
    private static final String DECOY_HASH = "0".repeat(64);
    private static final String DECOY_OTP = "000000";

    private final UserRepository userRepository;
    private final PasswordResetTicketRepository passwordResetTicketRepository;
    private final TokenGenerator tokenGenerator;
    private final Clock clock;
    private final PasswordResetOtpPolicyConfig passwordResetOtpPolicy;

    public PasswordResetOtpProcessor(
            UserRepository userRepository,
            PasswordResetTicketRepository passwordResetTicketRepository,
            TokenGenerator tokenGenerator,
            Clock clock,
            PasswordResetOtpPolicyConfig passwordResetOtpPolicy) {
        this.userRepository = userRepository;
        this.passwordResetTicketRepository = passwordResetTicketRepository;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
        this.passwordResetOtpPolicy = passwordResetOtpPolicy;
    }

    /**
     * The locked critical section. Validates the submitted OTP and, on success, mints and
     * returns the RAW single-use reset ticket (the client submits this as
     * {@code ResetPasswordRequest.resetTicket}).
     *
     * <p>{@code noRollbackFor = VerificationException.class} is intentional and load-bearing:
     * the per-code attempt increment AND the resend-surviving cumulative-failure increment
     * must commit even when {@code INVALID_CODE} is thrown — that is the anti-brute-force
     * invariant. Do not "fix" it.
     */
    @Transactional(noRollbackFor = VerificationException.class)
    public String verifyAndIssueTicket(VerifyPasswordResetOtpRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT).strip();
        // Pessimistic write lock serializes concurrent OTP submissions for the same
        // account, preventing attempt-counter double-spend.
        var userOpt = userRepository.findByEmailForUpdate(email);

        if (userOpt.isEmpty()) {
            // Constant-time decoy: same hashOtp + isEqual work a found user would do, so
            // unknown-email is time-equivalent to wrong-code.
            decoyCompare();
            throw new VerificationException(VerificationException.Code.INVALID_CODE);
        }

        var user = userOpt.get();

        // Cumulative lock check first. While locked, reject with the generic INVALID_CODE —
        // no distinct status/body/code that would leak lock state. Timing-oracle defense:
        // perform the same hashOtp + isEqual work an unlocked wrong-code attempt would do.
        if (isLocked(user)) {
            decoyCompare();
            throw new VerificationException(VerificationException.Code.INVALID_CODE);
        }

        if (user.getPasswordResetCodeHash() == null || user.getPasswordResetCodeExpiresAt() == null) {
            // Timing-oracle defense: without this, "known account with no active code" would
            // return CODE_EXPIRED noticeably FASTER than the unknown-email/wrong-code
            // branches (both of which pay the decoy/real hashOtp+isEqual cost above), letting
            // a caller distinguish "this email belongs to a real account" purely from response
            // latency — without ever calling /forgot-password first. Paying the same decoy
            // work here closes that gap; the returned Code can still legitimately differ
            // (CODE_EXPIRED vs INVALID_CODE) but the cost profile must not.
            decoyCompare();
            throw new VerificationException(VerificationException.Code.CODE_EXPIRED);
        }

        // Defence-in-depth: clear an expired code so a stale hash cannot survive alongside
        // a freshly-issued one.
        if (user.getPasswordResetCodeExpiresAt().isBefore(clock.instant())) {
            user.setPasswordResetCodeHash(null);
            user.setPasswordResetCodeExpiresAt(null);
            // Same timing-oracle defense as the null-hash branch above — an expired code must
            // cost the same as every other "no match" outcome.
            decoyCompare();
            throw new VerificationException(VerificationException.Code.CODE_EXPIRED);
        }

        // Per-code attempt cap before the hash comparison. Treat exhausted identically to
        // expiry (CODE_EXPIRED) so the limit is not leaked.
        if (user.getPasswordResetAttempts() >= passwordResetOtpPolicy.maxAttemptsPerCode()) {
            // Same timing-oracle defense — an exhausted attempt cap must not be distinguishable
            // by latency from any other CODE_EXPIRED/INVALID_CODE outcome either.
            decoyCompare();
            throw new VerificationException(VerificationException.Code.CODE_EXPIRED);
        }
        user.setPasswordResetAttempts((short) (user.getPasswordResetAttempts() + 1));

        String incomingHash = tokenGenerator.hashOtp(request.code());
        boolean match = MessageDigest.isEqual(
                incomingHash.getBytes(StandardCharsets.UTF_8),
                user.getPasswordResetCodeHash().getBytes(StandardCharsets.UTF_8));

        if (!match) {
            // Resend-surviving lifetime counter + lock trip. Both this and the attempt
            // increment commit (noRollbackFor) so a parallel request cannot double-spend.
            registerCumulativeFailure(user);
            throw new VerificationException(VerificationException.Code.INVALID_CODE);
        }

        // Success: clear every OTP column, then mint the reset ticket in the same
        // transaction (see class Javadoc for why, unlike EmailVerificationProcessor).
        user.setPasswordResetCodeHash(null);
        user.setPasswordResetCodeExpiresAt(null);
        user.setPasswordResetAttempts((short) 0);
        user.setPasswordResetFailedTotal((short) 0);
        user.setPasswordResetLockedUntil(null);

        return mintTicket(user.getId());
    }

    /**
     * @return {@code true} when {@code user} is inside an active cumulative lockout window.
     *         Shared with {@code PasswordResetService.mintAndSendOtp} so a locked account
     *         cannot be issued a fresh OTP either.
     */
    public boolean isLocked(User user) {
        Instant lockedUntil = user.getPasswordResetLockedUntil();
        return lockedUntil != null && clock.instant().isBefore(lockedUntil);
    }

    private void decoyCompare() {
        String decoyIncoming = tokenGenerator.hashOtp(DECOY_OTP);
        MessageDigest.isEqual(
                decoyIncoming.getBytes(StandardCharsets.UTF_8),
                DECOY_HASH.getBytes(StandardCharsets.UTF_8));
    }

    private void registerCumulativeFailure(User user) {
        int total = user.getPasswordResetFailedTotal() + 1;
        user.setPasswordResetFailedTotal((short) Math.min(total, Short.MAX_VALUE));
        if (total >= passwordResetOtpPolicy.cumulativeFailureThreshold()) {
            user.setPasswordResetLockedUntil(
                    clock.instant().plus(passwordResetOtpPolicy.lockoutDuration()));
        }
    }

    /**
     * Mints the single-use reset ticket: supersedes any outstanding unused ticket for the
     * user (at most one live ticket at a time), then persists the new hashed ticket.
     *
     * @return the RAW ticket — never persisted, handed to the caller exactly once.
     */
    private String mintTicket(UUID userId) {
        passwordResetTicketRepository.markAllUsedByUserId(userId);

        String rawTicket = tokenGenerator.generateToken();
        String hashedTicket = tokenGenerator.hash(rawTicket);
        Instant expiresAt = clock.instant().plus(passwordResetOtpPolicy.ticketTtl());

        passwordResetTicketRepository.save(new PasswordResetTicket(hashedTicket, userId, expiresAt));

        return rawTicket;
    }
}
