package com.beautica.review.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.config.CacheConfig;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.review.service.ReviewService;
import com.beautica.booking.repository.BookingRepository;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("ReviewEventListener — unit")
@ExtendWith(MockitoExtension.class)
class ReviewEventListenerTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private ReviewEventListener reviewEventListener;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger listenerLogger;

    @BeforeEach
    void setUp() {
        // onReviewCreated calls TransactionSynchronizationManager.registerSynchronization(),
        // which requires an active synchronization context. Initialize it here to mirror
        // the REQUIRES_NEW transactional context the method runs in during production.
        TransactionSynchronizationManager.initSynchronization();

        listenerLogger = (Logger) LoggerFactory.getLogger(ReviewEventListener.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        listenerLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
        listenerLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    // ── onReviewCreated — rating recalculation failure ─────────────────────────

    @Test
    @DisplayName("should_logError_when_recalculateMasterRatingThrows")
    void should_logError_when_recalculateMasterRatingThrows() {
        // Arrange
        UUID masterId = UUID.randomUUID();
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, UUID.randomUUID(), null, null);

        doThrow(new RuntimeException("simulated DB timeout"))
                .when(reviewRepository).recalculateMasterRating(masterId);
        // cacheManager.getCache() is reached only from the afterCompletion callback, which fires
        // when a REAL transaction completes. initSynchronization() above makes the callback
        // register but never fire, so no cacheManager stub is needed here. The eviction itself is
        // covered against a real Caffeine cache in the nested CacheEvictionIsolationTest below.

        // Act
        assertThatNoException()
                .as("exception from recalculateMasterRating must not propagate out of onReviewCreated")
                .isThrownBy(() -> reviewEventListener.onReviewCreated(event));

        // Assert
        assertThat(listAppender.list)
                .as("an ERROR log entry must be emitted when recalculateMasterRating throws")
                .anyMatch(e ->
                        e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains(masterId.toString())
                        && e.getFormattedMessage().contains("RuntimeException")
                        && !e.getFormattedMessage().contains("simulated DB timeout"));
    }

    @Test
    @DisplayName("should_notLogError_when_recalculateMasterRatingSucceeds")
    void should_notLogError_when_recalculateMasterRatingSucceeds() {
        // Arrange
        UUID masterId = UUID.randomUUID();
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, UUID.randomUUID(), null, null);
        // No stub for reviewRepository.recalculateMasterRating — default Mockito void stub is fine.
        // No stub for cacheManager — the afterCompletion callback only fires on real TX completion.

        // Act + Assert
        assertThatNoException().isThrownBy(() -> reviewEventListener.onReviewCreated(event));

        assertThat(listAppender.list)
                .as("no ERROR log must be emitted on success path")
                .noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    // ── onReviewCreated — salon rating recalculation (Phase 13.6) ──────────────

    @Test
    @DisplayName("should_callRecalculateSalonRating_when_eventCarriesSalonId")
    void should_callRecalculateSalonRating_when_eventCarriesSalonId() {
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, UUID.randomUUID(), salonId, null);

        reviewEventListener.onReviewCreated(event);

        verify(reviewRepository).recalculateSalonRating(salonId);
    }

    @Test
    @DisplayName("should_notCallRecalculateSalonRating_when_eventSalonIdIsNull")
    void should_notCallRecalculateSalonRating_when_eventSalonIdIsNull() {
        UUID masterId = UUID.randomUUID();
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, UUID.randomUUID(), null, null);

        reviewEventListener.onReviewCreated(event);

        verify(reviewRepository, never()).recalculateSalonRating(any());
    }

    @Test
    @DisplayName("should_logError_when_recalculateSalonRatingThrows")
    void should_logError_when_recalculateSalonRatingThrows() {
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, UUID.randomUUID(), salonId, null);

        doThrow(new RuntimeException("simulated DB timeout"))
                .when(reviewRepository).recalculateSalonRating(salonId);

        assertThatNoException()
                .as("exception from recalculateSalonRating must not propagate out of onReviewCreated")
                .isThrownBy(() -> reviewEventListener.onReviewCreated(event));

        assertThat(listAppender.list)
                .as("an ERROR log entry must be emitted when recalculateSalonRating throws")
                .anyMatch(e ->
                        e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains(salonId.toString())
                        && e.getFormattedMessage().contains("RuntimeException")
                        && !e.getFormattedMessage().contains("simulated DB timeout"));
    }

    @Test
    @DisplayName("should_stillRecalculateMasterRating_when_salonRecalcThrows")
    void should_stillRecalculateMasterRating_when_salonRecalcThrows() {
        // A salon-recalc failure must not prevent (or be prevented by) the master branch —
        // the two are independent try/catch blocks.
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, UUID.randomUUID(), salonId, null);

        doThrow(new RuntimeException("simulated DB timeout"))
                .when(reviewRepository).recalculateSalonRating(salonId);

        reviewEventListener.onReviewCreated(event);

        verify(reviewRepository).recalculateMasterRating(masterId);
    }

    // ── Cache eviction isolation (FIX 14) ─────────────────────────────────────
    //
    // This nested class uses a real CacheManager (Caffeine) to verify that evicting
    // masterA's cache entries does NOT evict masterB's entries.
    // It follows the same pattern as ReviewServiceCacheTest.
    //
    @Nested
    @SpringBootTest(
            classes = {ReviewEventListener.class, CacheConfig.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE
    )
    @DisplayName("evictMasterReviewPages — per-master isolation with real Caffeine cache")
    class CacheEvictionIsolationTest {

        @MockBean ReviewRepository         reviewRepository;
        @MockBean ApplicationEventPublisher eventPublisher;

        @Autowired ReviewEventListener reviewEventListener;
        @Autowired CacheManager        cacheManager;

        @BeforeEach
        void clearCache() {
            for (String name : List.of("reviews-by-master", "reviews-by-salon",
                                       "master-detail", "master-detail-by-user", "salon-detail")) {
                org.springframework.cache.Cache c = cacheManager.getCache(name);
                if (c != null) c.clear();
            }
        }

        /**
         * Fires the listener, then completes the ambient synchronization context the way a
         * really-committed transaction would.
         *
         * <p>Load-bearing, not ceremony. {@code onReviewCreated} does NOT evict inline — it
         * registers an {@code afterCompletion} callback so eviction lands after the rating
         * UPDATE is visible (Anti-Bug §F-2). JUnit propagates the enclosing class's
         * {@code @BeforeEach} into {@code @Nested}, so a synchronization context is already
         * open here, faithfully mirroring the production {@code REQUIRES_NEW} transaction.
         * Calling {@code onReviewCreated} bare would therefore evict nothing and every
         * assertion below would pass vacuously against a listener that never evicts.
         */
        private void fireReviewCreated(ReviewCreatedEvent event) {
            reviewEventListener.onReviewCreated(event);
            TransactionSynchronizationUtils.invokeAfterCompletion(
                    TransactionSynchronizationManager.getSynchronizations(),
                    TransactionSynchronization.STATUS_COMMITTED);
        }

        @Test
        @DisplayName("should_notEvictMasterB_when_evictingMasterA")
        void should_notEvictMasterB_when_evictingMasterA() {
            // Arrange — obtain native Caffeine cache and manually populate two entries
            org.springframework.cache.Cache springCache = cacheManager.getCache("reviews-by-master");
            assertThat(springCache).isNotNull();

            @SuppressWarnings("unchecked")
            Cache<Object, Object> nativeCache = (Cache<Object, Object>) springCache.getNativeCache();

            UUID masterA = UUID.randomUUID();
            UUID masterB = UUID.randomUUID();

            // Populate cache with String keys matching the format used by ReviewService.getReviewsForMaster:
            // "'master:' + #masterId + ':page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize"
            String keyA = "master:" + masterA + ":page:0:size:20";
            String keyB = "master:" + masterB + ":page:0:size:20";

            nativeCache.put(keyA, "page-content-for-A");
            nativeCache.put(keyB, "page-content-for-B");

            assertThat(nativeCache.getIfPresent(keyA)).isNotNull();
            assertThat(nativeCache.getIfPresent(keyB)).isNotNull();

            // Act — fire a ReviewCreatedEvent for masterA only
            // Call evictMasterReviewPages indirectly via onReviewCreated.
            // The listener calls reviewRepository.recalculateMasterRating (which we ignore)
            // then calls evictMasterReviewPages(masterA).
            fireReviewCreated(new ReviewCreatedEvent(masterA, UUID.randomUUID(), null, null));

            // Assert — masterA's entry is gone; masterB's entry is intact
            assertThat(nativeCache.getIfPresent(keyA))
                    .as("masterA's cache entry must be evicted after ReviewCreatedEvent for masterA")
                    .isNull();
            assertThat(nativeCache.getIfPresent(keyB))
                    .as("masterB's cache entry must NOT be evicted by an event for masterA")
                    .isNotNull();
        }

        // ── evictSalonReviewPages — per-salon isolation (Finding 1 — perf follow-up) ──

        @Test
        @DisplayName("should_notEvictSalonB_when_evictingSalonA")
        void should_notEvictSalonB_when_evictingSalonA() {
            // Arrange — obtain native Caffeine cache and manually populate two entries
            org.springframework.cache.Cache springCache = cacheManager.getCache("reviews-by-salon");
            assertThat(springCache).isNotNull();

            @SuppressWarnings("unchecked")
            Cache<Object, Object> nativeCache = (Cache<Object, Object>) springCache.getNativeCache();

            UUID salonA = UUID.randomUUID();
            UUID salonB = UUID.randomUUID();

            // Populate cache with String keys matching the format used by
            // ReviewService.getSalonReviews:
            // "'salon:' + #salonId + ':sort:' + #sort + ':page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize"
            String keyA = "salon:" + salonA + ":sort:NEWEST:page:0:size:20";
            String keyB = "salon:" + salonB + ":sort:NEWEST:page:0:size:20";

            nativeCache.put(keyA, "page-content-for-A");
            nativeCache.put(keyB, "page-content-for-B");

            assertThat(nativeCache.getIfPresent(keyA)).isNotNull();
            assertThat(nativeCache.getIfPresent(keyB)).isNotNull();

            // Act — fire a ReviewCreatedEvent carrying salonA only
            fireReviewCreated(new ReviewCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), salonA, null));

            // Assert — salonA's entry is gone; salonB's entry is intact
            assertThat(nativeCache.getIfPresent(keyA))
                    .as("salonA's cache entry must be evicted after a ReviewCreatedEvent carrying salonA")
                    .isNull();
            assertThat(nativeCache.getIfPresent(keyB))
                    .as("salonB's cache entry must NOT be evicted by an event for salonA")
                    .isNotNull();
        }

        @Test
        @DisplayName("should_notTouchSalonCache_when_eventSalonIdIsNull")
        void should_notTouchSalonCache_when_eventSalonIdIsNull() {
            // Arrange
            org.springframework.cache.Cache springCache = cacheManager.getCache("reviews-by-salon");
            assertThat(springCache).isNotNull();

            @SuppressWarnings("unchecked")
            Cache<Object, Object> nativeCache = (Cache<Object, Object>) springCache.getNativeCache();

            UUID salonA = UUID.randomUUID();
            String keyA = "salon:" + salonA + ":sort:NEWEST:page:0:size:20";
            nativeCache.put(keyA, "page-content-for-A");

            // Act — an INDEPENDENT_MASTER booking review (no salon)
            fireReviewCreated(new ReviewCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), null, null));

            // Assert — unrelated salon entry is untouched
            assertThat(nativeCache.getIfPresent(keyA))
                    .as("a null-salonId event must not touch any reviews-by-salon entry")
                    .isNotNull();
        }

        // ── master-detail / salon-detail eviction (Phase 240 audit, Finding 1) ─────────
        //
        // These caches back the PUBLIC profile reads (GET /masters/{id}, the salon profile).
        // Before this fix they were deliberately left to expire on their own 5-minute TTL, so
        // a client who had just left a review saw the NEW average on their booking (read live)
        // and the OLD one on the master's profile — the exact "I reviewed and nothing changed"
        // report that opened this track. Per-key, never allEntries: one master's review must
        // not flush every other master's cached profile.

        @Test
        @DisplayName("should_evictMasterDetailEntry_when_reviewCreatedForThatMaster")
        void should_evictMasterDetailEntry_when_reviewCreatedForThatMaster() {
            // Arrange — two cached master profiles; only one is reviewed
            org.springframework.cache.Cache detail = cacheManager.getCache("master-detail");
            assertThat(detail).isNotNull();

            UUID reviewedMaster = UUID.randomUUID();
            UUID bystanderMaster = UUID.randomUUID();
            // Key shape is MasterService#getMasterDetail's `key = "#masterId"` — the raw UUID.
            detail.put(reviewedMaster, "cached-profile-of-reviewed-master");
            detail.put(bystanderMaster, "cached-profile-of-bystander");

            // Act
            fireReviewCreated(new ReviewCreatedEvent(reviewedMaster, UUID.randomUUID(), null, null));

            // Assert
            assertThat(detail.get(reviewedMaster))
                    .as("the reviewed master's cached profile must be dropped so the next "
                        + "GET /masters/{id} rebuilds it with the recalculated average")
                    .isNull();
            assertThat(detail.get(bystanderMaster))
                    .as("eviction is per-key — an unrelated master's profile must survive")
                    .isNotNull();
        }

        @Test
        @DisplayName("should_evictMasterDetailByUserEntry_when_reviewCreatedForThatMaster")
        void should_evictMasterDetailByUserEntry_when_reviewCreatedForThatMaster() {
            // Phase 240 re-audit, Finding 1. The provider's OWN GET /masters/me must not keep
            // serving the pre-review rating for the cache's 10-minute TTL while every client
            // already sees the recalculated one.
            org.springframework.cache.Cache byUser = cacheManager.getCache("master-detail-by-user");
            assertThat(byUser).isNotNull();

            UUID reviewedMasterId = UUID.randomUUID();
            UUID reviewedMasterUserId = UUID.randomUUID();
            UUID bystanderUserId = UUID.randomUUID();

            // Key shape is MasterService#getMyMasterDetail's `key = "#userId"` — the raw userId.
            byUser.put(reviewedMasterUserId, "cached-self-view-of-reviewed-master");
            byUser.put(bystanderUserId, "cached-self-view-of-bystander");
            // Same cache, keyed with the MASTER id instead: a listener that evicted the wrong
            // identifier would drop this entry and leave the real one above intact. Deliberately
            // distinct UUIDs — reusing one value for both would make the swap undetectable.
            byUser.put(reviewedMasterId, "wrong-key-space-canary");

            // Act
            fireReviewCreated(new ReviewCreatedEvent(reviewedMasterId, reviewedMasterUserId, null, null));

            // Assert
            assertThat(byUser.get(reviewedMasterUserId))
                    .as("the reviewed master's own cached profile must be dropped so the next "
                        + "GET /masters/me rebuilds it with the recalculated average")
                    .isNull();
            assertThat(byUser.get(reviewedMasterId))
                    .as("eviction must use masterUserId, not masterId — this cache is keyed by "
                        + "userId, so a masterId-keyed evict would hit the wrong key space")
                    .isNotNull();
            assertThat(byUser.get(bystanderUserId))
                    .as("eviction is per-key — an unrelated master's self-view must survive")
                    .isNotNull();
        }

        @Test
        @DisplayName("should_notThrowAndNotEvict_when_eventCarriesNullMasterUserId")
        void should_notThrowAndNotEvict_when_eventCarriesNullMasterUserId() {
            // Defensive branch in evictKey: Caffeine's invalidate(null) throws NPE, and an NPE
            // escaping the afterCompletion lambda would abort the remaining evictions in it.
            org.springframework.cache.Cache byUser = cacheManager.getCache("master-detail-by-user");
            assertThat(byUser).isNotNull();

            // NOTE on what makes this test non-vacuous: TransactionSynchronizationUtils
            // .invokeAfterCompletion swallows Throwable, so "no exception escaped" and "the
            // bystander survived" would BOTH hold even with the guard removed — an NPE would just
            // be logged on Spring's own logger and lost. The load-bearing assertion is therefore
            // the WARN on the ReviewEventListener logger: it is emitted only by the guard.
            org.springframework.cache.Cache masterDetail = cacheManager.getCache("master-detail");
            assertThat(masterDetail).isNotNull();

            UUID masterId = UUID.randomUUID();
            UUID bystanderUserId = UUID.randomUUID();
            byUser.put(bystanderUserId, "cached-self-view-of-bystander");
            masterDetail.put(masterId, "cached-public-profile");

            fireReviewCreated(new ReviewCreatedEvent(masterId, null, null, null));

            assertThat(listAppender.list)
                    .as("a null key must be reported and skipped by evictKey's own guard, not "
                        + "reach Caffeine's invalidate(null) and NPE inside the callback")
                    .anyMatch(e -> e.getLevel() == Level.WARN
                                   && e.getFormattedMessage().contains("master-detail-by-user"));
            assertThat(masterDetail.get(masterId))
                    .as("the guard must skip only the null-keyed evict — the sibling "
                        + "master-detail eviction in the same callback still has to land")
                    .isNull();
            assertThat(byUser.get(bystanderUserId))
                    .as("a null key must skip the evict entirely, never fall back to a blanket clear")
                    .isNotNull();
        }

        @Test
        @DisplayName("should_evictSalonDetailEntry_when_eventCarriesSalonId")
        void should_evictSalonDetailEntry_when_eventCarriesSalonId() {
            org.springframework.cache.Cache detail = cacheManager.getCache("salon-detail");
            assertThat(detail).isNotNull();

            UUID reviewedSalon = UUID.randomUUID();
            UUID bystanderSalon = UUID.randomUUID();
            // Key shape is SalonService#getSalonEntity's `key = "#salonId"` — the raw UUID.
            detail.put(reviewedSalon, "cached-salon-entity");
            detail.put(bystanderSalon, "cached-bystander-salon-entity");

            fireReviewCreated(new ReviewCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), reviewedSalon, null));

            assertThat(detail.get(reviewedSalon))
                    .as("recalculateSalonRating updated salons.avg_rating — the cached Salon "
                        + "entity PublicSalonResponse reads it from must not survive")
                    .isNull();
            assertThat(detail.get(bystanderSalon))
                    .as("eviction is per-key — an unrelated salon must survive")
                    .isNotNull();
        }

        @Test
        @DisplayName("should_notTouchSalonDetail_when_eventSalonIdIsNull")
        void should_notTouchSalonDetail_when_eventSalonIdIsNull() {
            org.springframework.cache.Cache detail = cacheManager.getCache("salon-detail");
            assertThat(detail).isNotNull();

            UUID salonId = UUID.randomUUID();
            detail.put(salonId, "cached-salon-entity");

            // An INDEPENDENT_MASTER review carries no salon — the salon branch must not run.
            fireReviewCreated(new ReviewCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), null, null));

            assertThat(detail.get(salonId))
                    .as("a null-salonId event must not evict any salon-detail entry")
                    .isNotNull();
        }

        @Test
        @DisplayName("should_stillEvictMasterDetail_when_recalculateMasterRatingThrows")
        void should_stillEvictMasterDetail_when_recalculateMasterRatingThrows() {
            // The eviction contract is UNCONDITIONAL: a failed recalculation must still drop the
            // cached profile rather than leave a stale average reachable for the full TTL. This
            // is why the callback is registered on afterCompletion, not afterCommit.
            org.springframework.cache.Cache detail = cacheManager.getCache("master-detail");
            assertThat(detail).isNotNull();

            UUID masterId = UUID.randomUUID();
            detail.put(masterId, "cached-profile");
            doThrow(new RuntimeException("simulated DB timeout"))
                    .when(reviewRepository).recalculateMasterRating(masterId);

            fireReviewCreated(new ReviewCreatedEvent(masterId, UUID.randomUUID(), null, null));

            assertThat(detail.get(masterId))
                    .as("a failed recalc must not leave the pre-review average cached")
                    .isNull();
        }
    }
}
