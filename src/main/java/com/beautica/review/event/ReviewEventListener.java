package com.beautica.review.event;

import com.beautica.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class ReviewEventListener {

    private final ReviewRepository reviewRepository;
    private final CacheManager cacheManager;

    // Eviction is registered as a post-commit callback rather than via @CacheEvict.
    // @CacheEvict fires at proxy entry (before the REQUIRES_NEW TX commits), which
    // allows a concurrent reader to repopulate the cache with stale data before the
    // rating UPDATE commits. Registering afterCommit() ensures the cache is cleared
    // only after the new rating is durable.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReviewCreated(ReviewCreatedEvent event) {
        reviewRepository.recalculateMasterRating(event.masterId());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                var cache = cacheManager.getCache("reviews-by-master");
                if (cache != null) {
                    cache.clear();
                }
            }
        });
    }
}
