package com.beautica.review.event;

import com.beautica.master.event.SalonStaffChangedEvent;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.review.service.RatingRecalculationService;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Recomputes a salon's rating when its STAFF SET changes (mobile Phase 111) — the second trigger
 * for {@link ReviewRepository#recalculateSalonRating}, alongside
 * {@link ReviewEventListener#onReviewCreated}.
 *
 * <p>Deliberately a separate class from {@link ReviewEventListener} rather than another method on
 * it: that listener's contract is "a review was written", and every one of its branches derives
 * from a {@code ReviewCreatedEvent}'s master/salon/user ids. This one has a different trigger,
 * a different payload and a strictly smaller eviction set. Merging them would give one class two
 * reasons to change.
 *
 * <p><b>Contract copied verbatim from {@link ReviewEventListener}</b>, because the hazards are
 * identical:
 * <ul>
 *   <li>{@code AFTER_COMMIT} — the aggregate filters on {@code masters.salon_id} /
 *       {@code masters.is_active}, so it MUST run after the staff mutation is visible.</li>
 *   <li>{@code REQUIRES_NEW} — a failed recalc must never roll back the already-committed staff
 *       change. A master's deactivation is the important write here; the rating is a derived
 *       side-effect. <b>The boundary lives on {@link RatingRecalculationService}, not on this
 *       method</b>: with {@code @Transactional} here, the interceptor's commit — and therefore the
 *       {@code UnexpectedRollbackException} Hibernate's rollback-only mark produces after a failed
 *       {@code UPDATE} — fired OUTSIDE the {@code catch} below and escaped into the outer
 *       transaction's {@code triggerAfterCommit}, which has no catch. See that class's javadoc for
 *       the full chain.</li>
 *   <li>fire-and-log-don't-fail — every exception is caught and logged at ERROR with the
 *       exception's simple name only (no message, no PII).</li>
 *   <li>eviction via {@code afterCompletion}, never inline — inline eviction happens BEFORE this
 *       transaction commits, letting a concurrent reader repopulate {@code salon-detail} with the
 *       pre-UPDATE average and pin it for the full TTL. {@code afterCompletion} also fires on
 *       rollback (so a failed recalc still drops the stale entry) and, unlike
 *       {@code afterCommit}, Spring SWALLOWS throwables raised from it — a CacheManager hiccup
 *       cannot turn a committed staff change into a 500.</li>
 * </ul>
 *
 * <p>The recalc is idempotent, so a publisher that emits on a staff change which provably cannot
 * move the number (a brand-new master row has no reviews at any salon) costs one cheap UPDATE and
 * is preferred over asking each call site to reason about whether it is a no-op.
 */
@Component
@RequiredArgsConstructor
public class SalonStaffRatingListener {

    private static final Logger log = LoggerFactory.getLogger(SalonStaffRatingListener.class);

    /** Public profile of one salon, keyed by salonId — see {@code SalonService#getSalonEntity}. */
    private static final String SALON_DETAIL_CACHE = "salon-detail";
    /** Salon review pages, keyed {@code "salon:<uuid>:sort:<sort>:page:<n>:size:<m>"}. */
    private static final String REVIEWS_BY_SALON_CACHE = "reviews-by-salon";

    private final RatingRecalculationService ratingRecalculationService;
    private final CacheManager cacheManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSalonStaffChanged(SalonStaffChangedEvent event) {
        UUID salonId = event.salonId();
        try {
            // Crosses a proxy: the REQUIRES_NEW boundary is on the collaborator, so its COMMIT —
            // and any UnexpectedRollbackException that commit raises — happens inside this try.
            ratingRecalculationService.recalculateSalonRating(salonId);
        } catch (Exception ex) {
            log.error("recalculateSalonRating failed after staff change for salon={} — {}",
                      salonId, ex.getClass().getSimpleName());
        }

        // TWO synchronizations, not one Runnable doing both evictions. Spring swallows throwables
        // raised from afterCompletion, which is what keeps a CacheManager fault from failing the
        // committed staff change — but a throwable from the FIRST call in a shared lambda also
        // strands every later call in it. Sharing one Runnable therefore meant a fault in
        // evictSalonDetail left `reviews-by-salon` serving pre-recalc pages for the full TTL while
        // `salon-detail` had already been dropped: the two caches disagreeing is strictly worse
        // than either staleness alone. Registered separately, each is contained on its own.
        //
        // salons.avg_rating / review_count live on the Salon ENTITY this cache stores
        // (SalonService#getSalonEntity), which PublicSalonResponse.from reads. Per-key, never
        // allEntries: one salon's staff change must not flush every other salon.
        evictAfterCompletion(() -> evictSalonDetail(salonId));
        // The salon review LIST is not itself filtered by staff membership, but its cached pages
        // are the surface a client sees next to the rating that just moved; dropping them keeps
        // the pair coherent for the same reason ReviewEventListener drops them.
        evictAfterCompletion(() -> evictSalonReviewPages(salonId));
    }

    /**
     * @see ReviewEventListener#onReviewCreated for why both properties are required.
     *
     * <p>Call once PER eviction, never once with a combined lambda — see {@link
     * #onSalonStaffChanged}. Since this listener no longer opens a transaction of its own, the
     * active synchronization here is the ALREADY-COMMITTED outer one whose {@code afterCommit}
     * dispatched this event; {@code AbstractPlatformTransactionManager} collects the
     * synchronizations for {@code triggerAfterCompletion} after {@code triggerAfterCommit}
     * returns, so a callback registered now still fires, immediately, and still fires after the
     * recalc transaction above has completed. The fall-through branch keeps the bean callable with
     * no transaction at all (unit tests), where it degrades to an immediate evict.
     */
    private void evictAfterCompletion(Runnable eviction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eviction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                eviction.run();
            }
        });
    }

    private void evictSalonDetail(UUID salonId) {
        org.springframework.cache.Cache springCache = cacheManager.getCache(SALON_DETAIL_CACHE);
        if (springCache == null) {
            log.warn("Cache '{}' not found during eviction for salon={}", SALON_DETAIL_CACHE, salonId);
            return;
        }
        springCache.evict(salonId);
    }

    // Prefix scan on "salon:<uuid>:" matches every sort dimension for that salon — the ':'
    // delimiter cannot appear inside a UUID. Same Caffeine coupling caveat as
    // ReviewEventListener#evictSalonReviewPages: getNativeCache() assumes the Caffeine backend.
    @SuppressWarnings("unchecked")
    private void evictSalonReviewPages(UUID salonId) {
        org.springframework.cache.Cache springCache = cacheManager.getCache(REVIEWS_BY_SALON_CACHE);
        if (springCache == null) {
            log.warn("Cache '{}' not found during eviction for salon={}",
                     REVIEWS_BY_SALON_CACHE, salonId);
            return;
        }
        Cache<Object, Object> nativeCache = (Cache<Object, Object>) springCache.getNativeCache();
        String prefix = "salon:" + salonId + ":";
        nativeCache.invalidateAll(
                nativeCache.asMap().keySet().stream()
                        .filter(k -> k instanceof String s && s.startsWith(prefix))
                        .toList()
        );
    }
}
