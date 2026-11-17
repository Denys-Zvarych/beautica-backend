package com.beautica.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
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
 */
@Component
public class LoginFailureLimiter {

    static final int MAX_FAILURES = 10;
    static final Duration LOCK_WINDOW = Duration.ofMinutes(15);
    private static final long MAX_TRACKED_IDENTIFIERS = 100_000;
    /** Neither a normalised email nor a {@code +380} phone nor an IP literal contains a newline. */
    private static final String KEY_SEPARATOR = "\n";

    private final Cache<String, Integer> failures;

    public LoginFailureLimiter(Clock clock) {
        Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
        this.failures = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_IDENTIFIERS)
                .expireAfterWrite(LOCK_WINDOW)
                .ticker(ticker)
                .build();
    }

    /** {@code true} when {@code identifierKey} reached the failure cap from {@code clientIp} inside the window. */
    public boolean isLocked(String identifierKey, String clientIp) {
        Integer count = failures.getIfPresent(key(identifierKey, clientIp));
        return count != null && count >= MAX_FAILURES;
    }

    /** Counts one failed login of a real account from {@code clientIp}; each failure restarts the window. */
    public void recordFailure(String identifierKey, String clientIp) {
        failures.asMap().merge(key(identifierKey, clientIp), 1, Integer::sum);
    }

    /** A successful login clears the count of the identifier from that IP. */
    public void reset(String identifierKey, String clientIp) {
        failures.invalidate(key(identifierKey, clientIp));
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
