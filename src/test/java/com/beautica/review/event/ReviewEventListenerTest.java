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
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, null);

        doThrow(new RuntimeException("simulated DB timeout"))
                .when(reviewRepository).recalculateMasterRating(masterId);
        // cacheManager.getCache() is called inside afterCommit(), which only runs on real TX commit.
        // It is not invoked in this unit test, so no stub is needed.

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
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, null);
        // No stub for reviewRepository.recalculateMasterRating — default Mockito void stub is fine.
        // No stub for cacheManager — afterCommit() only fires on real TX commit.

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
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, salonId);

        reviewEventListener.onReviewCreated(event);

        verify(reviewRepository).recalculateSalonRating(salonId);
    }

    @Test
    @DisplayName("should_notCallRecalculateSalonRating_when_eventSalonIdIsNull")
    void should_notCallRecalculateSalonRating_when_eventSalonIdIsNull() {
        UUID masterId = UUID.randomUUID();
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, null);

        reviewEventListener.onReviewCreated(event);

        verify(reviewRepository, never()).recalculateSalonRating(any());
    }

    @Test
    @DisplayName("should_logError_when_recalculateSalonRatingThrows")
    void should_logError_when_recalculateSalonRatingThrows() {
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, salonId);

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
        ReviewCreatedEvent event = new ReviewCreatedEvent(masterId, salonId);

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
            org.springframework.cache.Cache c = cacheManager.getCache("reviews-by-master");
            if (c != null) c.clear();
            org.springframework.cache.Cache s = cacheManager.getCache("reviews-by-salon");
            if (s != null) s.clear();
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
            reviewEventListener.onReviewCreated(new ReviewCreatedEvent(masterA, null));

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
            reviewEventListener.onReviewCreated(new ReviewCreatedEvent(UUID.randomUUID(), salonA));

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
            reviewEventListener.onReviewCreated(new ReviewCreatedEvent(UUID.randomUUID(), null));

            // Assert — unrelated salon entry is untouched
            assertThat(nativeCache.getIfPresent(keyA))
                    .as("a null-salonId event must not touch any reviews-by-salon entry")
                    .isNotNull();
        }
    }
}
