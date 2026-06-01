package com.beautica.service.controller;

import com.beautica.common.ApiResponse;
import com.beautica.service.dto.CreatePlatformCategoryRequest;
import com.beautica.service.dto.PlatformCategoryResponse;
import com.beautica.service.dto.PlatformCategoryUsageResponse;
import com.beautica.service.service.InternalCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal management endpoints for platform-managed service categories.
 *
 * <p>All paths under {@code /api/v1/internal/**} are protected by
 * {@link com.beautica.config.InternalApiKeyFilter} — callers must supply the
 * correct value in the {@code X-Internal-Key} header. No JWT is required.
 *
 * <p>These endpoints are intended for the Beautica operations team and are
 * NOT exposed through the public OpenAPI spec (springdoc.api-docs.enabled=false
 * in prod).
 */
@RestController
@RequestMapping("/api/v1/internal/service-categories")
@RequiredArgsConstructor
public class InternalCategoryController {

    private final InternalCategoryService internalCategoryService;

    /**
     * Lists all platform categories with their current usage counts from
     * {@code service_definitions.category}.
     *
     * <p>Categories with zero usages are included so the team can see the full
     * canonical list and identify unused entries.
     *
     * @return 200 with {@code ApiResponse<List<PlatformCategoryUsageResponse>>}
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<PlatformCategoryUsageResponse>>> listCategories() {
        List<PlatformCategoryUsageResponse> categories = internalCategoryService.listWithUsageCounts();
        return ResponseEntity.ok(ApiResponse.ok(categories));
    }

    /**
     * Creates a new platform category.
     *
     * <p>The name must match {@code ^[A-Z][A-Z0-9_]*$} — uppercase letters, digits,
     * and underscores, starting with an uppercase letter. This mirrors the DB
     * {@code CHECK} constraint in the V64 migration.
     *
     * @param request validated category-creation request
     * @return 201 with the created category on success;
     *         409 if a category with the same name (case-insensitive) already exists;
     *         400 if the name fails Bean Validation
     */
    @PostMapping
    public ResponseEntity<ApiResponse<PlatformCategoryResponse>> createCategory(
            @Valid @RequestBody CreatePlatformCategoryRequest request) {
        PlatformCategoryResponse created = internalCategoryService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }
}
