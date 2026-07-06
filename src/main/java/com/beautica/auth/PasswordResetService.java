package com.beautica.auth;

import com.beautica.auth.dto.ForgotPasswordRequest;
import com.beautica.auth.dto.ResetPasswordRequest;
import com.beautica.auth.dto.VerifyPasswordResetOtpRequest;
import com.beautica.auth.dto.VerifyPasswordResetOtpResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.config.PasswordResetOtpPolicyConfig;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.PasswordResetTicket;
import com.beautica.user.PasswordResetTicketRepository;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles the password-reset OTP flow (Phase A3 — replaces the old email-link flow):
 * <ol>
 *   <li>{@link #requestReset} / {@link #requestResetForUserId} — both mint and email a
 *       6-digit OTP via the shared {@link #mintAndSendOtp} helper; the former is the
 *       anti-enumeration public entry point ({@code POST /auth/forgot-password}), the
 *       latter is the authenticated change-password-from-settings entry point
 *       ({@code POST /users/me/change-password/request-otp}).</li>
 *   <li>{@link #verifyOtp} — validates the submitted OTP via {@link PasswordResetOtpProcessor}
 *       and returns the freshly-minted {@code resetTicket}.</li>
 *   <li>{@link #resetPassword} — validates the raw ticket under a pessimistic lock, updates
 *       the password hash, and invalidates all existing sessions.</li>
 * </ol>
 *
 * <p><strong>Enumeration protection:</strong> {@code requestReset} always returns normally
 * (no exception, same log footprint) whether the email is unknown, unverified, or inactive.
 * Only the verified+active branch actually mints an OTP and enqueues mail.
 *
 * <p><strong>Oracle protection:</strong> {@code resetPassword} surfaces invalid, used, and
 * expired tickets as a single identical generic 400 — callers cannot distinguish the three
 * states. {@code verifyOtp} delegates its own oracle protection entirely to
 * {@link PasswordResetOtpProcessor}.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private static final String GENERIC_RESET_ERROR = "Invalid or expired reset token";

    private final PasswordResetTicketRepository passwordResetTicketRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenGenerator tokenGenerator;
    private final PasswordEncoder passwordEncoder;
    private final EmailNotificationService emailNotificationService;
    private final TaskExecutor emailExecutor;
    private final TokensValidAfterCache tokensValidAfterCache;
    private final PasswordResetOtpProcessor passwordResetOtpProcessor;
    private final PasswordResetOtpPolicyConfig passwordResetOtpPolicy;
    private final Clock clock;

    public PasswordResetService(
            PasswordResetTicketRepository passwordResetTicketRepository,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            TokenGenerator tokenGenerator,
            PasswordEncoder passwordEncoder,
            EmailNotificationService emailNotificationService,
            @Qualifier("emailExecutor") TaskExecutor emailExecutor,
            TokensValidAfterCache tokensValidAfterCache,
            PasswordResetOtpProcessor passwordResetOtpProcessor,
            PasswordResetOtpPolicyConfig passwordResetOtpPolicy,
            Clock clock
    ) {
        this.passwordResetTicketRepository = passwordResetTicketRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenGenerator = tokenGenerator;
        this.passwordEncoder = passwordEncoder;
        this.emailNotificationService = emailNotificationService;
        this.emailExecutor = emailExecutor;
        this.tokensValidAfterCache = tokensValidAfterCache;
        this.passwordResetOtpProcessor = passwordResetOtpProcessor;
        this.passwordResetOtpPolicy = passwordResetOtpPolicy;
        this.clock = clock;
    }

    /**
     * Initiates a password-reset request from an unauthenticated caller (the caller holds no
     * JWT — that is why they are resetting the password).
     *
     * <p>Enumeration protection: this method always returns normally regardless of whether
     * the email maps to a known, verified, or active account. Only verified+active users
     * actually receive an OTP email — the real OTP mint/send/cooldown logic lives in the
     * shared {@link #mintAndSendOtp} helper so this entry point and
     * {@link #requestResetForUserId} share it with zero duplication.
     *
     * @param request validated request DTO carrying the raw email address
     */
    @Transactional
    public void requestReset(ForgotPasswordRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT).strip();

        // Non-locking pre-read — unknown / inactive / unverified take NO row lock.
        var preReadOpt = userRepository.findByEmail(email);
        if (preReadOpt.isEmpty() || !preReadOpt.get().isActive() || !preReadOpt.get().isEmailVerified()) {
            performDecoyWork(email);
            return;
        }

        // Real write path — escalate to the pessimistic lock now (closes the TOCTOU window
        // between the cooldown read inside mintAndSendOtp and the OTP write), mirroring
        // AuthService.resendVerification.
        var user = userRepository.findByEmailForUpdate(email).orElse(null);
        if (user == null || !user.isActive() || !user.isEmailVerified()) {
            performDecoyWork(email);
            return;
        }

        mintAndSendOtp(user, true);
    }

    /**
     * Initiates a password-reset request for an already-authenticated caller (the
     * "change password from settings" entry point). No anti-enumeration is needed — the
     * caller is identified by their own JWT-derived {@code userId}, not an email they typed —
     * so this delegates directly to the same {@link #mintAndSendOtp} helper used by
     * {@link #requestReset}. Unlike {@link #requestReset}, the resend cooldown throttle IS
     * surfaced as a 429 here — see {@link #mintAndSendOtp} for why the two entry points
     * diverge on that one behavior.
     *
     * @param userId the authenticated caller's id (from {@code AuthenticationUtils.userId})
     * @throws NotFoundException if the user row is somehow missing (defensive only — the
     *                           caller already holds a valid JWT for this id)
     */
    @Transactional
    public void requestResetForUserId(UUID userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        mintAndSendOtp(user, false);
    }

    /**
     * Verifies the submitted OTP and, on success, returns the freshly-minted reset ticket.
     *
     * <p>Delegates entirely to {@link PasswordResetOtpProcessor#verifyAndIssueTicket}, which
     * owns the locked critical section (attempt cap, cumulative lockout, constant-time
     * compare) and mints the ticket in the same transaction. Mirrors
     * {@code AuthService.verifyEmail}'s thin-delegate shape.
     */
    public VerifyPasswordResetOtpResponse verifyOtp(VerifyPasswordResetOtpRequest request) {
        String rawTicket = passwordResetOtpProcessor.verifyAndIssueTicket(request);
        return new VerifyPasswordResetOtpResponse(rawTicket);
    }

    /**
     * Completes a password reset given the raw ticket minted by {@link #verifyOtp}.
     *
     * <p>Oracle protection: invalid, used, and expired tickets all throw a
     * {@link BusinessException} with the same message and HTTP status so callers cannot
     * probe which state was reached.
     *
     * <p>On success: the user's password hash is updated, the consumed ticket is marked used,
     * every other outstanding reset ticket for the user is invalidated, every existing refresh
     * token (i.e., all sessions) is revoked, AND {@code tokensValidAfter} is stamped so any
     * already-issued access token also stops working (see {@link TokensValidAfterCache} /
     * {@link JwtAuthenticationFilter}) — otherwise a stolen access token would remain usable
     * for its remaining TTL despite the reset. No auth tokens are returned — the client must
     * route to the login screen.
     *
     * <p>This unconditionally revokes every refresh token for the user via
     * {@code refreshTokenRepository.deleteByUserId} — including the session of the caller
     * themselves when this was reached via the authenticated change-password-from-settings
     * entry point ({@link #requestResetForUserId}). That is intentional: a password change
     * forces every session (including the one that just changed it) to re-authenticate.
     *
     * @param request validated DTO carrying the raw ticket from {@link #verifyOtp} and the
     *                desired new password
     * @throws BusinessException (400) for invalid / used / expired ticket
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String hashedTicket = tokenGenerator.hash(request.resetTicket());

        // Compute the new password BCrypt hash BEFORE looking up the ticket / acquiring the
        // row lock — and unconditionally, even though an invalid/unknown ticket will discard
        // it. Two deliberate reasons, both intentional trade-offs, NOT oversights:
        //
        // 1. Concurrency: BCrypt is pure CPU work (~80-150 ms) that needs no lock; running it
        //    inside the lock would lengthen the PESSIMISTIC_WRITE hold under concurrent
        //    same-ticket submits. Paying it up front shrinks the lock window to fast DB
        //    round-trips only.
        // 2. Anti-timing-oracle: paying the ~80-150 ms BCrypt cost on EVERY call — valid
        //    ticket or not — means an invalid/unknown/used/expired ticket takes the same
        //    wall-clock time as a successful reset. Skipping the encode for invalid tickets
        //    would make this endpoint's response latency a side channel that leaks "does this
        //    ticket exist" independently of the byte-identical 400 body already enforced
        //    below. The wasted CPU on invalid tickets is the accepted cost of closing that
        //    channel. This is mitigated (not eliminated) by the per-IP Bucket4j rate limit on
        //    `/auth/*` bounding how many timing samples an attacker can collect — it is a
        //    monitoring item, not a defect; do not remove or make this conditional on ticket
        //    validity.
        String newPasswordHash = passwordEncoder.encode(request.newPassword());

        PasswordResetTicket ticket = passwordResetTicketRepository
                .findByTicketHashForUpdate(hashedTicket)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, GENERIC_RESET_ERROR));

        if (ticket.isUsed() || ticket.getExpiresAt().isBefore(clock.instant())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, GENERIC_RESET_ERROR);
        }

        // FK integrity guarantees this user exists, but if somehow it does not,
        // surface the same generic error — no oracle distinguishing the FK case.
        User user = userRepository.findById(ticket.getUserId())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, GENERIC_RESET_ERROR));

        // Both `user` and `ticket` are managed entities loaded within this @Transactional
        // boundary, so Hibernate dirty-checking flushes these mutations on commit — no
        // explicit save() call is required (it would be a redundant no-op).
        user.setPasswordHash(newPasswordHash);

        // Revokes every access token issued before this instant (checked by
        // JwtAuthenticationFilter via TokensValidAfterCache). Without this, an attacker
        // holding a stolen access token kept full API access for up to its remaining TTL
        // even after this reset — refresh-token revocation alone (below) only stops NEW
        // access tokens from being minted, it does not invalidate ones already issued.
        user.setTokensValidAfter(clock.instant());

        // Primary single-use enforcement: flip the just-validated, pessimistically-locked
        // row directly. This is the authoritative consume — kept independent of the bulk
        // sweep below, which is defence-in-depth only.
        ticket.markUsed();

        // Defence in depth: invalidate every other outstanding reset ticket for the user,
        // then terminate all existing sessions (global logout).
        passwordResetTicketRepository.markAllUsedByUserId(user.getId());
        refreshTokenRepository.deleteByUserId(user.getId());

        // Evict the cached tokensValidAfter only AFTER this transaction commits — NOT
        // synchronously here. TokensValidAfterCache#get() is a read-through cache backed by
        // the DB row this method is about to update: under READ COMMITTED, a concurrent
        // request that misses the cache in the window between an inline invalidate() and
        // the actual COMMIT would read the still-uncommitted (stale, pre-reset) DB value and
        // cache that stale "no reset happened" answer for the full TTL — reopening the exact
        // stolen-access-token window this feature exists to close. Deferring to afterCommit
        // closes that race: by the time anyone can observe an evicted cache, the fresh value
        // is already committed and visible.
        evictTokensValidAfterCache(user.getId());

        log.info("Password reset completed: userId={}", user.getId());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Generates, hashes, and stores a fresh 6-digit OTP on {@code user}, then schedules the
     * OTP email — the single write path shared by {@link #requestReset} and
     * {@link #requestResetForUserId} (zero duplication between the two entry points).
     *
     * <p>Caller MUST supply a {@code user} entity obtained under a {@code PESSIMISTIC_WRITE}
     * lock (see the call sites) so the cooldown check below cannot race a concurrent request
     * for the same account.
     *
     * <p>Anti-brute-force: a locked account (resend-surviving cumulative lockout, shared with
     * {@link PasswordResetOtpProcessor}) is silently skipped — no OTP is (re)issued and no
     * exception is thrown, so a locked account's state never leaks via this entry point
     * either. An account that is NOT locked but within the per-account resend cooldown
     * normally throws {@link ResendThrottledException} (429), mirroring
     * {@code AuthService.resendVerification}'s throttle derivation exactly: {@code issuedAt}
     * is derived as {@code passwordResetCodeExpiresAt - otpTtl} (no dedicated
     * {@code sent_at} column) — UNLESS {@code suppressCooldownThrottle} is {@code true}.
     *
     * <p><strong>Why the flag exists:</strong> {@link #requestReset} (the unauthenticated,
     * anti-enumeration {@code POST /auth/forgot-password} entry point) always returns the
     * same 200 for unknown / inactive / unverified / locked accounts. If it also let a 429
     * escape for "real, verified, active, unlocked account with a still-live code", an
     * attacker could call it twice in a row for a candidate email and learn "account exists
     * and is fully eligible" from the second response's status code alone — an
     * account-existence oracle on the one endpoint specifically designed not to have one. So
     * {@code requestReset} passes {@code suppressCooldownThrottle = true}: within the
     * cooldown, this method silently no-ops (the already-issued, still-live OTP remains
     * valid, so no legitimate flow is broken) instead of throwing, and the caller always sees
     * the same 200. {@link #requestResetForUserId} (the authenticated
     * change-password-from-settings entry point) passes {@code false} — there is no
     * enumeration risk there (the caller is identified by their own JWT), and telling the
     * legitimate authenticated user to wait via 429 is a real UX benefit worth keeping.
     *
     * @param suppressCooldownThrottle {@code true} to silently no-op within the cooldown
     *                                 instead of throwing (the anti-enumeration entry point);
     *                                 {@code false} to throw {@link ResendThrottledException}
     *                                 as before (the authenticated entry point)
     */
    private void mintAndSendOtp(User user, boolean suppressCooldownThrottle) {
        if (passwordResetOtpProcessor.isLocked(user)) {
            return;
        }

        if (user.getPasswordResetCodeExpiresAt() != null) {
            Instant issuedAt = user.getPasswordResetCodeExpiresAt().minus(passwordResetOtpPolicy.otpTtl());
            Instant nextAllowed = issuedAt.plus(passwordResetOtpPolicy.resendCooldown());
            if (clock.instant().isBefore(nextAllowed)) {
                if (suppressCooldownThrottle) {
                    return;
                }
                long retryAfter = Duration.between(clock.instant(), nextAllowed).getSeconds() + 1;
                throw new ResendThrottledException(retryAfter);
            }
        }

        String rawOtp = tokenGenerator.generateOtp();
        user.setPasswordResetCodeHash(tokenGenerator.hashOtp(rawOtp));
        user.setPasswordResetCodeExpiresAt(clock.instant().plus(passwordResetOtpPolicy.otpTtl()));
        // Reset the per-code attempt window only. passwordResetFailedTotal is deliberately
        // NOT reset here — that is the resend-surviving cumulative bound.
        user.setPasswordResetAttempts((short) 0);

        scheduleResetOtpEmail(user, rawOtp);
    }

    /**
     * Performs throwaway work on the no-op (unknown / inactive / unverified) branch so the
     * per-request cost tracks the eligible path, which escalates to a second
     * {@code SELECT ... FOR UPDATE} lookup and generates+hashes a real OTP.
     *
     * <p>This is <em>best-effort latency narrowing, not perfect symmetry</em>. The primary
     * enumeration defenses remain the byte-identical generic 200 response and the per-IP rate
     * limit on {@code /auth/*}; this method only shrinks the residual timing side-channel.
     */
    private void performDecoyWork(String email) {
        // Decoy crypto cost mirroring mintAndSendOtp's generateOtp + hashOtp.
        tokenGenerator.hashOtp(tokenGenerator.generateOtp());
        // Decoy DB round-trip mirroring the eligible path's lock-escalation lookup — using the
        // NON-locking findByEmail, deliberately NOT findByEmailForUpdate. This branch is
        // reached for both a genuinely unknown email (no row exists to lock) AND a
        // known-but-ineligible (inactive/unverified) email (a real row DOES exist). Taking a
        // real PESSIMISTIC_WRITE lock in the latter case would serialize against any
        // concurrent legitimate write to that account for no benefit — the Optional result is
        // discarded either way, so no behavior depends on which finder is used, only lock
        // semantics. findByEmail's plain SELECT preserves the DB-round-trip timing mirror
        // without paying that concurrency cost.
        userRepository.findByEmail(email);
    }

    /**
     * Schedules the password-reset OTP email to be dispatched after the current transaction
     * commits. Mirrors {@code AuthService.scheduleVerificationEmail}: when no active
     * transaction synchronization exists (e.g. in unit tests where the {@code @Transactional}
     * proxy is bypassed), the email is sent immediately on the calling thread so tests can
     * verify the call without standing up a transaction manager.
     */
    private void scheduleResetOtpEmail(User user, String rawOtp) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            emailExecutor.execute(() ->
                                    emailNotificationService.sendPasswordResetOtpEmail(user, rawOtp));
                        }
                    }
            );
        } else {
            // No active transaction (unit-test path) — call directly on the calling thread.
            emailExecutor.execute(() ->
                    emailNotificationService.sendPasswordResetOtpEmail(user, rawOtp));
        }
    }

    /**
     * Evicts {@code userId}'s entry from {@link TokensValidAfterCache} after the current
     * transaction commits, never before.
     *
     * <p>See the call site in {@link #resetPassword} for why {@code afterCommit} — rather
     * than a synchronous inline call — is required here: this cache read-through hits the
     * DB row this method just updated, so an eviction that fires before commit reopens a
     * stale-read race window.
     */
    private void evictTokensValidAfterCache(UUID userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            tokensValidAfterCache.invalidate(userId);
                        }
                    }
            );
        } else {
            // No active transaction (unit-test path) — call directly on the calling thread.
            tokensValidAfterCache.invalidate(userId);
        }
    }
}
