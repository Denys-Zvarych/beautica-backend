package com.beautica.search.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Inbound request DTO for {@code GET /api/v1/search/suggestions} (Phase 331).
 *
 * <p>Bound via {@code @ModelAttribute}; the controller class carries class-level
 * {@code @Validated} so these constraints actually fire (see {@code SearchController}'s Javadoc
 * for why — without it every annotation here is dead code on an {@code @ModelAttribute}-bound
 * record).
 *
 * <p>{@code q}'s {@code @Pattern} mirrors {@code MasterSearchRequest#q} /
 * {@code SettlementSearchController#searchSettlements} verbatim: the ONLY character filter is the
 * Unicode Cc/Cf/Zl/Zp control-character ban, never the ASCII-only {@code \p{Cntrl}} (which misses
 * NEL, the C1 block, ZWSP, U+2028/U+2029 and RTL overrides), and never a {@code < > " '} ban
 * (Ukrainian apostrophe names). {@code q} is bound as a JDBC-free in-memory fold/match only — it
 * never reaches SQL — but the same defence-in-depth applies: never logged, reflected, or echoed
 * back (see {@code SearchSuggestionController}'s Javadoc).
 *
 * @param q        the caller's partial term; 1..50 characters after the caller supplies something
 *                 non-blank ({@code @NotBlank} — unlike {@code MasterSearchRequest#q}, which is
 *                 optional, {@code q} is the whole point of this endpoint)
 * @param limit    caller's suggestion cap; {@code null} defaults to 8 in the controller
 * @param location reused {@link LocationFilter} — identical wire shape and binding to
 *                 {@code /search/masters}, so a UUID-shape error behaves identically
 */
public record SearchSuggestionRequest(
        @NotBlank(message = "q must not be blank")
        @Size(max = 50, message = "q must be at most 50 characters")
        @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]*$",
                 message = "q must not contain control characters")
        String q,

        @Min(value = 1, message = "limit must be at least 1")
        @Max(value = 8, message = "limit must be at most 8")
        Integer limit,

        @Valid
        LocationFilter location
) {
}
