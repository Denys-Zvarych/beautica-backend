package com.beautica.search.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Outbound projection record for {@code GET /api/v1/masters/search}.
 *
 * <p>Returns a flattened, denormalised view of a master suitable for the
 * search results list: identity, display name, resolved locality labels,
 * aggregate rating, an optional avatar URL, and the cheapest effective price
 * across the master's active services (used for client-side price filtering /
 * sorting).</p>
 *
 * <p><b>Phase 10.5 — resolved locality labels:</b> the legacy free-text
 * {@code city} field is replaced by {@code cityLabel} + {@code districtLabel},
 * the taxonomy {@code name_uk} of the master's discovery locality (district
 * when the master's city is districted, else city). They are batch-resolved
 * by the {@code DiscoveryLocationResolver} M2 seam so the mobile client
 * renders human-readable locality with <b>no extra round-trip</b>;
 * {@code districtLabel} is {@code null} for non-districted cities or unset
 * locality. The internal city/district UUIDs are intentionally NOT exposed —
 * this is a {@code permitAll} endpoint and only the display labels are public
 * (§I).</p>
 *
 * <p>This is a response DTO only — no Bean Validation annotations apply.
 * It is intended to be assembled by the search service from a JPQL/SQL
 * projection query, not mapped from a JPA entity graph (avoids dragging
 * unrelated associations into the response).</p>
 *
 * <p>{@code userId} is intentionally absent: this record is returned from
 * a {@code permitAll} endpoint, and exposing internal user UUIDs would
 * enable enumeration of the user table by anonymous callers.</p>
 *
 * <p><b>{@code serviceNames} — custom-preferred procedure names:</b> a short,
 * capped ({@code SERVICE_NAME_CAP}) list of the master's distinct active
 * service names, surfaced so the result card can preview what the master does.
 * Each name is the {@code service_definitions.name} the owner set, which IS the
 * custom name (the platform never overwrites it), so custom-over-default is
 * automatic. The list is never {@code null}: a master with no active, priced
 * services yields an empty list, which serialises as {@code []}. It carries no
 * internal IDs — only the display strings — so it is safe on this
 * {@code permitAll} endpoint (§I).</p>
 *
 * <p><b>{@code priceMax} — FIXED vs RANGE discriminator:</b> the highest
 * effective price across the master's active services ({@code MAX(price_max)}
 * for {@code RANGE} services, else {@code base_price} for {@code FIXED}),
 * mirroring the salon price band. It is {@code null} only when the master has
 * no active, priced services (the same condition under which
 * {@code minEffectivePrice} is {@code null}). When the master effectively
 * carries a single fixed price, {@code priceMax} equals
 * {@code minEffectivePrice}; the mobile card renders the "від" (from) prefix
 * only when {@code priceMax != minEffectivePrice} (a genuine range). The
 * backend never collapses the two values — that is a rendering concern.</p>
 *
 * <p><b>{@code street} / {@code buildingNo} / {@code locationNote} — AUTH-GATED
 * street address:</b> the master's full street-level address
 * ({@code users.street} / {@code users.building_no} / {@code users.location_note}).
 * These are <b>privacy-sensitive</b> (an independent master's home address) and
 * are returned <b>only to an authenticated caller</b>. For an anonymous request
 * all three are {@code null}; the public {@code cityLabel}/{@code districtLabel}
 * remain visible to everyone. {@code locationNote} is a free-text arrival hint
 * ("3rd floor, ring twice") and may be {@code null} even for an authenticated
 * caller when the master recorded none. The values are always computed in SQL
 * and cached on the full object; the auth-gate (nulling for anon) happens
 * per-request <em>after</em> the cache read in the controller, so a warm cache
 * populated by an authenticated caller can never leak addresses to an anonymous
 * one (and vice-versa). See {@code SearchController} for the strip.</p>
 *
 * <p><b>{@code matchedServiceNames} — match preview (Phase 20.3, extended to
 * free text):</b> the capped ({@code SERVICE_NAME_CAP}), distinct list of the
 * master's active service names that actually <em>explain</em> this row, so the
 * result card can show <em>what</em> matched rather than the generic
 * alphabetical top-3 {@code serviceNames}. Populated when the request carries:
 * <ul>
 *   <li><b>{@code serviceTypeSlugs}</b> — the services matching any selected
 *       slug;</li>
 *   <li><b>{@code q}</b> — the services whose own name satisfied at least one
 *       query token while every token was satisfied through that same service
 *       (group-scoped semantics — see {@code SearchService.appendQPredicate});</li>
 *   <li><b>both</b> — their <b>intersection</b>: the services satisfying every
 *       active filter, since anything else would put a service on the card that
 *       does not match what the user asked for.</li>
 * </ul>
 * It is <b>empty</b> (never {@code null}) when neither filter is active, when a
 * {@code q} match was carried entirely by the master's own name, or when the
 * {@code q} and slug filters were satisfied by different services — the card
 * then falls back to {@code serviceNames}. Carries display strings only — safe
 * on this {@code permitAll} endpoint (§I).</p>
 */
public record MasterSearchResult(
        UUID masterId,
        String firstName,
        String lastName,
        String cityLabel,
        String districtLabel,
        Double avgRating,
        Integer reviewCount,
        String avatarUrl,
        BigDecimal minEffectivePrice,
        BigDecimal priceMax,
        List<String> serviceNames,
        String street,
        String buildingNo,
        String locationNote,
        List<String> matchedServiceNames
) {

    /**
     * Returns a copy with the auth-gated street-level fields ({@code street},
     * {@code buildingNo}, {@code locationNote}) nulled out, for serving to
     * anonymous callers. All other fields — including the public locality
     * labels — are preserved.
     *
     * <p>Applied per-request in the controller <em>after</em> the
     * {@code @Cacheable} read so the cache always holds the full object and
     * never leaks addresses across the anon/authenticated boundary.</p>
     */
    public MasterSearchResult withoutStreetAddress() {
        return new MasterSearchResult(
                masterId, firstName, lastName, cityLabel, districtLabel,
                avgRating, reviewCount, avatarUrl, minEffectivePrice, priceMax,
                serviceNames, null, null, null, matchedServiceNames
        );
    }
}
