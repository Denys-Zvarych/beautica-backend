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
     * used, every other outstanding reset token for the user is invalidated, and every
     * existing refresh token (i.e., all sessions) is revoked. No auth tokens are
     * returned — the client must route to the login screen.
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

        user.setPasswordHash(newPasswordHash);
        userRepository.save(user);

        token.markUsed();
        passwordResetTokenRepository.save(token);

        // Defence in depth: invalidate every other outstanding reset token for the user,
        // then terminate all existing sessions (global logout).
        passwordResetTokenRepository.markAllUsedByUserId(user.getId());
        refreshTokenRepository.deleteByUserId(user.getId());

        log.info("Password reset completed: userId={}", user.getId());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Performs a throwaway token hash on the no-op (unknown / unverified / inactive)
     * branch so the per-request crypto cost is symmetric with the verified-account
     * path, which mints + hashes a real token. Without this, response latency would
     * distinguish a real verified account (extra hash + DB save + email schedule)
     * from a phantom, opening a timing-based enumeration side-channel.
     *
     * <p>Mirrors the existing unknown-email defense pattern: the HTTP body is already
     * identical across branches; this closes the latency channel. The discarded result
     * is intentional — the cost, not the value, is what matters. (The email-bounce
     * channel is inherent and accepted; only the latency channel is closed here.)
     */
    private void performDecoyWork() {
        String decoyToken = tokenGenerator.generateToken();
        tokenGenerator.hash(decoyToken);
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
}
