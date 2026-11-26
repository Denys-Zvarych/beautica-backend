package com.beautica.service.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.service.dto.ApprovedCategoryResponse;
import com.beautica.service.dto.CategoryRequestResponse;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.service.CategoryRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Self-service category request endpoints (JSON).
 *
 * <ul>
 *   <li>{@code POST /requests} — authenticated, providers only (NOT clients). Role
 *       gate is controller-level; submitting also passes through the per-IP
 *       category-request rate limiter (5/hr) in {@code AuthRateLimitFilter}.</li>
 *   <li>{@code GET /approved} — public ({@code permitAll}, W050); feeds the mobile picker and the website.</li>
 * </ul>
 *
 * <p>The former emailed review/approve/reject links answer 410 in
 * {@link RetiredCatalogRequestLinksController} (B545); requests are decided in the catalog portal.
 */
@RestController
@RequestMapping("/api/v1/service-categories")
@RequiredArgsConstructor
public class CategoryRequestController {

    /**
     * B539 audit — {@code public, max-age=0, s-maxage=300}, the {@code PublicBookingController} pattern: a browser
     * always revalidates, a shared cache / CDN may serve the list for 5 min (the server-side
     * {@code approved-categories} cache already bounds freshness). The body is the same for every caller (no per-user
     * field), so an authenticated response may be shared too. Set through {@code ResponseEntity}, so Spring
     * Security's default {@code no-cache} header writer stays off for this 200.
     */
    static final CacheControl APPROVED_CACHE_CONTROL =
            CacheControl.maxAge(Duration.ZERO).sMaxAge(Duration.ofMinutes(5)).cachePublic();

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
        return ResponseEntity.ok().cacheControl(APPROVED_CACHE_CONTROL).body(ApiResponse.ok(categories));
    }
}
