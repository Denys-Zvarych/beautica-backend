package com.beautica.favorite.dto;

import java.util.List;
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
 * <h4>Affiliation — {@code salonId} / {@code salonName}</h4>
 * The employing salon of a salon-affiliated master ({@code SALON_MASTER} / {@code SALON_OWNER}),
 * both {@code null} for an {@code INDEPENDENT_MASTER}. The client keys its affiliation line off
 * {@code salonName != null} and navigates on {@code salonId} — it must never have to parse an id
 * out of a display name. Added after the screen shipped: the card said nothing about where a
 * salon-employed master actually works, and the mobile guard for it was structurally inert
 * because the field did not exist. {@code salonId} is not an §I leak — this endpoint is
 * CLIENT-authenticated, and {@code FavoriteSalonResponse} plus the public salon profile already
 * publish the same id.
 *
 * <h4>{@code street} / {@code buildingNo} / {@code locationNote} — WHOSE address</h4>
 * The address of whoever legitimately has a public one for this card, chosen by the single locked
 * predicate {@link com.beautica.master.entity.MasterType#disclosesOwnAddress}:
 * <ul>
 *   <li><b>{@code INDEPENDENT_MASTER}</b> → the master's OWN address off the {@code users} row.
 *       An independent master's address IS the discoverable location clients need.</li>
 *   <li><b>{@code SALON_MASTER} / {@code SALON_OWNER}</b> → the EMPLOYING SALON's address off the
 *       {@code salons} row. Never the employee's personal one.</li>
 * </ul>
 *
 * <p>The masking of the employee's personal address is unchanged and load-bearing: a salon-employed
 * master has no personal address to disclose, and {@code users.street} is the wrong datum anyway —
 * for a multi-salon owner that column carries the MOST RECENTLY CREATED salon's street, so a master
 * working in salon A was being handed salon B's. What changed is only the fallback: instead of
 * emitting nothing, the card now emits the salon's street, which is public business data already
 * returned unmasked on {@code FavoriteSalonResponse} and on the public salon profile. Withholding
 * it protected nothing and left the client unable to say where to go. This mirrors the locality
 * rule one field group over — <em>salon-or-nothing</em>, never a fall-through to the employee's
 * own row when the salon's columns are {@code null}.
 *
 * <p>All three are nullable regardless (the columns have no {@code NOT NULL} on either table; an
 * address is optional on both a master profile and a salon), so a {@code null} here means "not
 * disclosed OR not set" and the DTO deliberately does not distinguish the two.
 *
 * <p>{@code lastServiceName} was REMOVED in mobile Phase 111 — the approved favourites design no
 * longer renders it. Its {@code LATERAL} "latest booking for this (client, master) pair"
 * subquery was deleted from {@code FavoriteRepository#findFavoriteMasterRows} with it; nothing
 * else read it.
 *
 * <h4>{@code categories} — the FILTER axis, not a card field</h4>
 * The approved design's category chips select on this list, and the axis was reversed from a
 * client-booking-history derivation to an OFFERING derivation: it is now every distinct platform
 * category this master actually performs an ACTIVE service in, not the category of whatever this
 * client last happened to book. The card still renders neither field — the FILTER is by category,
 * the card never was. See {@code FavoriteCategoryResolver}'s class javadoc for the full rationale
 * of the reversal, and this pair's {@link FavoriteSalonResponse} counterpart for the salon arm.
 *
 * <p>Resolved by {@code FavoriteCategoryResolver} in one batched statement for the whole page —
 * never a per-row subquery on the list projection, and never per-provider entity hydration.
 *
 * <p><b>Empty, never {@code null}.</b> A master with no active categorisable service (a brand-new
 * profile, or one whose only services are all inactive) publishes an empty list — the client
 * iterates directly, with no null-check of its own. This replaced an earlier {@code null}-pair
 * contract keyed on client booking history; a master who has never been booked by ANYONE still
 * publishes their real offering here, because the axis no longer asks "what has this client
 * booked" at all.
 *
 * <p><b>Both-or-neither per entry.</b> A category code whose display label cannot currently be
 * resolved (deactivated or never-approved) contributes NO entry to the list, rather than an entry
 * with a {@code null} label — a chip that cannot be drawn is not a choice. This also inherits
 * {@code PlatformCategoryLabelResolver}'s selectability gate for free: a PENDING or deactivated
 * category is invisible here exactly as it is invisible to search, so the two surfaces cannot
 * disagree about which categories exist.
 *
 * <p><b>Plural by design decision (reversed from an earlier singular contract).</b> A provider
 * who performs services in several categories now appears under every one of them, so several
 * chips can each surface the same card. This was a deliberate product reversal of the previous
 * "one category, the last booked one" rule — see {@code FavoriteCategoryResolver}'s javadoc.
 *
 * @param avgRating master's aggregate rating, {@code null} when never reviewed
 * @param salonId   employing salon's id, {@code null} for an independent master
 * @param salonName employing salon's name, {@code null} for an independent master
 * @param categories every distinct platform category this master performs an active service in,
 *                   ordered by display label; empty (never {@code null}) when the master has none
 */
public record FavoriteMasterResponse(
        UUID masterId,
        String firstName,
        String lastName,
        String avatarUrl,
        String cityLabel,
        String districtLabel,
        Double avgRating,
        UUID salonId,
        String salonName,
        String street,
        String buildingNo,
        String locationNote,
        List<FavoriteCategoryView> categories
) {
}
