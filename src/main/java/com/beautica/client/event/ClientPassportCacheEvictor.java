package com.beautica.client.event;

import com.beautica.booking.event.BookingCompletedEvent;
import com.beautica.client.service.ClientPassportService;
import com.beautica.review.event.ReviewCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Invalidates one client's cached BEAUTY PASSPORT when the data it is derived from changes
 * (2026-08 perf audit F3).
 *
 * <h2>What actually invalidates a passport</h2>
 * <ul>
 *   <li>{@link BookingCompletedEvent} — a booking reaching {@code COMPLETED} moves
 *       {@code bookingsConsidered}, the top-districts and top-cities rankings, and the budget
 *       band. This is the only status the passport aggregates over.</li>
 *   <li>{@link ReviewCreatedEvent} — the client authoring a review moves
 *       {@code reviewsWritten}.</li>
 * </ul>
 * {@code memberSinceYear} is {@code users.created_at} and never changes, so it needs no
 * trigger. Reviews <em>received</em> ({@code ClientReviewCreatedEvent}) are deliberately NOT
 * wired: the passport shows reviews the client WROTE, not ones written about them.
 *
 * <h2>Why AFTER_COMMIT, and why per key</h2>
 * <p><b>AFTER_COMMIT, never inline {@code @CacheEvict} (§F-2).</b> An inline evict on the
 * mutating method runs while its transaction is still open, so a concurrent reader can miss,
 * re-derive the passport from the PRE-commit state and repopulate the cache with it. That
 * stale entry then survives for the full TTL — strictly worse than not caching. Delivering the
 * event at {@link TransactionPhase#AFTER_COMMIT} means the completion/review row is already
 * visible to every other connection before the key is dropped.
 *
 * <p><b>Per key, never {@code allEntries}/{@code clear()} (§F-6).</b> One client completing a
 * booking must not flush every other client's passport — at 2000 entries that would be a
 * thundering herd of 7-statement re-derivations, each scanning a client's whole booking
 * history. The cache is keyed on the client's own user id and so is every evict here.
 *
 * <p><b>Failures are contained.</b> Spring propagates throwables out of an AFTER_COMMIT
 * listener, which would turn an already-committed, fully successful booking completion into a
 * 500. Cache invalidation is a best-effort side-effect of a committed write and must never be
 * able to fail that write's response, so each evict is wrapped and logged. The subsequent
 * {@code expireAfterWrite} TTL is the backstop for a dropped eviction.
 *
 * <p>No {@code @Transactional} here, deliberately: unlike {@code ReviewEventListener} this
 * listener does no DB work, so there is nothing to open a {@code REQUIRES_NEW} transaction
 * for, and joining a phantom one would be the §F-2 anti-pattern.
 */
@Component
@RequiredArgsConstructor
public class ClientPassportCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(ClientPassportCacheEvictor.class);

    private final CacheManager cacheManager;

    /**
     * A completed booking changes every booking-derived value on the owning client's passport.
     * Guest (LINK) bookings carry a null client and have no passport — skip.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingCompleted(BookingCompletedEvent event) {
        evictPassport(event.clientUserId());
    }

    /**
     * A review authored by the client changes {@code reviewsWritten}.
     *
     * <p>Keyed on {@link ReviewCreatedEvent#clientId()} — the AUTHOR — not {@code masterId}.
     * The master's own passport is unaffected: providers do not have one.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCreated(ReviewCreatedEvent event) {
        evictPassport(event.clientId());
    }

    /**
     * Drops exactly one key. Null-safe (guest bookings) and missing-cache-safe (a slice test
     * may run without {@code CacheConfig}), mirroring {@code ReviewEventListener.evictKey} —
     * including its {@code log.warn} on the missing-cache branch. Against the real
     * {@code CacheConfig} that branch is unreachable: dynamic cache creation is disabled and the
     * registration is asserted at startup ({@code CacheConfig#cacheManager}), so a null here means
     * a context without {@code CacheConfig} — worth a warn, never a silent no-op, because in a
     * full context it would mean per-client passports are silently never invalidated.
     */
    private void evictPassport(UUID clientUserId) {
        if (clientUserId == null) {
            return;
        }
        try {
            Cache cache = cacheManager.getCache(ClientPassportService.CLIENT_PASSPORT_CACHE);
            if (cache == null) {
                log.warn("Cache '{}' not found during eviction — passport invalidation skipped",
                        ClientPassportService.CLIENT_PASSPORT_CACHE);
                return;
            }
            cache.evict(clientUserId);
        } catch (Exception ex) {
            // Never propagate: the write this event follows has ALREADY committed.
            log.error("client-passport eviction failed for client={} — {}",
                    clientUserId, ex.getClass().getSimpleName());
        }
    }
}
