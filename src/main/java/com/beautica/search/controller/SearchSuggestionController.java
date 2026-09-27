package com.beautica.search.controller;

import com.beautica.search.dto.SearchSuggestionRequest;
import com.beautica.search.dto.SearchSuggestionResponse;
import com.beautica.search.service.SearchSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Autocomplete suggestions behind the client «Пошук» box (Phase 331) —
 * {@code GET /api/v1/search/suggestions?q=&amp;limit=[&amp;location.cityId=][&amp;location.districtId=]}.
 *
 * <p>Returns ranked CATEGORY and SERVICE (admin-curated service-type) suggestions available at
 * the caller's place, so a tap never opens an empty {@code /search/masters}/{@code /search/salons}
 * results page for the same place (D3's round-trip guarantee). No place chosen → the national
 * list. See {@link SearchSuggestionService} for the fold/match/rank/cap logic and
 * {@code AuthRateLimitFilter}'s {@code SEARCH_SUGGESTIONS_PATH} branch for the per-IP throttle.
 *
 * <p><b>{@code permitAll()}</b> — already covered by {@code SecurityConfig}'s
 * {@code GET /api/v1/search/**} rule (no change needed there). The response carries catalogue
 * data only (labels, slugs, keys); no master/salon ids, no counts, no user data (§I).
 *
 * <p><b>{@code q} is never logged.</b> Unlike {@code SettlementSearchController}, which logs
 * nothing at all, this endpoint does not even emit a DEBUG line with the term — only its length,
 * whether a place was set, and the result count would ever be safe to log, and today nothing logs
 * even that.
 *
 * <p><b>Validation</b> mirrors {@code SearchController} / {@code SettlementSearchController}:
 * class-level {@link Validated} is what makes {@code @NotBlank}/{@code @Size}/{@code @Pattern}/
 * {@code @Min}/{@code @Max} on an {@code @ModelAttribute}-bound record fire at all. Violations
 * surface as 400 via {@code GlobalExceptionHandler} (blank/oversized/control-char {@code q},
 * out-of-range {@code limit}, malformed {@code location.cityId}/{@code location.districtId} —
 * the reused {@link com.beautica.search.dto.LocationFilter} gives an identical UUID-shape 400 to
 * {@code /search/masters}, value never echoed).
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Validated
public class SearchSuggestionController {

    /** {@code limit} default when the caller omits it. */
    private static final int DEFAULT_LIMIT = 8;

    private final SearchSuggestionService searchSuggestionService;

    @Operation(
            summary = "Autocomplete suggestions for the search box",
            description = "Ranked CATEGORY and SERVICE suggestions available at the caller's place "
                    + "(or the national list with no place chosen). Never 404s; no match or nothing "
                    + "available returns an empty list.")
    @GetMapping("/suggestions")
    public com.beautica.common.ApiResponse<List<SearchSuggestionResponse>> suggest(
            @Valid @ModelAttribute SearchSuggestionRequest request
    ) {
        int limit = request.limit() != null ? request.limit() : DEFAULT_LIMIT;
        List<SearchSuggestionResponse> suggestions =
                searchSuggestionService.suggest(request.q(), limit, request.location());
        return com.beautica.common.ApiResponse.ok(suggestions);
    }
}
