package com.beautica.auth.appcheck;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Phone-first hardening B9 (AUTH-D27 d) — the App Check token ids ({@code jti}) already accepted, each remembered until
 * its token's {@code exp} (a token past {@code exp} fails verification anyway). In memory (Caffeine, bounded,
 * single Railway instance — the {@code LoginFailureLimiter} deployment model); the ticker reads the injected
 * {@link Clock} so tests can pin time (Anti-Bug §G). Ids are never logged.
 */
@Component
public class AppCheckReplayStore {

    /** Limited-use tokens live minutes; 50 k ids bound the heap even under a flood of distinct valid tokens. */
    static final long MAX_TRACKED_IDS = 50_000;
    /** Gauge of the ids currently remembered (audit-fix): a sustained climb means a token flood or a bad client. */
    public static final String SIZE_METRIC = "appcheck.replay.tracked";
    /** An id lives at least this long, so a token at its last instant cannot be replayed through a zero TTL. */
    private static final Duration MIN_RETENTION = Duration.ofSeconds(1);

    private final Clock clock;
    private final Cache<String, Instant> seen;

    /** Without a registry (unit tests and the guard's pre-hardening constructor): no gauge. */
    public AppCheckReplayStore(Clock clock) {
        this(clock, MAX_TRACKED_IDS, null);
    }

    @Autowired
    public AppCheckReplayStore(Clock clock, MeterRegistry meterRegistry) {
        this(clock, MAX_TRACKED_IDS, meterRegistry);
    }

    /** Test seam: small bound. */
    AppCheckReplayStore(Clock clock, long maxTrackedIds, MeterRegistry meterRegistry) {
        this.clock = clock;
        Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
        this.seen = Caffeine.newBuilder()
                .maximumSize(maxTrackedIds)
                .ticker(ticker)
                .expireAfter(new UntilTokenExpiry())
                .build();
        if (meterRegistry != null) {
            Gauge.builder(SIZE_METRIC, seen, Cache::estimatedSize).register(meterRegistry);
        }
    }

    /**
     * Records {@code jti} as accepted until {@code expiresAt}.
     *
     * @return {@code true} the first time an id is seen inside its lifetime, {@code false} on a replay
     */
    public boolean markFirstUse(String jti, Instant expiresAt) {
        Instant until = expiresAt == null ? clock.instant().plus(MIN_RETENTION) : expiresAt;
        return seen.asMap().putIfAbsent(jti, until) == null;
    }

    private final class UntilTokenExpiry implements Expiry<String, Instant> {

        @Override
        public long expireAfterCreate(String key, Instant until, long currentTime) {
            Duration ttl = Duration.between(clock.instant(), until);
            return (ttl.compareTo(MIN_RETENTION) < 0 ? MIN_RETENTION : ttl).toNanos();
        }

        @Override
        public long expireAfterUpdate(String key, Instant until, long currentTime, long currentDuration) {
            return currentDuration;
        }

        @Override
        public long expireAfterRead(String key, Instant until, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
