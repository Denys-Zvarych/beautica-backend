package com.beautica.service.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.service.dto.ApprovedCategoryResponse;
import com.beautica.service.dto.CategoryRequestResponse;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.service.CategoryRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Self-service category request endpoints (JSON).
 *
 * <ul>
 *   <li>{@code POST /requests} — authenticated, providers only (NOT clients). Role
 *       gate is controller-level; submitting also passes through the per-IP
 *       category-request rate limiter (5/hr) in {@code AuthRateLimitFilter}.</li>
 *   <li>{@code GET /approved} — authenticated; feeds the mobile picker.</li>
 * </ul>
 *
 * <p>The token-bearing review/approve/reject pages live in
 * {@link CategoryRequestReviewController} (permitAll, token is the auth).
 */
@RestController
@RequestMapping("/api/v1/service-categories")
@RequiredArgsConstructor
public class CategoryRequestController {

    private final CategoryRequestService categoryRequestService;

    @PostMapping("/requests")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER')")
    public ResponseEntity<ApiResponse<CategoryRequestResponse>> submitRequest(
            @Valid @RequestBody CreateCategoryRequestRequest request,
            Authentication authentication) {
        UUID requesterId = AuthenticationUtils.userId(authentication);
        CategoryRequestResponse created = categoryRequestService.submitRequest(request, requesterId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping("/approved")
    public ResponseEntity<ApiResponse<List<ApprovedCategoryResponse>>> listApproved() {
        List<ApprovedCategoryResponse> categories = categoryRequestService.listApproved();
        return ResponseEntity.ok(ApiResponse.ok(categories));
    }
}
