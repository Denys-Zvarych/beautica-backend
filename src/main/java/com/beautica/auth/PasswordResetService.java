package com.beautica.auth;

import com.beautica.auth.dto.ForgotPasswordRequest;
import com.beautica.auth.dto.ResetPasswordRequest;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.util.SchemeGuard;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.PasswordResetToken;
import com.beautica.user.PasswordResetTokenRepository;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles the two-step password-reset flow:
 * <ol>
 *   <li>{@link #requestReset} — mints a hashed single-use token and schedules a reset email.</li>
 *   <li>{@link #resetPassword} — validates the raw token under a pessimistic lock, updates the
 *       password hash, and invalidates all existing sessions.</li>
 * </ol>
 *
 * <p><strong>Enumeration protection:</strong> {@code requestReset} always returns normally
 * (no exception, same log footprint) whether the email is unknown, unverified, or valid.
 * Only the verified+active branch actually persists a token and enqueues mail.
 *
 * <p><strong>Oracle protection:</strong> {@code resetPassword} surfaces invalid, used, and
 * expired tokens as a single identical generic 400 — callers cannot distinguish the three states.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private static final String GENERIC_RESET_ERROR = "Invalid or expired reset token";
    private static final String RESET_PATH = "/reset-password?token=";

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenGenerator tokenGenerator;
    private final PasswordEncoder passwordEncoder;
    private final EmailNotificationService emailNotificationService;
    private final TaskExecutor emailExecutor;
    private final TokensValidAfterCache tokensValidAfterCache;
    private final Clock clock;
    private final String frontendBaseUrl;
    private final long tokenExpirationHours;

    public PasswordResetService(
            PasswordResetTokenRepository passwordResetTokenRepository,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            TokenGenerator tokenGenerator,
            PasswordEncoder passwordEncoder,
            EmailNotificationService emailNotificationService,
            @Qualifier("emailExecutor") TaskExecutor emailExecutor,
            TokensValidAfterCache tokensValidAfterCache,
            Clock clock,
            @Value("${app.frontend.base-url}") String frontendBaseUrl,
            @Value("${app.password-reset.token-expiration-hours:1}") long tokenExpirationHours
    ) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenGenerator = tokenGenerator;
        this.passwordEncoder = passwordEncoder;
        this.emailNotificationService = emailNotificationService;
        this.emailExecutor = emailExecutor;
        this.tokensValidAfterCache = tokensValidAfterCache;
        this.clock = clock;
        this.frontendBaseUrl = frontendBaseUrl;
        this.tokenExpirationHours = tokenExpirationHours;
    }

    /**
     * Initiates a password-reset request.
     *
     * <p>Enumeration protection: this method always returns normally regardless of
     * whether the email maps to a known, verified, or active account. Only verified+active
     * users actually receive a reset email.
     *
     * <p>Before issuing a new token, any existing unused reset tokens for the user are
     * superseded (marked used) so at most one live reset link exists at any time.
     *
     * @param request validated request DTO carrying the raw email address
     */
    @Transactional
    public void requestReset(ForgotPasswordRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT).strip();

        // Enumeration protection: unknown / unverified / inactive → silent no-op.
        // No exception, no log line that references the email or the outcome.
        var userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            performDecoyWork();
            return;
        }
        User user = userOpt.get();
        if (!user.isActive() || !user.isEmailVerified()) {
            performDecoyWork();
            return;
        }

        // Supersede any outstanding unused reset links for this user.
        passwordResetTokenRepository.markAllUsedByUserId(user.getId());

        String rawToken = tokenGenerator.generateToken();
        String hashedToken = tokenGenerator.hash(rawToken);
        Instant expiresAt = clock.instant().plus(tokenExpirationHours, ChronoUnit.HOURS);

        passwordResetTokenRepository.save(new PasswordResetToken(hashedToken, user.getId(), expiresAt));

        String resetLink = buildResetLink(rawToken);
        scheduleResetEmail(email, resetLink);
    }

    /**
     * Completes a password reset given a raw token from the emailed link.
     *
     * <p>Oracle protection: invalid, used, and expired tokens all throw a
     * {@link BusinessException} with the same message and HTTP status so callers
     * cannot probe which state was reached.
     *
     * <p>On success: the user's password hash is updated, the consumed token is marked
     * used, every other outstanding reset token for the user is invalidated, every
     * existing refresh token (i.e., all sessions) is revoked, AND {@code tokensValidAfter}
     * is stamped so any already-issued access token also stops working (see
     * {@link TokensValidAfterCache} / {@link JwtAuthenticationFilter}) — otherwise a
     * stolen access token would remain usable for its remaining TTL despite the reset.
     * No auth tokens are returned — the client must route to the login screen.
     *
     * @param request validated DTO carrying the raw token from the email link and the desired new password
     * @throws BusinessException (400) for invalid / used / expired token
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String hashedToken = tokenGenerator.hash(request.token());

        // Compute the new password BCrypt hash BEFORE acquiring the row lock. BCrypt is
        // pure CPU work (~80-150 ms) that needs no lock; running it inside the lock would
        // lengthen the PESSIMISTIC_WRITE hold under concurrent same-token submits. This
        // shrinks the lock window to fast DB round-trips only. Trade-off: the hash is now
        // computed even for invalid tokens (minor wasted CPU) — acceptable, and it evens
        // out per-request timing as an anti-oracle bonus.
        String newPasswordHash = passwordEncoder.encode(request.newPassword());

        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenForUpdate(hashedToken)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, GENERIC_RESET_ERROR));

        if (token.isUsed() || token.getExpiresAt().isBefore(clock.instant())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, GENERIC_RESET_ERROR);
        }

        // FK integrity guarantees this user exists, but if somehow it does not,
        // surface the same generic error — no oracle distinguishing the FK case.
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, GENERIC_RESET_ERROR));

        // Both `user` and `token` are managed entities loaded within this @Transactional
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
        token.markUsed();

        // Defence in depth: invalidate every other outstanding reset token for the user,
        // then terminate all existing sessions (global logout).
        passwordResetTokenRepository.markAllUsedByUserId(user.getId());
        refreshTokenRepository.deleteByUserId(user.getId());

        // Evict the cached tokensValidAfter only AFTER this transaction commits — NOT
        // synchronously here. TokensValidAfterCache#get() is a read-through cache backed by
        // the DB row this method is about to update: under READ COMMITTED, a concurrent
        // request that misses the cache in the window between an inline invalidate() and
        // the actual COMMIT would read the still-uncommitted (stale, pre-reset) DB value and
        // cache that stale "no reset happened" answer for the full TTL — reopening the exact
        // stolen-access-token window this feature exists to close. Deferring to afterCommit
        // closes that race: by the time anyone can observe an evicted cache, the fresh value
        // is already committed and visible. (This is why the AccessTokenDenylist.revoke
        // analogy doesn't apply here: that denylist has no DB read-through to race against,
        // so synchronous eviction is safe there but not for this cache.)
        evictTokensValidAfterCache(user.getId());

        log.info("Password reset completed: userId={}", user.getId());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Performs throwaway work on the no-op (unknown / unverified / inactive) branch so the
     * per-request cost better tracks the verified-account path, which mints + hashes a real
     * token and performs an UPDATE + INSERT against {@code password_reset_tokens}.
     *
     * <p>This is <em>best-effort latency narrowing, not perfect symmetry</em>: the decoy runs
     * the same token hash plus a single read-only DB round-trip against the same table, whereas
     * the real path issues writes (which are typically costlier than the read). The discarded
     * results are intentional — the cost, not the value, is what matters.
     *
     * <p>The primary enumeration defenses remain the byte-identical generic 200 response (the
     * HTTP body is the same across all branches) and the per-IP rate limit on {@code /auth/*};
     * this method only shrinks the residual timing side-channel. (The email-bounce channel is
     * inherent and accepted.)
     */
    private void performDecoyWork() {
        String decoyToken = tokenGenerator.generateToken();
        String decoyHash = tokenGenerator.hash(decoyToken);
        // INTENTIONAL anti-timing-oracle DB round-trip — DO NOT remove or "optimize away".
        // The real path hits the DB (markAllUsedByUserId UPDATE + save INSERT); without a
        // representative DB read here this no-op branch would only burn CPU on the hash above,
        // letting response latency distinguish a phantom account from an eligible one. This is
        // a read-only probe by the (essentially never-matching) hashed token: no mutation, the
        // Optional result is deliberately discarded, and the response stays an identical 200.
        passwordResetTokenRepository.findByToken(decoyHash);
    }

    /**
     * Builds the absolute reset URL that will appear in the email.
     *
     * <p>The path {@code /reset-password?token=<rawToken>} is the mobile + web deep-link
     * contract. Do not change the query-param name without versioning the API.
     */
    private String buildResetLink(String rawToken) {
        if (!SchemeGuard.isAllowedScheme(frontendBaseUrl)) {
            // Configuration bug — do not swallow; surface loudly at startup time.
            throw new IllegalStateException(
                    "app.frontend.base-url must use https:// or http://localhost, "
                    + "got an unsafe scheme. Check the environment configuration.");
        }
        return frontendBaseUrl + RESET_PATH + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    /**
     * Schedules a password-reset email to be dispatched after the current transaction commits.
     *
     * <p>Mirrors {@code AuthService.scheduleVerificationEmail}: when no active transaction
     * synchronization exists (e.g. in unit tests where the {@code @Transactional} proxy is
     * bypassed), the email is sent immediately on the calling thread so tests can verify
     * the call without standing up a transaction manager.
     */
    private void scheduleResetEmail(String email, String resetLink) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            emailExecutor.execute(() ->
                                    emailNotificationService.sendPasswordResetEmail(email, resetLink));
                        }
                    }
            );
        } else {
            // No active transaction (unit-test path) — call directly on the calling thread.
            emailExecutor.execute(() ->
                    emailNotificationService.sendPasswordResetEmail(email, resetLink));
        }
    }

    /**
     * Evicts {@code userId}'s entry from {@link TokensValidAfterCache} after the current
     * transaction commits, never before.
     *
     * <p>Mirrors {@link #scheduleResetEmail}: when no active transaction synchronization
     * exists (e.g. in unit tests where the {@code @Transactional} proxy is bypassed), the
     * eviction runs immediately on the calling thread so tests can verify the call without
     * standing up a transaction manager.
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
