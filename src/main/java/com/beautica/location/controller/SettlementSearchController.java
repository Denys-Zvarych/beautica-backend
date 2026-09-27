package com.beautica.location.controller;

import com.beautica.common.ApiResponse;
import com.beautica.location.dto.SettlementSearchResponse;
import com.beautica.location.service.SettlementSearchService;
import com.beautica.search.service.NormalizedSearchQuery;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The public settlement autocomplete — {@code GET /api/v1/settlements?query=...} (Phase 326).
 *
 * <p>30 000 settlements cannot be a dropdown, so mobile Phase 346 replaces the «Область» +
 * «Місто» cascade with a single «Населений пункт» field. This is the endpoint behind that field.
 *
 * <p><b>Separate controller from {@link LocationController}, deliberately.</b> That class serves
 * the cascade this endpoint retires; it is not extended here and its three routes are untouched.
 * Keeping them apart means the cascade can be deleted in one piece when mobile 346 has shipped.
 *
 * <p><b>{@code permitAll()}</b> (phase-326 D7) — the field is reached during registration, before
 * a token exists. It returns a public government reference list and no user data: no owner UUIDs,
 * no provider counts, no PII (§I). Unlike the cascade's three GETs, this one IS rate-limited per
 * IP — see {@code AuthRateLimitFilter}'s {@code SETTLEMENT_SEARCH_PATH} branch, and
 * {@code SecurityConfig}, where the cascade's own "not throttled" note ends with "revisit only if
 * a dynamic/parameterised locality query is added". This is that query: the response depends on
 * caller input, so the cached-and-therefore-free argument that exempts the cascade does not carry
 * over.
 *
 * <p><b>Validation.</b> {@link Validated} on the class is what makes the constraints on the
 * request parameter execute at all — without it Spring binds the value and ignores every
 * annotation. Violations surface as 400 through {@code GlobalExceptionHandler}'s
 * {@code ConstraintViolationException} branch. The character filter mirrors the one on
 * {@code MasterSearchRequest#q} verbatim, for the same reason: since the {@code < > " '} ban was
 * correctly dropped for Ukrainian apostrophe names, it is the ONLY character filter on the term,
 * so it bans the Unicode control/format/separator categories rather than ASCII {@code \p{Cntrl}}
 * alone.
 *
 * <p>HTTP concerns only — the three-state routing (blank -> major list, too short -> empty, else
 * -> ranked search) lives in {@link SettlementSearchService}. The one thing decided here is the
 * user-facing hint attached to the too-short response, which is presentation.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
public class SettlementSearchController {

    /**
     * Hint returned with the empty below-minimum response.
     *
     * <p>Duplicates {@code SearchController}'s constant of the same wording on purpose: both
     * enforce {@link NormalizedSearchQuery#MIN_QUERY_LENGTH} and both must say so in Ukrainian,
     * where the numeral inflects and the string therefore cannot be interpolated from the
     * constant. Changing {@code MIN_QUERY_LENGTH} means changing BOTH messages.
     */
    private static final String QUERY_TOO_SHORT_MESSAGE = "Введіть щонайменше 3 символи";

    /**
     * Upper bound on the {@code query} parameter, in characters.
     *
     * <p>Named rather than inlined because the {@code message} beside it must restate the number
     * (a Bean Validation message cannot interpolate a constant), so the two are one edit.
     */
    static final int MAX_QUERY_LENGTH = 50;

    private final SettlementSearchService settlementSearchService;

    /**
     * Ranked settlement matches for one autocomplete keystroke.
     *
     * <p>Three outcomes, all HTTP 200 (see {@link SettlementSearchService} for why below-minimum
     * is not a 400):
     * <ul>
     *   <li>{@code query} absent or blank -> the ~50 {@code is_major} settlements.</li>
     *   <li>{@code query} shorter than {@link NormalizedSearchQuery#MIN_QUERY_LENGTH} -> an empty
     *       list plus {@link #QUERY_TOO_SHORT_MESSAGE}, with no database query issued.</li>
     *   <li>otherwise -> at most 20 matches, prefix-first, major-city-first, then by trigram
     *       similarity.</li>
     * </ul>
     *
     * @param query the caller's search term; optional
     * @return the settlement rows, each carrying its {@code settlementId} and its disambiguating
     *         oblast label
     */
    @GetMapping("/settlements")
    public ApiResponse<List<SettlementSearchResponse>> searchSettlements(
            // Defence-in-depth only: the term is bound as a JDBC parameter and LIKE-escaped by
            // the service, never interpolated into SQL, never logged, reflected or persisted.
            // Deliberately NOT a bare \p{C}, which also matches Cn (unassigned) and would 400
            // every code point Unicode has not allocated yet.
            // MAX_QUERY_LENGTH is a COST bound, not a correctness one: @Size counts characters
            // and the scan pays for bytes. 100 x U+2022 is 300 bytes, and the residual cost of a
            // term the trigram guard cannot reject scales with byte-length x rows (3-char
            // punctuation 26 ms, 100-char punctuation 119 ms — same rows, 4.5x the work). No
            // Ukrainian settlement name comes close to 50 characters; the longest in the 25 698-row
            // table is well under it, so halving the ceiling costs no legitimate caller anything
            // and halves the worst case that survives every other control.
            @RequestParam(name = "query", required = false)
            @Size(max = MAX_QUERY_LENGTH, message = "query must be at most 50 characters")
            @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]*$",
                     message = "query must not contain control characters")
            String query
    ) {
        List<SettlementSearchResponse> results = settlementSearchService.search(query);
        return SettlementSearchService.isNotIndexServable(query)
                ? ApiResponse.ok(results, QUERY_TOO_SHORT_MESSAGE)
                : ApiResponse.ok(results);
    }

}
