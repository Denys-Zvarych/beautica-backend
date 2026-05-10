package com.beautica.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

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
 * <p><b>Field-by-field rationale:</b></p>
 * <ul>
 *   <li>{@code city}, {@code region} — bounded to 100 chars to mirror the
 *       {@code salons.city VARCHAR(100)} / {@code salons.region VARCHAR(100)}
 *       columns. Control-character pattern blocks log injection and obviously
 *       malformed inputs.</li>
 *   <li>{@code page} — capped at 10000 to prevent absurd offset-pagination
 *       abuse.</li>
 *   <li>{@code size} — capped at 100 to mirror the global page-size ceiling
 *       defined in {@code application.yml}
 *       ({@code spring.data.web.pageable.max-page-size: 100}). The Spring
 *       property only constrains Spring-resolved {@code Pageable}s; the
 *       explicit {@code @Max} on the DTO is the actual enforcement when the
 *       controller builds its own {@code PageRequest}.</li>
 * </ul>
 *
 * <p>Mirror of {@link MasterSearchRequest} for the salon search endpoint —
 * keeps the validation surface identical so callers see consistent 400s on
 * malformed input regardless of which discovery path they hit.</p>
 */
public record SalonSearchRequest(

        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String city,

        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String region,

        @PositiveOrZero
        @Max(10_000)
        int page,

        @Positive
        @Max(100)
        int size
) {
}
