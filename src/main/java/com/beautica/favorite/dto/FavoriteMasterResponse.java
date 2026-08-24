package com.beautica.favorite.dto;

import java.util.UUID;

/**
 * A favorited master, for {@code GET /api/v1/favorites/masters} ("Улюблені майстри" screen).
 *
 * <p><b>Any {@code MasterType}</b> (mobile Phase 111): independent, salon-employed, or a salon
 * owner working as a master. The former "independent masters only" restriction was removed from
 * {@code FavoriteService#validateMasterTarget}; a client may heart any provider they can book.
 *
 * <p>Mirrors the public master-card shape ({@code MasterSearchResult}): resolved
 * locality labels ({@code cityLabel}/{@code districtLabel}, the taxonomy
 * {@code name_uk}; {@code districtLabel} null for non-districted/unset locality)
 * rather than raw FK UUIDs. This is an authenticated CLIENT-only endpoint, but
 * the labels are the human-readable values the UI renders anyway.
 *
 * <p><b>{@code street} / {@code buildingNo} / {@code locationNote}</b> (mobile Phase 111) are the
 * master's OWN address, read from the {@code users} row — never the employing salon's.
 *
 * <p><b>They are populated ONLY for a master type that passes
 * {@link com.beautica.master.entity.MasterType#disclosesOwnAddress}</b> — i.e. an
 * {@code INDEPENDENT_MASTER}. For a {@code SALON_MASTER} / {@code SALON_OWNER} all three arrive
 * {@code null}, exactly as {@code MasterDetailResponse.fromPublic} masks them on the public master
 * profile; the predicate is CALLED there, not copied, so the two surfaces cannot drift. An earlier
 * revision returned them for every master type and left the suppression to the mobile client —
 * "the client hides it" is not a server-side control, and it also leaked the wrong address
 * outright: a multi-salon owner's {@code users} row carries the most recently created salon's
 * street, so a master working in salon A was handed salon B's. Do not restore the unconditional
 * projection.
 *
 * <p>All three are nullable regardless (the columns have no {@code NOT NULL}; an address is
 * optional on a master profile), so a {@code null} here means "not disclosed OR not set" and the
 * DTO deliberately does not distinguish the two.
 *
 * <p>{@code lastServiceName} was REMOVED in mobile Phase 111 — the approved favourites design no
 * longer renders it. Its {@code LATERAL} "latest booking for this (client, master) pair"
 * subquery was deleted from {@code FavoriteRepository#findFavoriteMasterRows} with it; nothing
 * else read it.
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
        String street,
        String buildingNo,
        String locationNote
) {
}
