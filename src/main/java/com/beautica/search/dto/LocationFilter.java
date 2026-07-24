package com.beautica.search.dto;

import java.util.UUID;

/**
 * Structured, <b>growable</b> location filter (forward-compat mitigation
 * <b>M3</b>) shared by {@link MasterSearchRequest} and
 * {@link SalonSearchRequest}.
 *
 * <h3>M3 rationale (see Phase 10.5 doc §11)</h3>
 * The location filter is intentionally modeled as a structured object, NOT as
 * two flat top-level params and NOT as if district were the final word. Part B
 * (geocoded point/radius search) <em>additively</em> grows this object with
 * {@code lat}, {@code lng}, {@code radius} / {@code bbox} fields. Because the
 * search contract already nests location under one object, that growth is
 * additive — the mobile client adopts new optional fields rather than
 * reshaping the request a second time. The legacy free-text {@code city} /
 * {@code region} top-level params are deliberately removed (Phase 10.5 Step 5,
 * a documented pre-launch breaking change) and replaced by this object.
 *
 * <h3>Validation</h3>
 * Both ids are optional and nullable — every search field is optional;
 * filtering only fires when an id is supplied. No format annotations are
 * needed: Spring's UUID converter rejects a malformed {@code cityId} /
 * {@code districtId} with a generic 400 (no enum/SQL surface leaked), and the
 * ids are validated against the taxonomy at the data layer (FK), not echoed
 * back. District-primary semantics (district wins; districted-city-without-
 * district widens to city on the read side) live in the M2 seam
 * {@link com.beautica.location.DiscoveryLocationResolver}, not here — this
 * record only carries the caller's selection.
 *
 * @param cityId     chosen city id from the cascading picker, or {@code null}
 * @param districtId chosen urban-district id, or {@code null} (only meaningful
 *                   when the chosen city has urban districts)
 */
public record LocationFilter(
        UUID cityId,
        UUID districtId
) {
}
