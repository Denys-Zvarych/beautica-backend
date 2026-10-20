package com.beautica.common.cache;

import com.beautica.booking.dto.SalonBookingSlugInfoResponse;
import com.beautica.booking.dto.SalonBookingSlugMasterItem;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Evicts the public salon booking-page cache {@value #CACHE} (phase 377,
 * {@code SalonBookingSlugService#findBySlug}, {@code key = "#slug"}).
 *
 * <p><b>Why by value.</b> The cache is keyed by the salon's slug, but the writers that change the page —
 * schedule, service-assignment and master (de)activation writes, salon edits — know the salon id (or, for
 * an avatar, the master's slug), never the salon slug. The cached value carries {@code salonId} and every
 * listed {@code masterSlug}, so the entry is found by a value scan bounded by this one cache
 * ({@code maximumSize} 500), delegated to {@link MasterCachePrefixEvictor} — the single owner of native
 * cache scans ({@code CacheKeyScanOwnershipTest}). Only matching entries go, never {@code clear()}
 * (Anti-Bug §F-6). Normally one entry matches: a salon's slug never changes.
 *
 * <p><b>Ordering.</b> Every method here must run after the write commits (Anti-Bug §F-2):
 * {@link #evictSalonAfterCommit} registers itself; {@link #evictSalon} / {@link #evictByMasterSlug} are for
 * callers already inside an {@code afterCommit} hook. The scan runs on {@code cacheEvictionExecutor}
 * (inline under the {@code test} profile), off the committing request thread.
 */
@Component
public class SalonBookingInfoCacheEvictor {

    public static final String CACHE = "salon-booking-slug-info";
    private static final List<String> CACHES = List.of(CACHE);

    private final MasterCachePrefixEvictor cachePrefixEvictor;

    public SalonBookingInfoCacheEvictor(MasterCachePrefixEvictor cachePrefixEvictor) {
        this.cachePrefixEvictor = cachePrefixEvictor;
    }

    /** Evicts the salon's page now. Call from an {@code afterCommit} hook; {@code null} is a no-op. */
    public void evictSalon(UUID salonId) {
        if (salonId != null) {
            cachePrefixEvictor.evictByValue(ofSalon(salonId), CACHES);
        }
    }

    /** Registers {@link #evictSalon} after the current transaction commits (immediately outside one). */
    public void evictSalonAfterCommit(UUID salonId) {
        if (salonId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictSalon(salonId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictSalon(salonId);
            }
        });
    }

    /**
     * Evicts every salon page that lists the master with {@code masterSlug} (a master's avatar, rendered
     * on its salon's page). Call from an {@code afterCommit} hook; {@code null} is a no-op.
     */
    public void evictByMasterSlug(String masterSlug) {
        if (masterSlug != null) {
            cachePrefixEvictor.evictByValue(listingMaster(masterSlug), CACHES);
        }
    }

    static Predicate<Object> ofSalon(UUID salonId) {
        return value -> value instanceof SalonBookingSlugInfoResponse page && salonId.equals(page.salonId());
    }

    static Predicate<Object> listingMaster(String masterSlug) {
        return value -> value instanceof SalonBookingSlugInfoResponse page
                && page.masters().stream().map(SalonBookingSlugMasterItem::masterSlug).anyMatch(masterSlug::equals);
    }
}
