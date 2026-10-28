package com.beautica.review.service;

import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewResponse;
import com.beautica.review.dto.SalonReviewSort;
import com.beautica.review.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewViewerService — authoredByViewer stamping (phase 395)")
class ReviewViewerServiceTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID SALON_ID = UUID.randomUUID();
    private static final Pageable PAGE = PageRequest.of(0, 20);
    private static final OffsetDateTime CREATED = OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZoneOffset.UTC);

    @Mock private ReviewService reviewService;
    @Mock private ReviewRepository reviewRepository;

    private ReviewViewerService service;

    @BeforeEach
    void setUp() {
        service = new ReviewViewerService(reviewService, reviewRepository);
    }

    @Test
    @DisplayName("master list, authenticated → only the viewer's own row is flagged; one id-bounded query")
    void should_flagOnlyOwnRow_when_viewerAuthenticatedOnMasterList() {
        ReviewResponse own = review();
        ReviewResponse other = review();
        when(reviewService.getReviewsForMaster(MASTER_ID, SalonReviewSort.NEWEST, PAGE))
                .thenReturn(new PageImpl<>(List.of(own, other), PAGE, 2));
        when(reviewRepository.findIdsByClientIdAndIdIn(VIEWER_ID, List.of(own.id(), other.id())))
                .thenReturn(Set.of(own.id()));

        Page<ReviewResponse> result = service.getReviewsForMaster(MASTER_ID, SalonReviewSort.NEWEST, PAGE, VIEWER_ID);

        assertThat(result.getContent()).extracting(ReviewResponse::authoredByViewer).containsExactly(true, false);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(own.authoredByViewer()).as("the cached instance is never mutated").isFalse();
    }

    @Test
    @DisplayName("master list, anonymous → cached page returned as-is, no query")
    void should_returnCachedPageUntouched_when_viewerAnonymous() {
        Page<ReviewResponse> cached = new PageImpl<>(List.of(review()), PAGE, 1);
        when(reviewService.getReviewsForMaster(MASTER_ID, SalonReviewSort.NEWEST, PAGE)).thenReturn(cached);

        Page<ReviewResponse> result = service.getReviewsForMaster(MASTER_ID, SalonReviewSort.NEWEST, PAGE, null);

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(reviewRepository);
    }

    @Test
    @DisplayName("salon list, authenticated → own row flagged")
    void should_flagOwnRow_when_viewerAuthenticatedOnSalonList() {
        SalonReviewResponse own = salonReview();
        SalonReviewResponse other = salonReview();
        when(reviewService.getSalonReviews(SALON_ID, SalonReviewSort.NEWEST, PAGE))
                .thenReturn(new PageImpl<>(List.of(other, own), PAGE, 2));
        when(reviewRepository.findIdsByClientIdAndIdIn(VIEWER_ID, List.of(other.id(), own.id())))
                .thenReturn(Set.of(own.id()));

        Page<SalonReviewResponse> result = service.getSalonReviews(SALON_ID, SalonReviewSort.NEWEST, PAGE, VIEWER_ID);

        assertThat(result.getContent()).extracting(SalonReviewResponse::authoredByViewer)
                .containsExactly(false, true);
    }

    @Test
    @DisplayName("salon list, authenticated viewer with no own review → page returned as-is")
    void should_returnPageUntouched_when_viewerAuthoredNone() {
        Page<SalonReviewResponse> cached = new PageImpl<>(List.of(salonReview()), PAGE, 1);
        when(reviewService.getSalonReviews(SALON_ID, SalonReviewSort.NEWEST, PAGE)).thenReturn(cached);
        when(reviewRepository.findIdsByClientIdAndIdIn(VIEWER_ID, List.of(cached.getContent().get(0).id())))
                .thenReturn(Set.of());

        Page<SalonReviewResponse> result = service.getSalonReviews(SALON_ID, SalonReviewSort.NEWEST, PAGE, VIEWER_ID);

        assertThat(result).isSameAs(cached);
    }

    @Test
    @DisplayName("empty page, authenticated → no query")
    void should_notQuery_when_pageEmpty() {
        when(reviewService.getReviewsForMaster(MASTER_ID, SalonReviewSort.NEWEST, PAGE)).thenReturn(Page.empty(PAGE));

        service.getReviewsForMaster(MASTER_ID, SalonReviewSort.NEWEST, PAGE, VIEWER_ID);

        verifyNoInteractions(reviewRepository);
    }

    @Test
    @DisplayName("single review → flagged for its author, not for anyone else")
    void should_flagSingleReview_when_viewerIsAuthor() {
        ReviewResponse review = review();
        UUID stranger = UUID.randomUUID();
        when(reviewService.getReview(review.id())).thenReturn(review);
        when(reviewRepository.findIdsByClientIdAndIdIn(VIEWER_ID, List.of(review.id()))).thenReturn(Set.of(review.id()));
        when(reviewRepository.findIdsByClientIdAndIdIn(stranger, List.of(review.id()))).thenReturn(Set.of());

        boolean forAuthor = service.getReview(review.id(), VIEWER_ID).authoredByViewer();
        boolean forStranger = service.getReview(review.id(), stranger).authoredByViewer();

        assertThat(forAuthor).isTrue();
        assertThat(forStranger).isFalse();
        verify(reviewService, org.mockito.Mockito.times(2)).getReview(review.id());
    }

    private static ReviewResponse review() {
        return new ReviewResponse(UUID.randomUUID(), MASTER_ID, "Олена К.", "Манікюр", 5, "Чудово", CREATED, false);
    }

    private static SalonReviewResponse salonReview() {
        return new SalonReviewResponse(UUID.randomUUID(), MASTER_ID, "Ірина", "Т.", "Олена К.", "Манікюр", 4,
                "Добре", CREATED, false);
    }
}
