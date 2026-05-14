package com.beautica.review.service;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.config.CacheConfig;
import com.beautica.master.entity.Master;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.entity.Review;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {ReviewService.class, CacheConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("ReviewService — @Cacheable cache-hit behaviour")
class ReviewServiceCacheTest {

    @MockBean ReviewRepository reviewRepository;
    @MockBean BookingRepository bookingRepository;
    @MockBean ApplicationEventPublisher eventPublisher;

    @Autowired ReviewService reviewService;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCaches() {
        Cache reviewDetail = cacheManager.getCache("review-detail");
        if (reviewDetail != null) reviewDetail.clear();

        Cache reviewsByMaster = cacheManager.getCache("reviews-by-master");
        if (reviewsByMaster != null) reviewsByMaster.clear();
    }

    @Test
    @DisplayName("returns cached result on second call for same review ID — repository hit count must be exactly one")
    void should_returnCachedResult_when_getReviewCalledTwiceWithSameId() {
        UUID reviewId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        // Arrange
        User client = mock(User.class);
        when(client.getFirstName()).thenReturn("Anna");
        when(client.getLastName()).thenReturn("Koval");

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);

        Review review = mock(Review.class);
        when(review.getId()).thenReturn(reviewId);
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getRating()).thenReturn((short) 5);
        when(review.getComment()).thenReturn("Excellent");
        when(review.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        when(reviewRepository.findByIdWithAssociations(reviewId)).thenReturn(Optional.of(review));

        // Act — call twice with the same ID
        ReviewResponse first = reviewService.getReview(reviewId);
        ReviewResponse second = reviewService.getReview(reviewId);

        // Assert — repository must be called exactly once; second result is served from cache
        verify(reviewRepository, times(1)).findByIdWithAssociations(reviewId);
        assertThat(second).isEqualTo(first);
        assertThat(second.id()).isEqualTo(reviewId);
        assertThat(second.masterId()).isEqualTo(masterId);
    }

    @Test
    @DisplayName("does not collide cache entries across different review IDs — each ID hits the repository once")
    void should_hitRepositoryTwice_when_getReviewCalledWithDifferentIds() {
        UUID reviewId1 = UUID.randomUUID();
        UUID reviewId2 = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        // Arrange
        User client = mock(User.class);
        when(client.getFirstName()).thenReturn("Ivan");
        when(client.getLastName()).thenReturn("Petrenko");

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);

        Review review1 = buildReview(reviewId1, client, master, (short) 4, "Good");
        Review review2 = buildReview(reviewId2, client, master, (short) 3, "Average");

        when(reviewRepository.findByIdWithAssociations(reviewId1)).thenReturn(Optional.of(review1));
        when(reviewRepository.findByIdWithAssociations(reviewId2)).thenReturn(Optional.of(review2));

        // Act — different IDs — both must reach the repository (no cache collision)
        ReviewResponse first = reviewService.getReview(reviewId1);
        ReviewResponse second = reviewService.getReview(reviewId2);

        // Assert — each distinct ID hits the repository exactly once
        verify(reviewRepository, times(1)).findByIdWithAssociations(reviewId1);
        verify(reviewRepository, times(1)).findByIdWithAssociations(reviewId2);
        assertThat(first.id()).isEqualTo(reviewId1);
        assertThat(second.id()).isEqualTo(reviewId2);
    }

    // ── reviews-by-master cache (FIX 13) ─────────────────────────────────────

    @Test
    @DisplayName("should_returnCachedPage_when_getReviewsForMasterCalledTwice")
    void should_returnCachedPage_when_getReviewsForMasterCalledTwice() {
        UUID masterId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);

        // Arrange — one review page from repository
        User client = mock(User.class);
        when(client.getFirstName()).thenReturn("Olha");
        when(client.getLastName()).thenReturn("Bondar");

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);

        Review review = mock(Review.class);
        when(review.getId()).thenReturn(reviewId);
        when(review.getClient()).thenReturn(client);
        when(review.getMaster()).thenReturn(master);
        when(review.getRating()).thenReturn((short) 4);
        when(review.getComment()).thenReturn("Good");
        when(review.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        Page<UUID> idPage = new PageImpl<>(List.of(reviewId), pageable, 1);
        when(reviewRepository.findIdsByMasterIdOrderByCreatedAtDesc(masterId, PageRequest.of(0, 20)))
                .thenReturn(idPage);
        when(reviewRepository.findByIdsWithGraph(List.of(reviewId)))
                .thenReturn(List.of(review));

        // Act — two calls with same masterId + page args
        Page<ReviewResponse> first  = reviewService.getReviewsForMaster(masterId, pageable);
        Page<ReviewResponse> second = reviewService.getReviewsForMaster(masterId, pageable);

        // Assert — repository accessed only once; second result is from cache
        verify(reviewRepository, times(1)).findIdsByMasterIdOrderByCreatedAtDesc(masterId, PageRequest.of(0, 20));
        assertThat(second.getContent()).hasSize(1);
        assertThat(second.getContent().get(0).id()).isEqualTo(first.getContent().get(0).id());
    }

    private Review buildReview(UUID id, User client, Master master, short rating, String comment) {
        Review r = mock(Review.class);
        when(r.getId()).thenReturn(id);
        when(r.getClient()).thenReturn(client);
        when(r.getMaster()).thenReturn(master);
        when(r.getRating()).thenReturn(rating);
        when(r.getComment()).thenReturn(comment);
        when(r.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).toInstant());
        return r;
    }
}
