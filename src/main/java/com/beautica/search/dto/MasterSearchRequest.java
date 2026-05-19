package com.beautica.search.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

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

        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String category,

        @DecimalMin("0")
        @Digits(integer = 8, fraction = 2)
        BigDecimal minPrice,

        @DecimalMin("0")
        @Digits(integer = 8, fraction = 2)
        BigDecimal maxPrice,

        @DecimalMin("0.0")
        @DecimalMax("5.0")
        @Digits(integer = 1, fraction = 2)
        BigDecimal minRating,

        @PositiveOrZero
        @Max(500)
        Integer page,

        @Positive
        @Max(100)
        Integer size
) {
}
