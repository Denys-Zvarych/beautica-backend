package com.beautica.review.event;

import com.beautica.review.repository.ReviewRepository;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ReviewEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewEventListener.class);

    private final ReviewRepository reviewRepository;
    private final CacheManager cacheManager;

    // Runs after the outer transaction (review INSERT) commits.
    // REQUIRES_NEW opens a separate transaction for the rating UPDATE so that a
    // failure there does not roll back the already-committed review.
    // Cache eviction is placed AFTER the try-catch — it runs unconditionally
    // regardless of whether the rating recalculation succeeded or failed.
    // This prevents stale avg_rating being served from cache after a failed recalc.
    //
    // TransactionSynchronizationManager is intentionally NOT used here.
    // If eviction were registered inside the REQUIRES_NEW transaction body, a
    // rollback of that transaction would discard the synchronization callback,
    // leaving the cache unreachable until TTL expiry.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReviewCreated(ReviewCreatedEvent event) {
        try {
            reviewRepository.recalculateMasterRating(event.masterId());
        } catch (Exception ex) {
            log.error("recalculateMasterRating failed for master={} — {}",
                      event.masterId(), ex.getClass().getSimpleName());
        }
        // Eviction always runs — even if rating recalc failed — so stale pages
        // are not served from cache.
        evictMasterReviewPages(event.masterId());
    }

    // Evicts only the cached review pages belonging to the given master.
    //
    // The "reviews-by-master" cache uses string keys of the form
    // "<masterId>:<pageNumber>:<pageSize>" (see ReviewService.getReviewsForMaster).
    // All pages for a master share the same UUID prefix, so a startsWith check
    // evicts exactly those entries and leaves all other masters' pages intact.
    //
    // Coupling note: getNativeCache() returns Caffeine's public Cache<Object,Object>.
    // If the cache manager backend ever changes away from Caffeine, this method must be updated.
    @SuppressWarnings("unchecked")
    private void evictMasterReviewPages(UUID masterId) {
        org.springframework.cache.Cache springCache = cacheManager.getCache("reviews-by-master");
        if (springCache == null) {
            log.warn("Cache 'reviews-by-master' not found during eviction for master={}", masterId);
            return;
        }
        Cache<Object, Object> nativeCache = (Cache<Object, Object>) springCache.getNativeCache();
        String prefix = masterId.toString() + ':';
        nativeCache.invalidateAll(
                nativeCache.asMap().keySet().stream()
                        .filter(k -> k instanceof String s && s.startsWith(prefix))
                        .toList()
        );
    }
}
