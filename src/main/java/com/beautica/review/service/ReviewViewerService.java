package com.beautica.review.service;

import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewResponse;
import com.beautica.review.dto.SalonReviewSort;
import com.beautica.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Phase 395 — the public review reads with the per-viewer {@code authoredByViewer} flag stamped on, so the app can
 * hide «Поскаржитися» on the caller's own reviews.
 *
 * <p><b>Why a separate bean.</b> The underlying {@link ReviewService} reads are {@code @Cacheable}
 * ({@code reviews-by-master}, {@code reviews-by-salon}, {@code review-detail}) and shared by every viewer, so their
 * values stay viewer-neutral ({@code authoredByViewer = false}). This facade calls them THROUGH the Spring proxy
 * (self-invocation would bypass the cache) and then stamps the flag on copies:
 * <ul>
 *   <li>anonymous caller ({@code viewerId == null}) → no query at all, the cached value is returned as-is;</li>
 *   <li>authenticated caller → ONE primary-key-bounded query ({@link ReviewRepository#findIdsByClientIdAndIdIn})
 *       over the ids of the page (≤ {@code max-page-size}); never cached, so one viewer's flag never leaks to
 *       another.</li>
 * </ul>
 * Deliberately not {@code @Transactional}: a cache hit for an anonymous caller must not open a transaction or
 * borrow a connection; the one repository call runs in Spring Data's own read-only transaction.
 */
@Service
@RequiredArgsConstructor
public class ReviewViewerService {

    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;

    public Page<ReviewResponse> getReviewsForMaster(
            UUID masterId, SalonReviewSort sort, Pageable pageable,
            @Nullable UUID viewerId) {
        Page<ReviewResponse> page = reviewService.getReviewsForMaster(masterId, sort, pageable);
        Set<UUID> authored = authoredIds(viewerId, page.getContent(), ReviewResponse::id);
        return authored.isEmpty() ? page : page.map(r -> r.withAuthoredByViewer(authored.contains(r.id())));
    }

    public Page<SalonReviewResponse> getSalonReviews(
            UUID salonId, SalonReviewSort sort, Pageable pageable,
            @Nullable UUID viewerId) {
        Page<SalonReviewResponse> page = reviewService.getSalonReviews(salonId, sort, pageable);
        Set<UUID> authored = authoredIds(viewerId, page.getContent(), SalonReviewResponse::id);
        return authored.isEmpty() ? page : page.map(r -> r.withAuthoredByViewer(authored.contains(r.id())));
    }

    public ReviewResponse getReview(UUID reviewId, @Nullable UUID viewerId) {
        ReviewResponse review = reviewService.getReview(reviewId);
        return review.withAuthoredByViewer(!authoredIds(viewerId, List.of(review), ReviewResponse::id).isEmpty());
    }

    private <T> Set<UUID> authoredIds(@Nullable UUID viewerId, List<T> rows, Function<T, UUID> idOf) {
        if (viewerId == null || rows.isEmpty()) {
            return Set.of();
        }
        return reviewRepository.findIdsByClientIdAndIdIn(viewerId, rows.stream().map(idOf).toList());
    }
}
