package com.beautica.search.service;

import java.util.Set;
import java.util.UUID;

/**
 * The full set of place ids that have at least one BOOKABLE offer ANYWHERE (audit-fix cycle 1,
 * finding 2), with no per-place scoping — this is the short-circuit gate
 * {@link SearchSuggestionActivePlaces#snapshot()} produces, distinct from
 * {@link PlaceAvailability} (which is scoped to ONE place and carries category/service-type
 * membership, not city/district membership).
 *
 * <p>A {@code cityId} is "active" iff it appears in {@code cityIds}; a {@code districtId} is
 * active iff it appears in {@code districtIds}. Immutable, cache-safe.
 */
record ActivePlaces(Set<UUID> cityIds, Set<UUID> districtIds) {

    static final ActivePlaces EMPTY = new ActivePlaces(Set.of(), Set.of());
}
