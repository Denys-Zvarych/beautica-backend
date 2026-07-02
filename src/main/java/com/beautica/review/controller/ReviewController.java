package com.beautica.review.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.MyReviewResponse;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewResponse;
import com.beautica.review.dto.SalonReviewSort;
import com.beautica.review.dto.SalonReviewSummaryResponse;
import com.beautica.review.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PreAuthorize("hasRole('CLIENT')")
    @PostMapping("/reviews")
    public ResponseEntity<ApiResponse<ReviewResponse>> createReview(
            @Valid @RequestBody CreateReviewRequest request,
            Authentication auth) {
        ReviewResponse response = reviewService.createReview(AuthenticationUtils.userId(auth), request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @GetMapping("/masters/{masterId}/reviews")
    public ApiResponse<PageResponse<ReviewResponse>> getReviewsByMaster(
            @PathVariable UUID masterId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ReviewResponse> page = reviewService.getReviewsForMaster(masterId, pageable);
        return ApiResponse.ok(PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        ));
    }

    // Declared before /reviews/{reviewId}; Spring's PathPattern matching also favours the literal
    // "me" segment over the {reviewId} variable, so the routes are unambiguous in either order.
    // clientUserId comes only from the authenticated principal — never a query/path parameter.
    @PreAuthorize("hasRole('CLIENT')")
    @GetMapping("/reviews/me")
    public ApiResponse<PageResponse<MyReviewResponse>> getMyReviews(
            Authentication auth,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(reviewService.getMyReviews(AuthenticationUtils.userId(auth), pageable));
    }

    @GetMapping("/reviews/{reviewId}")
    public ApiResponse<ReviewResponse> getReview(@PathVariable UUID reviewId) {
        return ApiResponse.ok(reviewService.getReview(reviewId));
    }

    /**
     * Rating summary for a salon's public profile.
     *
     * <p><strong>Public endpoint — no authentication required.</strong> Matches the
     * existing {@link #getReviewsByMaster} no-auth pattern: unauthenticated clients browse
     * a salon's rating before deciding whether to book.
     */
    @GetMapping("/salons/{salonId}/reviews/summary")
    public ApiResponse<SalonReviewSummaryResponse> getSalonReviewSummary(@PathVariable UUID salonId) {
        return ApiResponse.ok(reviewService.getSalonReviewSummary(salonId));
    }

    /**
     * Sortable, paginated review list for a salon's public profile.
     *
     * <p><strong>Public endpoint — no authentication required.</strong> Same no-auth
     * rationale as {@link #getSalonReviewSummary}. {@code sort} defaults to {@code NEWEST}
     * when omitted.
     */
    @GetMapping("/salons/{salonId}/reviews")
    public ApiResponse<PageResponse<SalonReviewResponse>> getSalonReviews(
            @PathVariable UUID salonId,
            @RequestParam(required = false, defaultValue = "NEWEST") SalonReviewSort sort,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<SalonReviewResponse> page = reviewService.getSalonReviews(salonId, sort, pageable);
        return ApiResponse.ok(PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        ));
    }
}
