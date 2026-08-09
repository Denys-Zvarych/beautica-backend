package com.beautica.search.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchRequest;
import com.beautica.search.dto.SalonSearchResult;
import com.beautica.search.dto.SearchResultWindow;
import com.beautica.search.service.NormalizedSearchQuery;
import com.beautica.search.service.SearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
 *
 * <p><b>Free-text {@code q} contract (both endpoints):</b></p>
 * <ul>
 *   <li>absent / blank → no text filter; the location-scoped result set;</li>
 *   <li>supplied but no token reaches 3 characters → <b>HTTP 200</b> with an
 *       empty page ({@code totalElements: 0}) and
 *       {@code message: "Введіть щонайменше 3 символи"} in the envelope. Never a
 *       400, and never the unfiltered set;</li>
 *   <li>otherwise → whitespace-tokenised (max 4 tokens), every token must match
 *       one of the searchable columns, curly apostrophes folded onto U+0027.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Validated
public class SearchController {

    /**
     * Helper text returned (HTTP 200, {@code data} = an empty page) when the
     * caller typed something but no token reaches
     * {@link NormalizedSearchQuery#MIN_QUERY_LENGTH}.
     *
     * <p><b>Wire contract — the mobile client renders this verbatim, so the
     * string is frozen.</b> It is a literal, not a template: Ukrainian numerals
     * inflect the noun ("3 символи" vs "5 символів"), so interpolating
     * {@code MIN_QUERY_LENGTH} would silently produce broken grammar the day the
     * floor changes. Changing the floor means changing this string too.</p>
     *
     * <p>Deliberately NOT a 400: a 1–2 character query is an ordinary keystroke
     * in an incremental search box, not malformed input. The alternative the
     * platform used to ship — silently dropping the filter and returning the
     * whole location-scoped set — is the actual defect this replaces.</p>
     */
    private static final String QUERY_TOO_SHORT_MESSAGE = "Введіть щонайменше 3 символи";

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
        Pageable pageable = resolvePageable(request.page(), request.size());
        if (isQueryTooShort(request.q())) {
            return tooShortQueryResponse(pageable.getPageNumber(), pageable.getPageSize());
        }
        Page<MasterSearchResult> result = searchService.searchMasters(request, pageable);
        // AUTH-GATE street address per-request, AFTER the cache read (the cached
        // value always holds the full object). Anonymous callers get street /
        // buildingNo nulled out — privacy for masters' home addresses (§I).
        List<MasterSearchResult> content = isAuthenticated()
                ? result.getContent()
                : result.getContent().stream().map(MasterSearchResult::withoutStreetAddress).toList();
        return ApiResponse.ok(PageResponse.of(
                content,
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
        Pageable pageable = resolvePageable(request.page(), request.size());
        if (isQueryTooShort(request.q())) {
            return tooShortQueryResponse(pageable.getPageNumber(), pageable.getPageSize());
        }
        Page<SalonSearchResult> result = searchService.searchSalons(request, pageable);
        // AUTH-GATE street address per-request, AFTER the cache read (see masters).
        List<SalonSearchResult> content = isAuthenticated()
                ? result.getContent()
                : result.getContent().stream().map(SalonSearchResult::withoutStreetAddress).toList();
        return ApiResponse.ok(PageResponse.of(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        ));
    }

    /**
     * True iff the caller typed a free-text query that is not trigram-servable
     * (no token reaches {@link NormalizedSearchQuery#MIN_QUERY_LENGTH}).
     *
     * <p>The rule itself lives in {@link NormalizedSearchQuery} and is enforced
     * again inside {@link SearchService} (which returns the same empty page for
     * a non-HTTP caller). This call is the HTTP-layer affordance only: it lets
     * the endpoint attach the user-facing {@code message} and skip a search
     * whose answer is already known — no business rule is duplicated here.</p>
     */
    private static boolean isQueryTooShort(String q) {
        return NormalizedSearchQuery.of(q).belowMinimumLength();
    }

    /**
     * The short-query response: HTTP 200, an empty page in the standard
     * {@code PageResponse} shape, and {@link #QUERY_TOO_SHORT_MESSAGE} in the
     * envelope's {@code message} field. Page number / size echo the request so
     * the client's paging state stays consistent.
     */
    /**
     * Resolves the caller's {@code page}/{@code size} into a {@link Pageable}, applying
     * the server defaults and a <b>defence-in-depth</b> clamp.
     *
     * <p>The authoritative rejection of an over-window request is
     * {@code @AssertTrue isWithinResultWindow()} on the request record, which returns a
     * 400 rather than a clamped 200 — see {@link SearchResultWindow} for why a silent
     * clamp is the wrong contract. This clamp exists only for the case that made the
     * previous {@code Math.min} calls worth keeping: {@code @Validated} being removed
     * from this class, which silently disables every constraint on the bound record. It
     * is unreachable while validation is wired, and it is derived from the same
     * constants so the two cannot disagree about where the window ends.</p>
     */
    private static Pageable resolvePageable(Integer requestedPage, Integer requestedSize) {
        int pageSize = Math.min(
                requestedSize != null ? Math.max(requestedSize, 1) : SearchResultWindow.DEFAULT_PAGE_SIZE,
                SearchResultWindow.MAX_PAGE_SIZE);
        int lastPageInWindow = Math.max(SearchResultWindow.MAX_RESULT_WINDOW / pageSize - 1, 0);
        int maxPageIndex = Math.min(SearchResultWindow.MAX_PAGE_INDEX, lastPageInWindow);
        int pageNum = Math.min(requestedPage != null ? Math.max(requestedPage, 0) : 0, maxPageIndex);
        return PageRequest.of(pageNum, pageSize);
    }

    private static <T> ApiResponse<PageResponse<T>> tooShortQueryResponse(int pageNum, int pageSize) {
        return ApiResponse.ok(
                PageResponse.of(List.of(), pageNum, pageSize, 0L, 0),
                QUERY_TOO_SHORT_MESSAGE);
    }

    /**
     * True iff the current request carries an authenticated principal.
     *
     * <p>These endpoints are {@code permitAll}, so anonymous requests still
     * reach here but carry an {@link AnonymousAuthenticationToken} (or a null
     * authentication) in the {@code SecurityContext}. Street-level address
     * fields are returned only when this is {@code true}; for everyone else they
     * are nulled out post-cache. The cache itself is auth-agnostic (it always
     * stores the full object), so this per-request gate cannot leak addresses
     * across the anon/authenticated boundary.</p>
     */
    private static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }
}
