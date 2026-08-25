package com.beautica.review.service;

import com.beautica.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The {@code REQUIRES_NEW} transaction BOUNDARY for the two denormalised rating recalculations,
 * extracted so that the boundary and the {@code try/catch} that contains its failures are on
 * OPPOSITE sides of a Spring proxy.
 *
 * <h3>Why this class exists — the containment bug it fixes</h3>
 *
 * <p>Both {@link com.beautica.review.event.ReviewEventListener} and
 * {@link com.beautica.review.event.SalonStaffRatingListener} used to carry
 * {@code @Transactional(REQUIRES_NEW)} on the listener method itself and wrap the repository call
 * in {@code try (…) catch (Exception)}. That does NOT contain a JPA failure:
 *
 * <ol>
 *   <li>the {@code UPDATE} throws — say a {@code DataAccessException};</li>
 *   <li>Hibernate has already marked the transaction {@code rollbackOnly};</li>
 *   <li>the {@code catch} swallows the original exception and the method returns NORMALLY;</li>
 *   <li>{@code TransactionInterceptor#commitTransactionAfterReturning} then runs — <b>outside</b>
 *       the {@code try} block, because the interceptor sits around the method — and
 *       {@code AbstractPlatformTransactionManager#processCommit} throws
 *       {@link org.springframework.transaction.UnexpectedRollbackException} for the
 *       rollback-only mark.</li>
 * </ol>
 *
 * <p>That exception escapes into the caller, which is
 * {@code triggerAfterCommit} → {@code processCommit} of the OUTER transaction —
 * {@code TransactionSynchronizationUtils#invokeAfterCommit} has no {@code catch} at all (unlike
 * {@code invokeAfterCompletion}, see {@code ReviewEventListener}'s own analysis). The net effect is
 * an already-COMMITTED write — a review INSERT, a master deactivation — surfacing to the client as
 * a 500, with the write silently persisted. Precisely the outcome the fire-and-log-don't-fail
 * contract exists to prevent.
 *
 * <p><b>The fix is structural, not another catch clause.</b> With the boundary on this bean, the
 * proxy's commit (and therefore its {@code UnexpectedRollbackException}) happens while control is
 * still INSIDE the caller's {@code try}, so the existing {@code catch (Exception)} catches it like
 * any other failure. The listeners no longer declare a transaction of their own.
 *
 * <p><b>A separate bean, not a method on the listeners</b>: self-invocation does not pass through
 * the proxy (Anti-Bug §F-3), so an in-class {@code @Transactional} method would silently run with
 * no new transaction at all — the same class of bug wearing the fix's clothes. Same reasoning, and
 * the same shape, as {@code FavoritePersistenceService}.
 *
 * <p><b>One transaction per recalculation.</b> {@code ReviewEventListener} previously ran the
 * master and salon recalcs in ONE transaction, so a failing salon recalc marked the whole thing
 * rollback-only and silently discarded the master recalc that had already succeeded — despite the
 * two having independent {@code try/catch} blocks that read as if they were isolated. Two methods
 * here means two transactions, so the isolation those blocks promise is real.
 *
 * <p>Neither method catches anything: containment belongs to the caller, which is the side that
 * knows the ids to log and has the {@code afterCompletion} eviction to run regardless.
 */
@Service
@RequiredArgsConstructor
public class RatingRecalculationService {

    private final ReviewRepository reviewRepository;

    /**
     * Recomputes {@code masters.avg_rating} / {@code masters.review_count} for one master in its
     * own transaction.
     *
     * @see ReviewRepository#recalculateMasterRating(UUID)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recalculateMasterRating(UUID masterId) {
        reviewRepository.recalculateMasterRating(masterId);
    }

    /**
     * Recomputes {@code salons.avg_rating} / {@code salons.review_count} for one salon in its own
     * transaction. Fired both when a review is written and when the salon's STAFF SET changes —
     * the aggregate is scoped to the salon's currently-active masters.
     *
     * @see ReviewRepository#recalculateSalonRating(UUID)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recalculateSalonRating(UUID salonId) {
        reviewRepository.recalculateSalonRating(salonId);
    }
}
