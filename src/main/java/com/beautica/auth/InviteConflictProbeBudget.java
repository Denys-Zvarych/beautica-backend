package com.beautica.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * B520 audit-fix (AUTH-D02 / AUTH-18) — bounds how many 409 {@code PHONE_ALREADY_REGISTERED} answers one inviter may
 * collect: {@value #MAX_CONFLICTS} per {@link #WINDOW}. A 409 sends no SMS, so it never touches the AUTH-52 SMS caps;
 * without this budget a provider session could probe unlimited phones for "is registered" at no cost.
 *
 * <p>Once the budget is spent, EVERY invite of that caller is refused (429 {@code INVITE_SMS_LIMIT}, the code the app
 * already handles) until the window ends — refusing only further 409s would turn the 429/201 split into the same oracle.
 *
 * <p>Shape of {@link LoginFailureLimiter}: in memory (Caffeine, bounded, single instance — the deployment model of the
 * Bucket4j buckets), the window restarts on each counted conflict, the ticker reads the injected {@link Clock} (Anti-Bug
 * §G). Keys are account ids, never phones.
 */
@Component
public class InviteConflictProbeBudget {

    public static final int MAX_CONFLICTS = 10;
    public static final Duration WINDOW = Duration.ofHours(1);
    private static final long MAX_TRACKED_INVITERS = 50_000;

    private final ConcurrentMap<UUID, Integer> conflictsByInviter;

    @Autowired
    public InviteConflictProbeBudget(Clock clock) {
        this(clock, MAX_TRACKED_INVITERS);
    }

    /** Test seam: a small bound. */
    InviteConflictProbeBudget(Clock clock, long maxTrackedInviters) {
        Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
        Cache<UUID, Integer> cache = Caffeine.newBuilder()
                .maximumSize(maxTrackedInviters)
                .expireAfterWrite(WINDOW)
                .ticker(ticker)
                .build();
        this.conflictsByInviter = cache.asMap();
    }

    /** {@code true} when {@code inviterId} has spent the whole budget inside the window. */
    public boolean isExhausted(UUID inviterId) {
        Integer count = conflictsByInviter.get(inviterId);
        return count != null && count >= MAX_CONFLICTS;
    }

    /**
     * Atomically counts one 409 for {@code inviterId}: {@code false} (nothing counted) when the budget is already
     * spent, so concurrent probes can never collect more than {@value #MAX_CONFLICTS} answers between them.
     */
    public boolean tryRecordConflict(UUID inviterId) {
        if (isExhausted(inviterId)) {
            return false; // fast path: no write, so a spent budget's window is not restarted
        }
        boolean[] recorded = {false};
        conflictsByInviter.compute(inviterId, (id, count) -> {
            int value = count == null ? 0 : count;
            if (value >= MAX_CONFLICTS) {
                return count;
            }
            recorded[0] = true;
            return value + 1;
        });
        return recorded[0];
    }
}
