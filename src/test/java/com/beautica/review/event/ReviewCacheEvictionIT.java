package com.beautica.review.event;

import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.service.MasterService;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.service.ReviewService;
import com.beautica.review.support.AbstractRatingVisibilityIT;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.service.SalonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring proof for {@link ReviewEventListener}'s profile-cache eviction
 * (Phase 240 — {@code master-detail}, {@code master-detail-by-user}, {@code salon-detail}).
 *
 * <h2>The gap this closes</h2>
 * {@code ReviewEventListenerTest.CacheEvictionIsolationTest} drives the eviction by calling
 * {@code TransactionSynchronizationUtils.invokeAfterCompletion(...)} BY HAND
 * ({@code ReviewEventListenerTest:233}). That proves the callback <i>body</i> — which keys it
 * touches — and nothing about the wiring the entire fix rests on: that a
 * {@code @Transactional(REQUIRES_NEW)} nested inside a
 * {@code @TransactionalEventListener(AFTER_COMMIT)} really opens a synchronization scope whose
 * {@code afterCompletion} fires, and fires <b>after</b> {@code recalculateMasterRating}'s UPDATE
 * is committed and visible. Every one of the following would keep that unit test green while
 * shipping the original stale-rating bug to users:
 * <ul>
 *   <li>{@code @Transactional(REQUIRES_NEW)} dropped from {@code onReviewCreated} — no
 *       synchronization scope, so {@code isSynchronizationActive()} is false and the eviction
 *       degrades to an inline run BEFORE the rating UPDATE lands;</li>
 *   <li>{@code @TransactionalEventListener} downgraded to a plain {@code @EventListener} — the
 *       eviction runs inside the review INSERT transaction, so a concurrent reader repopulates
 *       the cache with the pre-review average and pins it for the full TTL;</li>
 *   <li>the event never published, or published with the wrong ids.</li>
 * </ul>
 *
 * <h2>What is asserted</h2>
 * Not "evict was called". Each test primes the real {@code @Cacheable} entry through the real
 * service (so the cached value provably carries the OLD rating), creates a review through the
 * real {@code ReviewService#createReview} transaction, then asserts BOTH that the cache entry is
 * gone AND that the next read returns the <b>recalculated</b> average. The second assertion is
 * the load-bearing one — an eviction that fired too early would leave a repopulated-stale entry
 * that the "is it gone" check alone cannot distinguish from a correct one.
 *
 * <p>Shape follows {@code com.beautica.user.UserCacheEvictionIT}, the existing precedent for
 * "post-commit synchronization actually fires" coverage.
 *
 * <p><b>Mutation-verified:</b> commenting out the three {@code evictKey(...)} calls in
 * {@code ReviewEventListener#onReviewCreated} turns both tests below RED on the fresh-average
 * assertions. See the Phase 240 QA record.
 */
@DisplayName("ReviewEventListener — profile caches are evicted after the rating UPDATE commits (real tx, real CacheConfig)")
class ReviewCacheEvictionIT extends AbstractRatingVisibilityIT {

    private static final Logger log = LoggerFactory.getLogger(ReviewCacheEvictionIT.class);

    private static final String MASTER_DETAIL_CACHE = "master-detail";
    private static final String MASTER_DETAIL_BY_USER_CACHE = "master-detail-by-user";
    private static final String SALON_DETAIL_CACHE = "salon-detail";

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private MasterService masterService;

    @Autowired
    private SalonService salonService;

    @Autowired
    private CacheManager cacheManager;

    // ── independent master — master-detail + master-detail-by-user ─────────────

    @Test
    @DisplayName("independent master — both profile caches drop their pre-review entry and the next read returns the recalculated 5.00")
    void should_returnRecalculatedAverageFromBothMasterCaches_when_reviewCommits() {
        String suffix = "-imc-" + System.nanoTime();
        UUID masterUserId = seedProviderUser("im" + suffix + "@beautica.test",
                "INDEPENDENT_MASTER", null);
        UUID masterId = seedMaster(masterUserId, null, "INDEPENDENT_MASTER");
        UUID masterServiceId = seedService("INDEPENDENT_MASTER", masterUserId, masterId);
        UUID clientId = seedClientUser("cli" + suffix + "@beautica.test");
        UUID bookingId = seedElapsedConfirmedBooking(clientId, masterId, masterServiceId, null);

        // ── 1. Prime both caches; the stored DTOs must carry the pre-review state ──
        MasterDetailResponse publicBefore = masterService.getMasterDetail(masterId);
        MasterDetailResponse selfBefore = masterService.getMyMasterDetail(masterUserId);

        assertThat(publicBefore.reviewCount())
                .as("priming read must capture the zero-review state; a non-zero count here would "
                    + "make the post-review assertion unable to tell fresh from stale")
                .isZero();
        assertThat(selfBefore.avgRating())
                .as("an unreviewed master's own profile carries a null average (Finding 3 "
                    + "normalisation), so a surviving stale entry is detectable below")
                .isNull();
        assertThat(cacheEntry(MASTER_DETAIL_CACHE, masterId))
                .as("master-detail must hold the primed entry under the masterId key — otherwise "
                    + "the eviction assertion would be vacuous")
                .isNotNull();
        assertThat(cacheEntry(MASTER_DETAIL_BY_USER_CACHE, masterUserId))
                .as("master-detail-by-user must hold the primed entry under the userId key — "
                    + "otherwise the eviction assertion would be vacuous")
                .isNotNull();

        // ── 2. Create the review through the real transactional service path ──
        log.debug("Act: create a rating-5 review on the elapsed CONFIRMED booking of an "
                  + "INDEPENDENT_MASTER whose two profile caches are primed with the zero-review state");
        reviewService.createReview(clientId, new CreateReviewRequest(bookingId, 5, "Все чудово"));

        // ── 3. Both entries must be gone… ──
        assertThat(cacheEntry(MASTER_DETAIL_CACHE, masterId))
                .as("master-detail entry MUST be evicted; a survivor means the afterCompletion "
                    + "callback registered by the REQUIRES_NEW transaction never fired")
                .isNull();
        assertThat(cacheEntry(MASTER_DETAIL_BY_USER_CACHE, masterUserId))
                .as("master-detail-by-user entry MUST be evicted — the provider's own "
                    + "GET /masters/me must not serve the pre-review rating for the 10-minute TTL")
                .isNull();

        // ── 4. …and the re-read must surface the RECALCULATED average, not a
        //        stale value repopulated by an eviction that fired too early. ──
        MasterDetailResponse publicAfter = masterService.getMasterDetail(masterId);
        MasterDetailResponse selfAfter = masterService.getMyMasterDetail(masterUserId);

        assertThat(publicAfter.avgRating())
                .as("GET /masters/{id} must return 5.00 immediately after the review commits, "
                    + "actual=%s — the pre-fix bug served the stale average for 5 minutes",
                        publicAfter.avgRating())
                .isEqualByComparingTo("5.00");
        assertThat(publicAfter.reviewCount())
                .as("reviewCount must be 1, actual=%s", publicAfter.reviewCount())
                .isEqualTo(1);
        assertThat(selfAfter.avgRating())
                .as("GET /masters/me must return 5.00 too, actual=%s — the same staleness class "
                    + "on the provider side of the transaction", selfAfter.avgRating())
                .isEqualByComparingTo("5.00");
        assertThat(selfAfter.reviewCount())
                .as("the provider's own reviewCount must be 1, actual=%s", selfAfter.reviewCount())
                .isEqualTo(1);
    }

    // ── salon master — adds the salon-detail branch ────────────────────────────

    @Test
    @DisplayName("salon master — salon-detail is evicted too and the salon entity re-reads at the recalculated 4.00")
    void should_returnRecalculatedSalonAverage_when_salonAffiliatedMasterIsReviewed() {
        String suffix = "-smc-" + System.nanoTime();
        UUID ownerId = seedProviderUser("own" + suffix + "@beautica.test", "SALON_OWNER", null);
        UUID salonId = seedSalon(ownerId);
        UUID masterUserId = seedProviderUser("sm" + suffix + "@beautica.test", "SALON_MASTER", salonId);
        UUID masterId = seedMaster(masterUserId, salonId, "SALON_MASTER");
        UUID masterServiceId = seedService("SALON", salonId, masterId);
        UUID clientId = seedClientUser("cli" + suffix + "@beautica.test");
        UUID bookingId = seedElapsedConfirmedBooking(clientId, masterId, masterServiceId, salonId);

        // ── 1. Prime all three caches ──
        masterService.getMasterDetail(masterId);
        masterService.getMyMasterDetail(masterUserId);
        Salon salonBefore = salonService.getSalonEntity(salonId);

        assertThat(salonBefore.getReviewCount())
                .as("priming read must capture the salon's zero-review state, actual=%s",
                        salonBefore.getReviewCount())
                .isZero();
        assertThat(cacheEntry(SALON_DETAIL_CACHE, salonId))
                .as("salon-detail must hold the primed Salon entity under the salonId key — "
                    + "otherwise the eviction assertion would be vacuous")
                .isNotNull();

        // ── 2. Review the salon-affiliated master ──
        log.debug("Act: create a rating-4 review on the elapsed CONFIRMED booking of a SALON_MASTER "
                  + "whose master profile and owning salon are both primed in cache");
        reviewService.createReview(clientId, new CreateReviewRequest(bookingId, 4, "Дуже добре"));

        // ── 3. All three entries gone ──
        assertThat(cacheEntry(MASTER_DETAIL_CACHE, masterId))
                .as("master-detail entry MUST be evicted for a salon-affiliated master too")
                .isNull();
        assertThat(cacheEntry(MASTER_DETAIL_BY_USER_CACHE, masterUserId))
                .as("master-detail-by-user entry MUST be evicted for a salon-affiliated master too")
                .isNull();
        assertThat(cacheEntry(SALON_DETAIL_CACHE, salonId))
                .as("salon-detail entry MUST be evicted — recalculateSalonRating updated the very "
                    + "columns PublicSalonResponse reads off this cached entity")
                .isNull();

        // ── 4. Fresh values on re-read ──
        MasterDetailResponse masterAfter = masterService.getMasterDetail(masterId);
        Salon salonAfter = salonService.getSalonEntity(salonId);

        assertThat(masterAfter.avgRating())
                .as("the master's public profile must show 4.00 immediately, actual=%s",
                        masterAfter.avgRating())
                .isEqualByComparingTo("4.00");
        assertThat(salonAfter.getAvgRating())
                .as("the salon profile must show 4.00 immediately, actual=%s — a stale 0.00 here "
                    + "means the salon-branch afterCompletion eviction never fired",
                        salonAfter.getAvgRating())
                .isEqualByComparingTo("4.00");
        assertThat(salonAfter.getReviewCount())
                .as("the salon's reviewCount must be 1, actual=%s", salonAfter.getReviewCount())
                .isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Object cacheEntry(String cacheName, UUID key) {
        Cache cache = cacheManager.getCache(cacheName);
        assertThat(cache)
                .as("cache '%s' must be registered by the real CacheConfig", cacheName)
                .isNotNull();
        Cache.ValueWrapper wrapper = cache.get(key);
        return wrapper == null ? null : wrapper.get();
    }
}
