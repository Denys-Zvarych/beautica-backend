package com.beautica.search.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Inbound request DTO for {@code GET /api/v1/search/salons}.
 *
 * <p>Bound from query parameters via {@code @ModelAttribute} on the controller.
 * Every field is optional and may be {@code null}; validation only fires when
 * the caller actually supplies a value.</p>
 *
 * <p><b>Controller wiring:</b> the controller class binding to this record
 * MUST be annotated with {@link org.springframework.validation.annotation.Validated}
 * and the method parameter with {@code @Valid} for the constraints to fire on
 * {@code @ModelAttribute}-bound records — Bean Validation does not run on
 * plain {@code @RequestParam} method parameters by default.</p>
 *
 * <p><b>Phase 10.5 breaking change:</b> the legacy free-text {@code city} /
 * {@code region} query params are <b>removed</b> and replaced by the
 * structured {@link LocationFilter} object ({@code location.cityId} /
 * {@code location.districtId}) — an intended, documented pre-launch breaking
 * change to the search contract, mirroring {@link MasterSearchRequest}.</p>
 *
 * <p><b>Field-by-field rationale:</b></p>
 * <ul>
 *   <li>{@code location} — structured FK-based locality filter; see
 *       {@link LocationFilter}. {@code @Valid} cascades Bean Validation into
 *       the nested record. Optional: a {@code null} location means "no
 *       location filter".</li>
 *   <li>{@code category} — optional service-category filter (Phase 19.7,
 *       decision 5). When supplied it scopes the salon price-range aggregation
 *       ({@code priceMin}/{@code priceMax}) to matching active services;
 *       otherwise the range spans every active service across the salon's
 *       masters. Kept as a free {@code String} (max 100, mirroring
 *       {@code service_definitions.category VARCHAR(100)} from V6 and
 *       {@link MasterSearchRequest#category()}) rather than the
 *       {@code ServiceCategory} enum — enum binding fails with an opaque 400
 *       that leaks the full enum surface.</li>
 *   <li>{@code q} — free-text query matched case-insensitively
 *       ({@code ILIKE %term%}) against the salon name. Same cap / control-char
 *       {@code @Pattern} as {@code category}; the service escapes the
 *       {@code LIKE} wildcards in the term. Optional.</li>
 *   <li>{@code sort} — allow-listed ordering; see {@link SearchSort}. Bound to
 *       an enum so caller text never reaches the {@code ORDER BY}. Salons have
 *       no per-row price/review column to sort by cheaply, so {@code PRICE_*}
 *       and {@code REVIEWS_DESC} fall back to the default name ordering on this
 *       endpoint (documented in {@code SearchService}); {@code null} also
 *       defaults.</li>
 *   <li>{@code minPrice}, {@code maxPrice} — optional price-band filter against
 *       the salon's aggregate price range ({@code priceMin}/{@code priceMax}
 *       across the salon's masters' active services). A salon matches when its
 *       band overlaps the requested band ({@code priceMin <= maxPrice AND
 *       priceMax >= minPrice}); salons with no active priced service (both
 *       aggregates NULL) are excluded once either bound is supplied. Precision
 *       matches {@link MasterSearchRequest#minPrice()}. Cross-field min ≤ max is
 *       enforced by {@link #isPriceRangeValid()}.</li>
 *   <li>{@code page} — boxed {@code Integer}; {@code null} = use server default (0).
 *       Capped at 500 to bound offset-pagination memory usage: at the {@code size=100}
 *       ceiling that means at most ~50 000 results reachable via offset pagination.
 *       Deeper pagination requires keyset (cursor) pagination, deferred until
 *       phase-9 search overhaul. The previous cap of 10 000 allowed ~1 000 000-row
 *       offsets that degrade into a sort-and-discard scan in Postgres.</li>
 *   <li>{@code size} — boxed {@code Integer}; {@code null} = use server default (20).
 *       Capped at 100 to mirror the global page-size ceiling defined in
 *       {@code application.yml} ({@code spring.data.web.pageable.max-page-size: 100}).
 *       The Spring property only constrains Spring-resolved {@code Pageable}s; the
 *       explicit {@code @Max} on the DTO is the actual enforcement when the controller
 *       builds its own {@code PageRequest}.</li>
 * </ul>
 *
 * <p>Mirror of {@link MasterSearchRequest} for the salon search endpoint —
 * keeps the validation surface identical so callers see consistent 400s on
 * malformed input regardless of which discovery path they hit.</p>
 */
public record SalonSearchRequest(

        @Valid
        LocationFilter location,

        @Size(max = 100, message = "q must be at most 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}<>\"']*$",
                 message = "q must not contain control characters or HTML special characters")
        String q,

        @Size(max = 100, message = "category must be at most 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}<>\"']*$",
                 message = "category must not contain control characters or HTML special characters")
        String category,

        SearchSort sort,

        @DecimalMin(value = "0", message = "minPrice must be at least 0")
        @Digits(integer = 8, fraction = 2, message = "minPrice must have at most 8 integer digits and 2 decimal places")
        BigDecimal minPrice,

        @DecimalMin(value = "0", message = "maxPrice must be at least 0")
        @Digits(integer = 8, fraction = 2, message = "maxPrice must have at most 8 integer digits and 2 decimal places")
        BigDecimal maxPrice,

        @PositiveOrZero(message = "page must be zero or positive")
        @Max(value = 500, message = "page must be at most 500")
        Integer page,

        @Positive(message = "size must be a positive number")
        @Max(value = 100, message = "size must be at most 100")
        Integer size
) {

    /**
     * Cross-field price-range guard mirroring
     * {@link MasterSearchRequest#isPriceRangeValid()} — evaluated at Spring MVC
     * argument-resolution time, before any {@code @Cacheable} proxy intercepts.
     * Returns {@code true} (valid) when either bound is absent: a null on either
     * side simply means "no lower/upper bound" and is not a cross-field
     * violation (the per-field {@code @DecimalMin} already rejects negatives).
     */
    @AssertTrue(message = "minPrice must be less than or equal to maxPrice")
    public boolean isPriceRangeValid() {
        if (minPrice == null || maxPrice == null) {
            return true;
        }
        return minPrice.compareTo(maxPrice) <= 0;
    }
}
