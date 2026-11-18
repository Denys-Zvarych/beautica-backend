package com.beautica.common.cache;

import com.github.benmanes.caffeine.cache.Expiry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Write-based Caffeine expiry with a random per-entry reduction: each created or updated entry lives
 * {@code ttl - U[0, jitter)}, so a bulk fill (one cold search resolve writes up to 2 000
 * {@code master-bookable-assignments} entries in the same millisecond) does not expire as one block
 * and re-trigger the full 180-day walk for every master at once. Reads do not extend the lifetime
 * (the {@code expireAfterWrite} semantics it replaces); the upper bound stays {@code ttl}, so every
 * documented staleness bound still holds.
 */
public final class JitteredExpiry implements Expiry<Object, Object> {

    private final long ttlNanos;
    private final long jitterNanos;

    public JitteredExpiry(Duration ttl, Duration jitter) {
        if (jitter.isNegative() || jitter.compareTo(ttl) >= 0) {
            throw new IllegalArgumentException("jitter must be in [0, ttl)");
        }
        this.ttlNanos = ttl.toNanos();
        this.jitterNanos = jitter.toNanos();
    }

    @Override
    public long expireAfterCreate(Object key, Object value, long currentTime) {
        return jittered();
    }

    @Override
    public long expireAfterUpdate(Object key, Object value, long currentTime, long currentDuration) {
        return jittered();
    }

    @Override
    public long expireAfterRead(Object key, Object value, long currentTime, long currentDuration) {
        return currentDuration;
    }

    private long jittered() {
        return jitterNanos == 0 ? ttlNanos : ttlNanos - ThreadLocalRandom.current().nextLong(jitterNanos);
    }
}
