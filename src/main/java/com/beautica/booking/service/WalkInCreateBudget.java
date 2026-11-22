package com.beautica.booking.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * B527 audit — per-ACTOR budget for staff walk-in creates (30 per hour per staff user,
 * {@code app.rate-limit.staff-walk-in-create-capacity}). Charged on EVERY create, whether or not the phone resolves to a
 * CLIENT account: a throttle charged only on a match would make its own 429 a phone -&gt; is-a-client oracle. Keys are
 * staff user ids, never phones.
 */
@Component
public class WalkInCreateBudget {

    private final LoadingCache<String, Bucket> buckets;

    public WalkInCreateBudget(@Qualifier("staffWalkInCreateBuckets") LoadingCache<String, Bucket> buckets) {
        this.buckets = buckets;
    }

    /** {@code true} when one more walk-in is allowed for {@code actorId} (the token is consumed). */
    public boolean tryConsume(UUID actorId) {
        return buckets.get(actorId.toString()).tryConsume(1);
    }
}
