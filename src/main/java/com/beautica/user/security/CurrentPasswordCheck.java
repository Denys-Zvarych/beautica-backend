package com.beautica.user.security;

import com.beautica.auth.LoginFailureLimiter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * B516 (AUTH-D19) — "is this the account's current password?" for the verify step of a phone change and an email add
 * / change. A missing password, or an account with no password hash (a future social-only account, B506), never
 * matches. The raw value is only handed to the encoder — never stored or logged.
 *
 * <p>Audit-fix cycle 2 (security) — ONE per-account cumulative lockout shared by the phone and the email path, so
 * fresh codes do not buy fresh password guesses: REUSE-FIRST, the AUTH-08 {@link LoginFailureLimiter} with the same
 * threshold and window ({@value LoginFailureLimiter#MAX_FAILURES} wrong passwords per 15 min, the window restarting
 * on each failure), kept in the limiter's own per-account cache (cycle 3), so a login-failure flood cannot evict
 * it. An attempt is claimed atomically BEFORE BCrypt and cleared only by a correct password, so concurrent
 * requests share the same {@value LoginFailureLimiter#MAX_FAILURES} checks. Once locked, every answer — even for the
 * correct password — is "no match", without running BCrypt.
 */
@Component
public class CurrentPasswordCheck {

    /** The lockout is per ACCOUNT, not per client IP: the caller is already signed in as that account. */
    private static final String KEY_PREFIX = "current-password:";

    private final PasswordEncoder passwordEncoder;
    private final LoginFailureLimiter failureLimiter;

    public CurrentPasswordCheck(PasswordEncoder passwordEncoder, LoginFailureLimiter failureLimiter) {
        this.passwordEncoder = passwordEncoder;
        this.failureLimiter = failureLimiter;
    }

    /**
     * Checks {@code rawPassword} against {@code passwordHash} (read WITHOUT a row lock — BCrypt must not run under
     * one) and counts the result against the account's shared lockout.
     *
     * @return {@code true} only for the correct password on an account that is not locked out
     */
    public boolean verify(UUID userId, String passwordHash, String rawPassword) {
        String key = KEY_PREFIX + userId;
        if (!failureLimiter.tryAcquireAccountAttempt(key)) {
            return false;
        }
        boolean matches = rawPassword != null && !rawPassword.isEmpty() && passwordHash != null
                && passwordEncoder.matches(rawPassword, passwordHash);
        if (matches) {
            failureLimiter.resetAccount(key);
        }
        return matches;
    }
}
