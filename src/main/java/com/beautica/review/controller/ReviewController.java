package com.beautica.review.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
        ReviewResponse response = reviewService.createReview(principalId(auth), request);
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

    @GetMapping("/reviews/{reviewId}")
    public ApiResponse<ReviewResponse> getReview(@PathVariable UUID reviewId) {
        return ApiResponse.ok(reviewService.getReview(reviewId));
    }

    private UUID principalId(Authentication auth) {
        if (auth instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID id) {
            return id;
        }
        throw new ForbiddenException("Access denied");
    }
}
