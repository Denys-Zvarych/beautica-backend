package com.beautica.search.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

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

        @PositiveOrZero
        @Max(500)
        Integer page,

        @Positive
        @Max(100)
        Integer size
) {
}
