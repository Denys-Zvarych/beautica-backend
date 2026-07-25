package com.beautica.review.event;

import com.beautica.review.repository.ClientReviewRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Master&rarr;client mirror of {@link ReviewEventListener} (Phase 27.5). Recalculates the
 * client's {@code users.avg_rating}/{@code users.review_count} aggregate AFTER the review
 * transaction commits, in its own {@code REQUIRES_NEW} transaction — same fire-and-log-don't-fail
 * contract as the master/salon recalculation (a failure here must never roll back the
 * already-committed review).
 *
 * <p>No cache eviction here (unlike {@link ReviewEventListener}): there is no {@code
 * reviews-by-client} cache, and {@code GET /users/me/rating} is not cached (a single primary-key
 * projection read, cheap enough that a dedicated cache would only add eviction-wiring surface —
 * same rationale as {@code ReviewService#getMasterReviewSummary}).
 *
 * <p>No notification/outbox row is enqueued (locked product decision — a client is never told a
 * master reviewed them).
 */
@Component
@RequiredArgsConstructor
public class ClientReviewEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClientReviewEventListener.class);

    private final ClientReviewRepository clientReviewRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onClientReviewCreated(ClientReviewCreatedEvent event) {
        try {
            clientReviewRepository.recalculateClientRating(event.clientId());
        } catch (Exception ex) {
            log.error("recalculateClientRating failed for client={} — {}",
                    event.clientId(), ex.getClass().getSimpleName());
        }
    }
}
