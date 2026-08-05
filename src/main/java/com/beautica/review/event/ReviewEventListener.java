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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ReviewEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewEventListener.class);

    private final ReviewRepository reviewRepository;
    private final CacheManager cacheManager;

    /** Public profile of one master, keyed by masterId — see MasterService#getMasterDetail. */
    private static final String MASTER_DETAIL_CACHE = "master-detail";
    /**
     * The master's OWN profile ({@code GET /masters/me}), keyed by <b>userId</b> — see
     * {@code MasterService#getMyMasterDetail}, {@code @Cacheable(key = "#userId")}. Distinct key
     * space from {@link #MASTER_DETAIL_CACHE}; evicted with {@code event.masterUserId()}.
     */
    private static final String MASTER_DETAIL_BY_USER_CACHE = "master-detail-by-user";
    /** Public profile of one salon, keyed by salonId — see SalonService#getSalonEntity. */
    private static final String SALON_DETAIL_CACHE = "salon-detail";
    private static final String REVIEWS_BY_MASTER_CACHE = "reviews-by-master";
    private static final String REVIEWS_BY_SALON_CACHE = "reviews-by-salon";

    // Runs after the outer transaction (review INSERT) commits.
    // REQUIRES_NEW opens a separate transaction for the rating UPDATE so that a
    // failure there does not roll back the already-committed review.
    //
    // Eviction is registered as an afterCompletion callback on THIS (REQUIRES_NEW)
    // transaction, never executed inline. Two properties are load-bearing and the pair
    // is the reason afterCompletion is used rather than afterCommit or a bare call:
    //
    //  * AFTER the rating UPDATE is visible (Anti-Bug §F-2). Evicting inline evicts
    //    BEFORE this transaction commits, so a concurrent reader can miss, re-load the
    //    pre-UPDATE row and repopulate "master-detail" with the OLD average — pinned for
    //    the full 5-minute TTL. That is exactly the bug this listener now exists to fix,
    //    so an inline evict would make the popular-master case strictly worse.
    //  * UNCONDITIONAL. afterCompletion fires on rollback too, so a failed recalc still
    //    drops the cached entries rather than leaving a stale average reachable until TTL.
    //    (afterCommit would NOT fire on rollback — that is why it is not used here.)
    //
    // A THIRD property, surfaced by the Phase 240 re-audit, is the strongest of the three and
    // is the reason not to "simplify" this back to afterCommit even if the two above were
    // somehow satisfied: Spring SWALLOWS throwables from afterCompletion and PROPAGATES them
    // from afterCommit. AbstractPlatformTransactionManager.processCommit calls
    // triggerAfterCommit(status) inside a try whose finally is triggerAfterCompletion(...);
    // TransactionSynchronizationUtils.invokeAfterCompletion wraps each callback in
    // try/catch(Throwable) + log.error, whereas invokeAfterCommit has no catch at all. So under
    // afterCommit a CacheManager hiccup — a Caffeine/backing-store failure, a misconfigured
    // cache — would escape the completion path and turn an ALREADY-COMMITTED, fully successful
    // review POST into a 500 for the client, with the review row silently persisted. Cache
    // invalidation is a best-effort side-effect of a committed write; it must never be able to
    // fail that write's response. afterCompletion gives exactly that containment for free.
    //
    // The isSynchronizationActive() guard keeps the method callable outside a transaction
    // (unit tests invoke the bean directly), where it degrades to an immediate evict.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReviewCreated(ReviewCreatedEvent event) {
        try {
            reviewRepository.recalculateMasterRating(event.masterId());
        } catch (Exception ex) {
            log.error("recalculateMasterRating failed for master={} — {}",
                      event.masterId(), ex.getClass().getSimpleName());
        }
        evictAfterCompletion(() -> {
            evictMasterReviewPages(event.masterId());
            // Phase 240 audit (Finding 1). Previously skipped deliberately, on the ground
            // that the 5-minute TTL was "an accepted staleness bound". That ground no
            // longer holds: BookingDetailResponse.masterAvgRating now serves the master's
            // rating LIVE off the booking read, so leaving the profile cached made the
            // same master show a new rating on the booking card and the old one on
            // GET /masters/{masterId} for up to five minutes — the precise "I left a
            // review and nothing changed" symptom this track is closing.
            //
            // The MasterService#getMasterDetail Javadoc's own reason for not evicting
            // ("callers hold only userId, not masterId") never applied here — the event
            // carries masterId, which IS this cache's key.
            //
            // Per-key, never allEntries: one master's review must not flush every other
            // master's cached profile.
            evictKey(MASTER_DETAIL_CACHE, event.masterId());
            // Phase 240 RE-audit (Finding 1). The userId-keyed twin of master-detail, backing
            // the master's own GET /masters/me. Previously skipped on the ground that the event
            // "does not carry a userId" — that blocker was removed rather than accepted: the
            // publisher already holds the JOIN FETCH-ed user (ReviewService#createReview), so
            // carrying masterUserId costs no lazy load and no extra query. Without this evict the
            // provider saw their pre-review rating on their OWN profile for up to the 10-minute
            // TTL (longer than master-detail's 5) while every client saw the new one — the same
            // symptom class as F1, on the other side of the transaction.
            //
            // Key shape is the raw UUID: MasterService#getMyMasterDetail declares
            // @Cacheable(value = "master-detail-by-user", key = "#userId"), i.e. the userId
            // itself, NOT a composite and NOT a string. Matches how UserService and
            // MasterService#rotateMasterSalon evict this same cache.
            evictKey(MASTER_DETAIL_BY_USER_CACHE, event.masterUserId());
        });

        // Symmetric salon branch (Phase 13.6): only runs when the reviewed booking
        // belonged to a salon-affiliated master. Same fire-and-log-don't-fail contract —
        // a salon-recalc failure must never roll back the already-committed review, and
        // this method already runs in its own REQUIRES_NEW transaction.
        if (event.salonId() != null) {
            try {
                reviewRepository.recalculateSalonRating(event.salonId());
            } catch (Exception ex) {
                log.error("recalculateSalonRating failed for salon={} — {}",
                          event.salonId(), ex.getClass().getSimpleName());
            }
            evictAfterCompletion(() -> {
                evictSalonReviewPages(event.salonId());
                // Mirrors the master branch: recalculateSalonRating updates
                // salons.avg_rating / review_count, and "salon-detail" caches the Salon
                // ENTITY those columns live on (SalonService#getSalonEntity), which
                // PublicSalonResponse.from reads to build the public salon profile.
                // Without this evict the salon profile keeps the pre-review average for
                // the cache's 5-minute TTL.
                evictKey(SALON_DETAIL_CACHE, event.salonId());
            });
        }
    }

    /**
     * Registers {@code eviction} to run when the surrounding transaction completes —
     * committed or rolled back — falling back to running it immediately when this bean is
     * invoked outside a transaction. See {@link #onReviewCreated} for why both properties
     * (post-commit ordering AND unconditional execution) are required.
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

    /**
     * Evicts a single entry by key; a missing cache or a null key is logged, never fatal.
     *
     * <h4>Phase 240 re-audit, Finding 2 — ACCEPTED, deliberately not fixed</h4>
     *
     * <p><b>The hazard is real.</b> Both {@code master-detail}/{@code master-detail-by-user}
     * ({@code MasterService}) and {@code salon-detail} ({@code SalonService#getSalonEntity}) are
     * {@code @Cacheable(sync = true)}. Spring's {@code CacheInterceptor} implements {@code sync}
     * as {@code CaffeineCache.get(key, valueLoader)} → {@code Caffeine#get(key, mappingFunction)}
     * → {@code ConcurrentHashMap#computeIfAbsent}, which holds the hash BIN monitor for the whole
     * loader invocation. Hibernate acquires its JDBC connection lazily at first statement
     * (DELAYED_ACQUISITION), so that loader waits on the Hikari pool <i>while holding the bin</i>.
     * This method's {@code springCache.evict(key)} → {@code ConcurrentHashMap#remove} needs the
     * same bin. It runs from {@code afterCompletion}, which
     * {@code AbstractPlatformTransactionManager} fires in an INNER finally, while
     * {@code cleanupAfterCompletion} → {@code doCleanupAfterCompletion} (EntityManager/connection
     * release) only runs in the OUTER one. So under pool saturation the review submitter can park
     * holding a connection behind a reader waiting for one — reachable unauthenticated via
     * {@code GET /api/v1/salons/{salonId}} ({@code SecurityConfig} {@code permitAll}).
     *
     * <p><b>Why it is accepted anyway.</b> Bounded and self-healing: the blocking reader's own
     * {@code spring.datasource.hikari.connection-timeout} (20 000 ms, {@code application.yml})
     * caps the wait, after which it throws, releases the bin, and this evict proceeds. It creates
     * no NEW connection demand — it delays the release of one already-held connection, and only
     * once the pool is already exhausted (a state that is already failing requests). Note also
     * that a same-key collision is the harmless case: if the loader is inside
     * {@code computeIfAbsent} the entry was absent, so the evict is a no-op once it acquires.
     *
     * <p><b>Why each alternative is worse.</b>
     * <ol>
     *   <li><i>Evict asynchronously.</i> Ordering itself would survive (a task submitted from
     *       {@code afterCompletion} can only run later than the commit), but §H-2 forbids
     *       {@code CallerRunsPolicy} on a dedicated executor, and {@code AbortPolicy} DROPS the
     *       eviction when the queue saturates — under precisely the same overload that triggers
     *       this finding. That trades a ≤20 s bounded stall for a stale rating pinned for the
     *       full 5–10 min TTL, i.e. re-opens F1, and downgrades the unconditional-on-rollback
     *       guarantee above to best-effort. Strictly worse.</li>
     *   <li><i>A hook after connection release.</i> None exists on this thread. Worse, this
     *       listener is {@code @TransactionalEventListener(AFTER_COMMIT)}, so the outer
     *       review-INSERT transaction's connection is ALSO still held; deferring past the inner
     *       REQUIRES_NEW transaction would not help.</li>
     *   <li><i>Evict from the controller, after {@code createReview} returns.</i> This genuinely
     *       removes the hazard (both transactions cleaned up) but puts cache invalidation in the
     *       HTTP layer, decouples the eviction from the rating UPDATE it must accompany, and is
     *       silently lost by any future second caller of {@code createReview}.</li>
     * </ol>
     *
     * <p><b>The {@code afterCompletion} ordering guarantee is therefore preserved unchanged</b> —
     * this finding produced no code change on the eviction path.
     */
    private void evictKey(String cacheName, UUID key) {
        if (key == null) {
            log.warn("Skipping eviction from cache '{}' — null key (event published incomplete)",
                     cacheName);
            return;
        }
        org.springframework.cache.Cache springCache = cacheManager.getCache(cacheName);
        if (springCache == null) {
            log.warn("Cache '{}' not found during eviction for key={}", cacheName, key);
            return;
        }
        springCache.evict(key);
    }

    // Evicts only the cached review pages belonging to the given master.
    //
    // Cache keys use the format "master:<uuid>:page:<n>:size:<m>" (set in ReviewService).
    // Prefix scan on "master:<uuid>:" is safe — the UUID delimiter ':' cannot appear inside a UUID.
    //
    // Coupling note: getNativeCache() returns Caffeine's public Cache<Object,Object>.
    // If the cache manager backend ever changes away from Caffeine, this method must be updated.
    @SuppressWarnings("unchecked")
    private void evictMasterReviewPages(UUID masterId) {
        org.springframework.cache.Cache springCache = cacheManager.getCache(REVIEWS_BY_MASTER_CACHE);
        if (springCache == null) {
            log.warn("Cache '{}' not found during eviction for master={}",
                     REVIEWS_BY_MASTER_CACHE, masterId);
            return;
        }
        Cache<Object, Object> nativeCache = (Cache<Object, Object>) springCache.getNativeCache();
        String prefix = "master:" + masterId + ":";
        nativeCache.invalidateAll(
                nativeCache.asMap().keySet().stream()
                        .filter(k -> k instanceof String s && s.startsWith(prefix))
                        .toList()
        );
    }

    // Evicts only the cached review pages belonging to the given salon.
    //
    // Cache keys use the format "salon:<uuid>:sort:<sort>:page:<n>:size:<m>" (set in
    // ReviewService#getSalonReviews). Prefix scan on "salon:<uuid>:" matches every sort
    // dimension for that salon — the UUID delimiter ':' cannot appear inside a UUID.
    // Same Caffeine-coupling caveat as evictMasterReviewPages above.
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
