package com.beautica.review.event;

import com.beautica.review.service.RatingRecalculationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard (phases 383/384 QA). The public {@code /book/{slug}/reviews} and {@code /book/s/{slug}/reviews}
 * routes decide "this page is beyond the last one" from the PERSISTED {@code masters.review_count} /
 * {@code salons.review_count} ({@code BookingSlugService#isBeyondLastPage}). That is only correct while the counter
 * is written synchronously, before the {@code POST /reviews} response: the review commits, then the AFTER_COMMIT
 * listener recalculates the counters in a REQUIRES_NEW transaction on the SAME thread. Make either step
 * asynchronous and the first review of a master reads as an empty page until the counter catches up.
 */
@DisplayName("Review counters are recalculated synchronously after the review commit (guards the /reviews beyond-last-page shortcut)")
class ReviewCounterSynchronyGuardTest {

    @Test
    @DisplayName("ReviewEventListener and RatingRecalculationService carry no @Async, on the class or any method")
    void should_haveNoAsyncAnywhere_when_ratingRecalculationWired() {
        for (Class<?> type : new Class<?>[] {ReviewEventListener.class, RatingRecalculationService.class}) {
            assertThat(type.isAnnotationPresent(Async.class)).as("%s class @Async", type.getSimpleName()).isFalse();
            assertThat(Arrays.stream(type.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(Async.class))
                    .map(Method::getName).toList())
                    .as("%s @Async methods", type.getSimpleName()).isEmpty();
        }
    }

    @Test
    @DisplayName("onReviewCreated stays a @TransactionalEventListener(AFTER_COMMIT) - not a plain @EventListener, not BEFORE_COMMIT")
    void should_listenAfterCommit_when_reviewCreated() throws Exception {
        Method listener = ReviewEventListener.class.getMethod("onReviewCreated", ReviewCreatedEvent.class);

        TransactionalEventListener annotation = listener.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).as("@TransactionalEventListener present").isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("both recalculations run in their own REQUIRES_NEW transaction (the counter commits before the response)")
    void should_commitCountersInOwnTransaction_when_recalculated() throws Exception {
        for (String name : new String[] {"recalculateMasterRating", "recalculateSalonRating"}) {
            Method method = RatingRecalculationService.class.getMethod(name, java.util.UUID.class);
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as("%s @Transactional", name).isNotNull();
            assertThat(tx.propagation()).as("%s propagation", name).isEqualTo(Propagation.REQUIRES_NEW);
        }
    }
}
