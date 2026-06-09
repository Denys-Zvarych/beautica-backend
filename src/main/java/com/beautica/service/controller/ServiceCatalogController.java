package com.beautica.service.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.service.dto.CatalogCategoryResponse;
import com.beautica.service.dto.PlatformServiceTypeResponse;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.service.ServiceCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ServiceCatalogController {

    private final ServiceCatalogService serviceCatalogService;

    @GetMapping("/service-categories")
    public ApiResponse<List<CatalogCategoryResponse>> getCategories() {
        return ApiResponse.ok(serviceCatalogService.getCategories());
    }

    /**
     * Legacy System-A catalog search over the orphan {@code service_types} table,
     * filtered by {@code categoryId} and/or trigram {@code q}. Retained as a
     * callable internal endpoint but <strong>excluded from the published OpenAPI
     * spec</strong> via {@code @Operation(hidden = true)}.
     *
     * <p>Without hiding it, SpringDoc collapses this operation and
     * {@link #getServiceTypesByPlatformCategory(String)} — both mapped to
     * {@code GET /api/v1/service-types} — into a single ambiguous {@code oneOf}
     * 200 response. Because {@link ServiceTypeResponse} and
     * {@link PlatformServiceTypeResponse} both deserialize the real
     * {@code {id,slug,nameUk,categoryName}} payload, the generated mobile client
     * throws {@code UnsupportedError("more than one match found")} and the
     * service-types list renders empty. Hiding the legacy operation leaves a
     * single, unambiguous 200 schema ({@code ApiResponse<List<PlatformServiceTypeResponse>>})
     * for the live {@code ?categoryName=} lookup.
     */
    @Operation(hidden = true)
    @GetMapping("/service-types")
    public ApiResponse<List<ServiceTypeResponse>> getServiceTypes(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false)
            @Size(min = 3, max = 100, message = "Search query must be 3–100 characters")
            @Pattern(regexp = "^[^\\p{Cntrl}<>\"']+$",
                     message = "Search query must not contain control characters or HTML special characters")
            String q) {
        return ApiResponse.ok(serviceCatalogService.searchServiceTypes(categoryId, q));
    }

    /**
     * Returns active service types for a platform category, keyed by the category's
     * canonical name slug (e.g. {@code EYELASH}, {@code HAIR}).
     *
     * <p>The {@code params = "categoryName"} constraint on {@code @GetMapping} routes
     * only requests that carry the {@code categoryName} query parameter here.
     * Requests with {@code categoryId} or {@code q} continue to the existing handler.
     *
     * <p>Unknown slug → 200 with empty list. Present-but-blank slug → 400 (Bean
     * Validation {@code @NotBlank} fires before the service is called).
     *
     * <p>This endpoint is {@code permitAll()} — the path is already open in
     * {@code SecurityConfig}; no role annotation is needed.
     */
    @GetMapping(value = "/service-types", params = "categoryName")
    public ApiResponse<List<PlatformServiceTypeResponse>> getServiceTypesByPlatformCategory(
            @RequestParam
            @Parameter(required = true,
                    description = "Canonical platform-category name slug (e.g. EYELASH, HAIR). Required.")
            @NotBlank(message = "categoryName must not be blank")
            @Size(max = 100, message = "categoryName must be at most 100 characters")
            @Pattern(regexp = "^[^\\p{Cntrl}<>\"']+$",
                     message = "categoryName must not contain control characters or HTML special characters")
            String categoryName) {
        return ApiResponse.ok(serviceCatalogService.findServiceTypesByPlatformCategory(categoryName));
    }

    @PostMapping("/service-types/suggest")
    @PreAuthorize("hasAnyRole('SALON_OWNER','INDEPENDENT_MASTER','SALON_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> suggestServiceType(
            @Valid @RequestBody SuggestServiceTypeRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        serviceCatalogService.suggestServiceType(request, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(null, "Suggestion submitted"));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID userId) {
            return userId;
        }
        throw new ForbiddenException("Not authenticated");
    }
}
