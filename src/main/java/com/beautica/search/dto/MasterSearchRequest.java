package com.beautica.search.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Inbound request DTO for {@code GET /api/v1/masters/search}.
 *
 * <p>Bound from query parameters (typically via {@code @ModelAttribute} or
 * Spring's record-parameter binding). Every field is optional and may be
 * {@code null}; validation only fires when the caller actually supplies a
 * value.</p>
 *
 * <p><b>Controller wiring:</b> the controller class binding to this record
 * MUST be annotated with {@link org.springframework.validation.annotation.Validated}
 * (and the method parameter with {@code @Valid}) for the constraints to fire
 * on {@code @ModelAttribute}/{@code @RequestParam}-bound records — class-level
 * method validation is required because Bean Validation does not run on plain
 * {@code @RequestParam} method parameters by default.</p>
 *
 * <p><b>Phase 10.5 breaking change:</b> the legacy free-text {@code city} /
 * {@code region} query params are <b>removed</b> and replaced by the
 * structured {@link LocationFilter} object ({@code location.cityId} /
 * {@code location.districtId}). This is an intended, documented pre-launch
 * breaking change to the search contract (there are no real clients yet); the
 * old exact string-equality location filter was a real bug ("Київ" ≠ "Киев").
 * The {@code @Valid} cascade lets the nested object grow additively in Part B
 * (M3) without reshaping this request again.</p>
 *
 * <p><b>Field-by-field rationale:</b></p>
 * <ul>
 *   <li>{@code location} — structured FK-based locality filter; see
 *       {@link LocationFilter}. {@code @Valid} cascades Bean Validation into
 *       the nested record. Optional: a {@code null} location means "no
 *       location filter".</li>
 *   <li>{@code q} — free-text name / service-name query. Matched
 *       case-insensitively ({@code ILIKE %term%}) against the master's first
 *       name, last name, and the (custom-preferred) service-definition names.
 *       Capped at 100 chars; the same control-char / HTML-special
 *       {@code @Pattern} as {@code category} blocks injection on this
 *       {@code permitAll} endpoint. The service escapes the {@code LIKE}
 *       wildcards ({@code %}, {@code _}, {@code \}) in the supplied term, so a
 *       literal {@code %} matches a literal {@code %}. Optional: {@code null} /
 *       blank means "no text filter".</li>
 *   <li>{@code sort} — allow-listed ordering; see {@link SearchSort}. Bound to
 *       an enum so caller text never reaches the {@code ORDER BY}. A
 *       {@code null} (or unbindable) value falls back to
 *       {@link SearchSort#RATING_DESC} — the historical default.</li>
 *   <li>{@code category} — kept as a free {@code String} (max 100, mirroring
 *       {@code service_definitions.category VARCHAR(100)} from V6) rather than
 *       binding straight to the {@code ServiceCategory} enum. Enum binding
 *       fails with an opaque 400 that leaks the full enum surface; keeping
 *       the field as a String lets the service layer convert and throw a
 *       graceful {@code ValidationException} naming only the offending value.</li>
 *   <li>{@code minPrice}, {@code maxPrice} — precision matches
 *       {@code master_services.price_override NUMERIC(10,2)} (8 integer digits
 *       + 2 fraction = 10 total). {@code @DecimalMin("0")} blocks negatives.
 *       Cross-field check (min ≤ max) is left to the service layer.</li>
 *   <li>{@code minRating} — precision matches
 *       {@code masters.avg_rating NUMERIC(3,2)} (1 integer digit + 2 fraction);
 *       value range 0.00–5.00 reflects the domain rating scale.</li>
 *   <li>{@code page} — boxed {@code Integer}; {@code null} = use server default (0).
 *       Capped at 500 to bound offset-pagination memory usage: at the {@code size=100}
 *       ceiling that means at most ~50 000 results reachable via {@code page}. Deeper
 *       pagination requires keyset (cursor) pagination, deferred until phase-9 search
 *       overhaul. The previous cap of 10 000 permitted ~1 000 000-row offsets, which
 *       degrades into a sort-and-discard scan in Postgres even with the covering index
 *       on {@code masters.avg_rating} added in V36.</li>
 *   <li>{@code size} — boxed {@code Integer}; {@code null} = use server default (20).
 *       Capped at 100 to enforce the global page-size ceiling defined in
 *       {@code application.yml} ({@code spring.data.web.pageable.max-page-size: 100}).
 *       The Spring property sets a default; {@code @PageableDefault} does NOT cap
 *       caller-supplied {@code ?size}, so the explicit {@code @Max} on the DTO is the
 *       actual enforcement on this endpoint.</li>
 * </ul>
 */
public record MasterSearchRequest(

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

        @DecimalMin(value = "0.0", message = "minRating must be between 0.0 and 5.0")
        @DecimalMax(value = "5.0", message = "minRating must be between 0.0 and 5.0")
        @Digits(integer = 1, fraction = 2, message = "minRating must have 1 integer digit and at most 2 decimal places")
        BigDecimal minRating,

        @PositiveOrZero(message = "page must be zero or positive")
        @Max(value = 500, message = "page must be at most 500")
        Integer page,

        @Positive(message = "size must be a positive number")
        @Max(value = 100, message = "size must be at most 100")
        Integer size,

        @Size(max = 20, message = "serviceTypeSlugs must contain at most 20 entries")
        List<
                @Size(max = 255, message = "each serviceTypeSlug must be at most 255 characters")
                @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                         message = "each serviceTypeSlug must be a lowercase hyphenated slug")
                String> serviceTypeSlugs
) {

    /**
     * Cross-field price-range guard evaluated at Spring MVC argument-resolution
     * time — <b>before</b> any {@code @Cacheable} proxy intercepts the service
     * call. This prevents a cached 200 response from a prior valid request being
     * served back for a semantically invalid range (e.g. {@code minPrice=999,
     * maxPrice=1}) on a cached page.
     *
     * <p>Returns {@code true} (valid) when either price bound is absent — the
     * individual per-field constraints ({@code @DecimalMin}) already handle
     * negative values; a null on either side simply means "no lower/upper bound"
     * and is not a cross-field violation.</p>
     *
     * <p><b>Method name contract:</b> Jakarta Validation requires the method
     * targeted by {@link AssertTrue} to start with {@code is} so it is
     * recognised as a getter-style accessor on the record.</p>
     */
    @AssertTrue(message = "minPrice must be less than or equal to maxPrice")
    public boolean isPriceRangeValid() {
        if (minPrice == null || maxPrice == null) {
            return true;
        }
        return minPrice.compareTo(maxPrice) <= 0;
    }

    /**
     * Canonical {@code serviceTypeSlugs} view used for BOTH the {@code @Cacheable}
     * key and the service-layer filter (Phase 20.1): {@code null}-safe, trimmed,
     * lower-cased, blank-dropped, de-duplicated, and sorted. Lower-casing collapses
     * casing variants ({@code "Hair"} / {@code "hair"}) onto one cache key —
     * service-type slugs are a fixed lowercase vocabulary (the {@code @Pattern}
     * enforces lowercase at validation; this is defence-in-depth so the resolver
     * lookup and the cache key stay consistent on any uncached path). Sorting +
     * dedup keep the key stable regardless of caller ordering or repeats, bounding
     * key cardinality; the service resolves and filters off this same list so the
     * cached result always matches the executed query. Never {@code null} — an
     * absent or empty param yields an empty list (no service filter).
     */
    public List<String> normalizedServiceTypeSlugs() {
        if (serviceTypeSlugs == null) {
            return List.of();
        }
        return serviceTypeSlugs.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(slug -> slug.toLowerCase(Locale.ROOT))
                .filter(slug -> !slug.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }
}
