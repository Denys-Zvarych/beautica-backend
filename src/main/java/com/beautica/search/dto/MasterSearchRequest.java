package com.beautica.search.dto;

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
 * <p><b>Field-by-field rationale:</b></p>
 * <ul>
 *   <li>{@code city}, {@code region} — bounded to 100 chars to mirror the
 *       {@code users.city VARCHAR(100)} / {@code users.region VARCHAR(100)}
 *       columns added in V35. Control-character pattern blocks log injection
 *       and obviously malformed inputs.</li>
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
 *   <li>{@code page} — capped at 10000 to prevent absurd offset-pagination
 *       abuse against a catalogue that will never approach that size.</li>
 *   <li>{@code size} — capped at 100 to enforce the global page-size ceiling
 *       defined in {@code application.yml} ({@code spring.data.web.pageable.max-page-size: 100}).
 *       The Spring property sets a default; {@code @PageableDefault} does NOT
 *       cap caller-supplied {@code ?size}, so the explicit {@code @Max} on the
 *       DTO is the actual enforcement on this endpoint.</li>
 * </ul>
 */
public record MasterSearchRequest(

        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String city,

        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String region,

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
        Double minRating,

        @PositiveOrZero
        @Max(10_000)
        int page,

        @Positive
        @Max(100)
        int size
) {
}
