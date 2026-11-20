package com.beautica.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.beautica.config.AuthLimitsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * B514 (AUTH-D14 item 6) — login failure lockout "as AUTH-06", keyed by identifier AND client IP: after
 * {@value #MAX_FAILURES} failed logins for one identifier (normalised email or {@code +380} phone) from one IP, that
 * pair is locked for {@link #LOCK_WINDOW}, counted from the last failure. Keying by the pair means an attacker who
 * knows someone's phone cannot lock the owner out from another network (a per-identifier lock rejected even the
 * correct password, a free account-DoS). A locked pair answers the same 401 as a wrong password, so the lock is no
 * oracle. Complements the per-IP {@code loginBuckets} (5 / min).
 *
 * <p>Only identifiers that name a real account are counted ({@link AuthService} calls {@link #recordFailure} on the
 * known-user path only), so a flood of garbage identifiers cannot fill the bounded cache and evict real counters.
 *
 * <p>In memory (Caffeine, bounded, single instance — the same deployment model as the Bucket4j buckets). The
 * ticker reads the injected {@link Clock} so tests can pin time (Anti-Bug §G). Keys are never logged.
 *
 * <p>B516 audit-fix cycle 3 (security) — the per-ACCOUNT current-password lockout (AUTH-D19,
 * {@link #tryAcquireAccountAttempt} / {@link #resetAccount}) lives in its OWN bounded cache with the same threshold and
 * window, so a flood of failed logins filling the (identifier, IP) cache can never evict an account's lock.
 */
@Component
public class LoginFailureLimiter {

    /** Production default of {@code app.auth-limits.login-max-failures}. */
    public static final int MAX_FAILURES = 10;
    /** Production default of {@code app.auth-limits.login-lock-window}. */
    public static final Duration LOCK_WINDOW = Duration.ofMinutes(15);
    private static final long MAX_TRACKED_IDENTIFIERS = 100_000;
    /** One entry per signed-in account that typed a wrong current password inside the window. */
    private static final long MAX_TRACKED_ACCOUNTS = 100_000;
    /** Neither a normalised email nor a {@code +380} phone nor an IP literal contains a newline. */
    private static final String KEY_SEPARATOR = "\n";

    private final Cache<String, Integer> failures;
    private final Cache<String, Integer> accountFailures;
    private final int maxFailures;

    /** Production defaults ({@link #MAX_FAILURES} / {@link #LOCK_WINDOW}); kept for the tests that build it directly. */
    public LoginFailureLimiter(Clock clock) {
        this(clock, AuthLimitsProperties.defaults());
    }

    @Autowired
    public LoginFailureLimiter(Clock clock, AuthLimitsProperties limits) {
        this(clock, MAX_TRACKED_IDENTIFIERS, MAX_TRACKED_ACCOUNTS, limits);
    }

    /** Test seam: small bounds so a test can overflow the login cache. */
    LoginFailureLimiter(Clock clock, long maxTrackedPairs, long maxTrackedAccounts) {
        this(clock, maxTrackedPairs, maxTrackedAccounts, AuthLimitsProperties.defaults());
    }

    private LoginFailureLimiter(Clock clock, long maxTrackedPairs, long maxTrackedAccounts,
                                AuthLimitsProperties limits) {
        Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
        this.maxFailures = limits.loginMaxFailures();
        this.failures = newCounterCache(ticker, maxTrackedPairs, limits.loginLockWindow());
        this.accountFailures = newCounterCache(ticker, maxTrackedAccounts, limits.loginLockWindow());
    }

    private static Cache<String, Integer> newCounterCache(Ticker ticker, long maximumSize, Duration lockWindow) {
        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(lockWindow)
                .ticker(ticker)
                .build();
    }

    /** {@code true} when {@code identifierKey} reached the failure cap from {@code clientIp} inside the window. */
    public boolean isLocked(String identifierKey, String clientIp) {
        Integer count = failures.getIfPresent(key(identifierKey, clientIp));
        return count != null && count >= maxFailures;
    }

    /** Counts one failed login of a real account from {@code clientIp}; each failure restarts the window. */
    public void recordFailure(String identifierKey, String clientIp) {
        failures.asMap().merge(key(identifierKey, clientIp), 1, Integer::sum);
    }

    /** A successful login clears the count of the identifier from that IP. */
    public void reset(String identifierKey, String clientIp) {
        failures.invalidate(key(identifierKey, clientIp));
    }

    /**
     * B516 (AUTH-D19) — atomically claims one current-password attempt for {@code accountKey} BEFORE the password is
     * checked: {@code false} (nothing written) once the account holds {@value #MAX_FAILURES} counted attempts inside
     * the window, else the attempt is counted and {@code true} returned. Counting first and clearing on success
     * ({@link #resetAccount}) means concurrent requests can never get more than {@value #MAX_FAILURES} password checks
     * between them (a check-then-record race could). Kept in the account cache, never the login one.
     */
    public boolean tryAcquireAccountAttempt(String accountKey) {
        Integer current = accountFailures.getIfPresent(accountKey);
        if (current != null && current >= maxFailures) {
            return false; // fast path: no write, so a locked key's window is not restarted
        }
        boolean[] acquired = {false};
        accountFailures.asMap().compute(accountKey, (k, count) -> {
            int value = count == null ? 0 : count;
            if (value >= maxFailures) {
                return count;
            }
            acquired[0] = true;
            return value + 1;
        });
        return acquired[0];
    }

    /** The correct current password clears the account's count. */
    public void resetAccount(String accountKey) {
        accountFailures.invalidate(accountKey);
    }

    /** Number of tracked (identifier, IP) pairs — for tests of the cache-flood guard. */
    long trackedCount() {
        failures.cleanUp();
        return failures.estimatedSize();
    }

    private static String key(String identifierKey, String clientIp) {
        return identifierKey + KEY_SEPARATOR + clientIp;
    }
}
