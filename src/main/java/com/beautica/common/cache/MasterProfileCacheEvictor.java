package com.beautica.common.cache;

import com.beautica.auth.Role;
import com.beautica.search.service.SearchCacheNames;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Evicts, after the current transaction commits, every cache that renders a master's
 * {@code users}-row profile fields — for writers outside {@code com.beautica.master} whose field
 * lands in those DTOs. Phase 344 writer: {@code MediaService#uploadAvatar} / {@code #deleteAvatar}
 * ({@code users.avatar_url}).
 *
 * <p><b>Caches and keys</b> — each key is the exact {@code @Cacheable} key of the reader:
 * <ul>
 *   <li>{@code master-detail-by-user} — {@code MasterService#findMyMasterDetail}, {@code key = "#userId"}</li>
 *   <li>{@code master-by-user} — {@code MasterService#getMasterByUserId}, {@code key = "#userId"}
 *       (holds a {@code Master} with its {@code User} fetched)</li>
 *   <li>{@code master-detail} — {@code MasterService#getMasterDetail}, {@code key = "#masterId"}</li>
 *   <li>{@code booking-slug-info} — {@code BookingSlugService#findBySlug}, {@code key = "#slug"}</li>
 *   <li>{@code search:masters:*} ({@link SearchCacheNames#MASTERS_ALL}) — <b>cleared</b>, not
 *       evicted: those keys are the search request (filters, query text, page), so no key names a
 *       master and per-key eviction is impossible. Same rule {@code UserService} applies to a
 *       master's search-visible profile fields. Writers here are rare, user-initiated profile
 *       edits, not booking traffic, so the clear cannot become a hot-path herd.</li>
 * </ul>
 *
 * <p><b>Ordering.</b> {@code afterCommit}, never inline (Anti-Bug §F-2): an inline evict lets a
 * parallel reader repopulate the entry from the pre-write row for the full TTL. Outside an active
 * transaction the evict runs immediately, mirroring {@link UserProfileCacheEvictor}.
 */
@Component
public class MasterProfileCacheEvictor {

    public static final String MASTER_DETAIL_CACHE = "master-detail";
    public static final String MASTER_DETAIL_BY_USER_CACHE = "master-detail-by-user";
    public static final String MASTER_BY_USER_CACHE = "master-by-user";
    public static final String BOOKING_SLUG_INFO_CACHE = "booking-slug-info";

    private final CacheManager cacheManager;

    public MasterProfileCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Registers the post-commit eviction of one master's profile-rendering cache entries.
     *
     * @param userId      the master's account id; {@code null} makes the whole call a no-op
     * @param masterId    the {@code masters.id}; {@code null} skips {@code master-detail}
     * @param bookingSlug the public booking slug; {@code null} skips {@code booking-slug-info}
     * @param role        the writing user's role; only {@link Role#INDEPENDENT_MASTER} clears the
     *                    {@code search:masters:*} caches (the per-key evictions are unconditional)
     */
    public void evictAfterCommit(UUID userId, UUID masterId, String bookingSlug, Role role) {
        if (userId == null) {
            return;
        }
        // Same condition as UserService#evictUserCachesAfterCommit — only independent masters are
        // search-visible.
        boolean clearSearch = role == Role.INDEPENDENT_MASTER;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(userId, masterId, bookingSlug, clearSearch);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(userId, masterId, bookingSlug, clearSearch);
            }
        });
    }

    private void evict(UUID userId, UUID masterId, String bookingSlug, boolean clearSearch) {
        evictKey(MASTER_DETAIL_BY_USER_CACHE, userId);
        evictKey(MASTER_BY_USER_CACHE, userId);
        evictKey(MASTER_DETAIL_CACHE, masterId);
        evictKey(BOOKING_SLUG_INFO_CACHE, bookingSlug);
        if (clearSearch) {
            clearSearchCaches();
        }
    }

    private void clearSearchCaches() {
        for (String searchCache : SearchCacheNames.MASTERS_ALL) {
            Cache cache = cacheManager.getCache(searchCache);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    private void evictKey(String cacheName, Object key) {
        if (key == null) {
            return;
        }
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }
}
