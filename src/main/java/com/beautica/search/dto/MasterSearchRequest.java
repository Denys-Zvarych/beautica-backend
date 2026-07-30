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
import java.math.RoundingMode;
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
 *   <li>{@code q} — free-text name / service-name query. Whitespace-tokenised
 *       and matched case-insensitively ({@code ILIKE %token%}) against the
 *       master's first name, last name, and the (custom-preferred)
 *       service-definition names — every token must match at least one of those
 *       columns, so "Вікторія Руденко" finds the master whose first name is
 *       "Вікторія" and last name "Руденко".
 *       <p><b>The {@code @Pattern} bans control characters ONLY.</b> It
 *       deliberately does <em>not</em> ban {@code < > " '}: Ukrainian
 *       orthography requires the apostrophe constantly (В'ячеслав, Мар'яна,
 *       Дар'я), and banning U+0027 turned every such name into an HTTP 400. The
 *       ban bought no security — the term is bound as a JDBC parameter
 *       ({@code :q0…:q3}), never interpolated into SQL, and the response is
 *       JSON that never reflects {@code q} into HTML. The service additionally
 *       escapes the {@code LIKE} wildcards ({@code %}, {@code _}, {@code \}) in
 *       the supplied term, so a literal {@code %} matches a literal {@code %},
 *       and folds the curly apostrophe U+2019 onto U+0027 so both keyboard
 *       variants match the same rows.
 *       <p>Capped at 100 chars. Optional: {@code null} / blank means "no text
 *       filter". A term whose longest token is shorter than 3 characters is NOT
 *       a validation error — the endpoint returns an explicit empty page plus a
 *       helper {@code message} (see {@code NormalizedSearchQuery}).</li>
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

        // Java's \p{Cntrl} without UNICODE_CHARACTER_CLASS covers only ASCII
        // 0x00–0x1F and 0x7F, so U+0085 NEL, the C1 block, U+200B ZERO WIDTH SPACE,
        // U+2028/U+2029 line/paragraph separators and U+202E RIGHT-TO-LEFT OVERRIDE
        // all passed. Since the `< > " '` ban was (correctly) dropped for Ukrainian
        // apostrophe names, this @Pattern is the ONLY character filter on q, so it
        // is widened to the Unicode categories: Cc (control), Cf (format — bidi
        // overrides, ZWJ/ZWNJ), Zl, Zp. Deliberately NOT a bare \p{C}: that also
        // matches Cn (unassigned), which would 400 every code point Unicode has not
        // allocated yet — i.e. future emoji in a salon name.
        //
        // Defence-in-depth only: q is bound as a JDBC parameter (:q0…:q3), never
        // interpolated into SQL, never logged, reflected, persisted, exported or
        // templated. No exploit path exists today; this keeps it that way.
        @Size(max = 100, message = "q must be at most 100 characters")
        @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]*$",
                 message = "q must not contain control characters")
        String q,

        // Same ASCII-only \p{Cntrl} gap that was closed on `q` above: without
        // UNICODE_CHARACTER_CLASS it covers only 0x00–0x1F and 0x7F, so U+0085 NEL,
        // the C1 block, U+200B ZERO WIDTH SPACE, U+2028/U+2029 and U+202E
        // RIGHT-TO-LEFT OVERRIDE all passed. Widened to the same Unicode categories
        // (Cc, Cf, Zl, Zp) — again NOT a bare \p{C}, which would also match Cn
        // (unassigned) and reject every not-yet-allocated code point.
        //
        // Unlike `q` this field KEEPS the `< > " '` ban: category is a fixed
        // vocabulary (a ServiceCategory enum name, upper-cased and compared to
        // service_definitions.category), so no legitimate value contains an
        // apostrophe or an angle bracket — the ban that had to be dropped from `q`
        // for Ukrainian names (В'ячеслав, Мар'яна) costs nothing here. So this
        // pattern is strictly stricter than q's.
        //
        // Defence-in-depth only: category is bound as a JDBC parameter, never
        // interpolated into SQL and never reflected into HTML. No exploit path
        // exists today; this keeps the control consistent across both filters.
        @Size(max = 100, message = "category must be at most 100 characters")
        @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}<>\"']*$",
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
        @Max(value = SearchResultWindow.MAX_PAGE_INDEX, message = "page must be at most 500")
        Integer page,

        @Positive(message = "size must be a positive number")
        @Max(value = SearchResultWindow.MAX_PAGE_SIZE, message = "size must be at most 100")
        Integer size,

        @Size(max = 20, message = "serviceTypeSlugs must contain at most 20 entries")
        List<
                @Size(max = 255, message = "each serviceTypeSlug must be at most 255 characters")
                @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                         message = "each serviceTypeSlug must be a lowercase hyphenated slug")
                String> serviceTypeSlugs
) implements FreeTextSearchRequest {

    /**
     * Compact constructor — normalizes the scale of every {@code @Digits}-bound
     * decimal to scale 2 ({@link RoundingMode#HALF_UP}) <b>before</b> Jakarta Bean
     * Validation runs. Records bind via the canonical constructor, so this body
     * executes ahead of the {@code @Digits(fraction = 2)} check. Without it, a
     * float-precision artifact from the mobile price slider (e.g.
     * {@code maxPrice=1700.0000000000002}, 13 fraction digits) trips
     * {@code @Digits} and returns a spurious 400.
     *
     * <p>Rounding only collapses excess <em>fraction</em> digits — it never
     * relaxes the integer-digit cap ({@code @Digits(integer = 8/1)}) or the
     * lower/upper bounds ({@code @DecimalMin}/{@code @DecimalMax}): a genuinely
     * too-large value (9+ integer digits) or a negative value still fails
     * validation after rounding. Idempotent with the service-layer
     * {@code SearchService.normalizePrice}, which harmlessly re-normalizes.</p>
     */
    public MasterSearchRequest {
        minPrice = roundToPriceScale(minPrice);
        maxPrice = roundToPriceScale(maxPrice);
        minRating = roundToPriceScale(minRating);
    }

    private static BigDecimal roundToPriceScale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

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
     * Reachable-result-window guard: {@code page * size + size} must not exceed
     * {@link SearchResultWindow#MAX_RESULT_WINDOW} (10 000 rows).
     *
     * <p>Bounds the <b>product</b> of the two per-field caps rather than either cap
     * alone — {@code page=500&size=100} would otherwise ask for a 50 000-row offset,
     * uncached (pages ≥ 5 skip the cache) and costing two statements when out of
     * range. See {@link SearchResultWindow} for the 10 000 figure, why a 400 and not
     * a silent clamp, and why keyset pagination is the long-term answer.</p>
     *
     * <p><b>Method name contract:</b> Jakarta Validation requires an
     * {@link AssertTrue} target to start with {@code is}.</p>
     */
    @AssertTrue(message = "page and size together request results beyond the 10000-result window "
            + "(page * size + size must not exceed 10000); narrow your filters or request a lower page")
    public boolean isWithinResultWindow() {
        return SearchResultWindow.isWithinWindow(page, size);
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
