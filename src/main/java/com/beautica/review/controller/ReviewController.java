package com.beautica.review.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.review.dto.CreateAppointmentReviewRequest;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.MasterReviewSummaryResponse;
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

    /**
     * Creates the ONE review a client may leave for an eligible multi-service visit (BE-6) —
     * {@code COMPLETED}, or still {@code CONFIRMED} with an already-elapsed end (see {@code
     * BookingClosureRule#isReviewEligible}).
     *
     * <p>Kept in the review feature (not {@code AppointmentController}) so all review-creation logic
     * lives in one package — the visit-scoped path {@code /appointments/{appointmentId}/review} is
     * mapped here under this controller's {@code /api/v1} base and is unambiguous against
     * {@code AppointmentController}'s routes (different {@code /review} suffix). Role gate mirrors the
     * legacy {@code POST /reviews}: {@code hasRole('CLIENT')} plus the ownership/status/duplicate
     * checks in the service. Same 201 + {@link ApiResponse} envelope as the single-booking path.
     */
    @PreAuthorize("hasRole('CLIENT')")
    @PostMapping("/appointments/{appointmentId}/review")
    public ResponseEntity<ApiResponse<ReviewResponse>> createAppointmentReview(
            @PathVariable UUID appointmentId,
            @Valid @RequestBody CreateAppointmentReviewRequest request,
            Authentication auth) {
        ReviewResponse response = reviewService.createAppointmentReview(
                AuthenticationUtils.userId(auth), appointmentId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    /**
     * Sortable, paginated received-reviews list for a master's public profile.
     *
     * <p><strong>Public endpoint — no authentication required.</strong> {@code sort} defaults
     * to {@code NEWEST} when omitted (Phase 8.11), preserving the pre-8.11 newest-first
     * behaviour. Reuses the salon {@link SalonReviewSort} enum verbatim — the four wire values
     * are identical, so mobile maps one enum for both endpoints.
     */
    @GetMapping("/masters/{masterId}/reviews")
    public ApiResponse<PageResponse<ReviewResponse>> getReviewsByMaster(
            @PathVariable UUID masterId,
            @RequestParam(required = false, defaultValue = "NEWEST") SalonReviewSort sort,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ReviewResponse> page = reviewService.getReviewsForMaster(masterId, sort, pageable);
        return ApiResponse.ok(PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        ));
    }

    /**
     * Rating summary for a master's public profile (Phase 8.10).
     *
     * <p><strong>Public endpoint — no authentication required.</strong> Mirrors the salon
     * {@link #getSalonReviewSummary} pattern: aggregate-only (avg + count + zero-filled 5→1
     * distribution), no PII, so the same no-auth access rules apply.
     */
    @GetMapping("/masters/{masterId}/reviews/summary")
    public ApiResponse<MasterReviewSummaryResponse> getMasterReviewSummary(@PathVariable UUID masterId) {
        return ApiResponse.ok(reviewService.getMasterReviewSummary(masterId));
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
