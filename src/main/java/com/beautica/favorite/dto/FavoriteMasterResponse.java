package com.beautica.favorite.dto;

import java.util.UUID;

/**
 * A favorited independent master, for {@code GET /api/v1/favorites/masters}
 * ("Улюблені майстри" screen).
 *
 * <p>Mirrors the public master-card shape ({@code MasterSearchResult}): resolved
 * locality labels ({@code cityLabel}/{@code districtLabel}, the taxonomy
 * {@code name_uk}; {@code districtLabel} null for non-districted/unset locality)
 * rather than raw FK UUIDs. This is an authenticated CLIENT-only endpoint, but
 * the labels are the human-readable values the UI renders anyway.
 *
 * <p>{@code lastServiceName} is <b>this client's</b> most-recent booking service
 * name with that master (any status, latest {@code startsAt}), or {@code null}
 * when the client never booked them. Resolved in a single projection query — no
 * N+1 (§E).
 *
 * @param avgRating master's aggregate rating, {@code null} when never reviewed
 */
public record FavoriteMasterResponse(
        UUID masterId,
        String firstName,
        String lastName,
        String avatarUrl,
        String cityLabel,
        String districtLabel,
        Double avgRating,
        String lastServiceName
) {
}
