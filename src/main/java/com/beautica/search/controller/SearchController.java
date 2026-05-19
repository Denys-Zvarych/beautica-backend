package com.beautica.search.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchRequest;
import com.beautica.search.dto.SalonSearchResult;
import com.beautica.search.service.SearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public discovery endpoints for masters and salons.
 *
 * <p>Both endpoints are intentionally unauthenticated — discovery is open to
 * the marketplace. {@code SecurityConfig} permits {@code GET
 * /api/v1/search/**} to anonymous callers; do not reintroduce
 * {@code @PreAuthorize} on these methods.</p>
 *
 * <p><b>Why {@link Validated} on the class:</b> the request DTOs are bound via
 * {@link ModelAttribute}, not {@code @RequestBody}. Bean Validation on
 * {@code @ModelAttribute @Valid}-bound records only fires when the controller
 * itself is registered for method validation through class-level
 * {@link Validated}. Without this, every {@code @Min}, {@code @Size},
 * {@code @Pattern}, {@code @DecimalMin}, etc. on
 * {@link MasterSearchRequest} and {@link SalonSearchRequest} becomes dead
 * code — a regression already documented in {@code docs/backlog.md} for
 * Phase 6.2.</p>
 *
 * <p><b>Public response shape:</b> the result DTOs deliberately omit
 * salon/owner UUIDs from masters and any internal flags. Each result record
 * already filters its public surface; do not add owner-identifying fields to
 * the response without re-auditing the public-PII policy in
 * {@code ARCHITECTURE-backend.md} §0.7. The Phase 10.5 locality labels are
 * resolved {@code name_uk} strings only — the internal city/district UUIDs
 * are NOT exposed (§I).</p>
 *
 * <p><b>BREAKING CHANGE (Phase 10.5, pre-launch):</b> the free-text
 * {@code ?city=} / {@code ?region=} query params on <em>both</em> endpoints
 * are <b>removed</b> and replaced by the structured, FK-based location filter
 * {@code ?location.cityId=&lt;uuid&gt;} (+ optional
 * {@code &location.districtId=&lt;uuid&gt;}). The old exact string-equality
 * filter was a real bug ("Київ" ≠ "Киев" ≠ "kyiv"); this is a clean
 * replacement, not a migration — there are no real clients yet. Result DTOs
 * replace the free-text {@code city}/{@code region} fields with resolved
 * {@code cityLabel}/{@code districtLabel}. Mobile-client adoption of the new
 * contract is a separate later follow-up. See
 * {@code docs/backend-phases/phase-10.5-search-rework.md} § "OpenAPI / change
 * notes".</p>
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Validated
public class SearchController {

    private final SearchService searchService;

    /**
     * Search active masters by optional FK location filter
     * ({@code location.cityId} / {@code location.districtId},
     * district-primary), service category, minimum rating, and effective price
     * range. Sorted by rating descending. {@code SALON_ADMIN} accounts are
     * never returned; an employed {@code SALON_MASTER}'s locality resolves
     * through its salon at query time.
     *
     * <p>Validation on the bound record is enforced by the class-level
     * {@link Validated} annotation; constraint violations surface as a 400
     * via {@code GlobalExceptionHandler}'s
     * {@code ConstraintViolationException} /
     * {@code MethodArgumentNotValidException} branches.</p>
     */
    @GetMapping("/masters")
    public ApiResponse<PageResponse<MasterSearchResult>> searchMasters(
            @Valid @ModelAttribute MasterSearchRequest request
    ) {
        int pageNum = request.page() != null ? request.page() : 0;
        int pageSize = request.size() != null ? request.size() : 20;
        Pageable pageable = PageRequest.of(pageNum, pageSize);
        Page<MasterSearchResult> result = searchService.searchMasters(request, pageable);
        return ApiResponse.ok(PageResponse.of(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        ));
    }

    /**
     * Search active salons by the optional FK location filter
     * ({@code location.cityId} / {@code location.districtId},
     * district-primary).
     *
     * <p>Mirrors the master endpoint's wiring — bound DTO, class-level
     * {@code @Validated} drives the constraints, identical {@code PageResponse}
     * envelope.</p>
     */
    @GetMapping("/salons")
    public ApiResponse<PageResponse<SalonSearchResult>> searchSalons(
            @Valid @ModelAttribute SalonSearchRequest request
    ) {
        int pageNum = request.page() != null ? request.page() : 0;
        int pageSize = request.size() != null ? request.size() : 20;
        Pageable pageable = PageRequest.of(pageNum, pageSize);
        Page<SalonSearchResult> result = searchService.searchSalons(request, pageable);
        return ApiResponse.ok(PageResponse.of(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        ));
    }
}
