package com.beautica.search.service;

import java.util.Set;
import java.util.UUID;

/**
 * The result of {@link SearchSuggestionAvailability#forPlace(SuggestionPlaceKey)} (Phase 331): the
 * categories and service types that have at least one BOOKABLE (search's coarse gate — an active
 * master actively assigned the service, no schedule/free-slot probe) master or salon offering in
 * one place.
 *
 * <p>A CATEGORY is available iff {@code categoryKeys} contains its {@code platform_categories.name}.
 * A SERVICE is available iff {@code serviceTypeIds} contains its {@code service_types.id}.
 * Immutable, cache-safe (Caffeine may hand this object to concurrent callers).
 */
record PlaceAvailability(Set<String> categoryKeys, Set<UUID> serviceTypeIds) {

    static final PlaceAvailability EMPTY = new PlaceAvailability(Set.of(), Set.of());
}
