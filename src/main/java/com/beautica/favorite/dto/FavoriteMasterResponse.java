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
 * <h4>{@code categoryCode} / {@code categoryLabel} — the FILTER axis, not a card field</h4>
 * The approved design's category chips select on this pair, and the design defines it as
 * <em>derived server-side from the last booked service, so both kinds filter through one
 * axis</em> — hence the identical pair on {@link FavoriteSalonResponse}. It is NOT a
 * reinstatement of {@code lastServiceName}: the card renders neither field. The card never
 * showed a service; the FILTER is by category. The client's per-category counts are read only
 * to decide which chips exist.
 *
 * <p>Resolved by {@code FavoriteCategoryResolver} in one batched statement for the whole page —
 * never a per-row subquery on the list projection, which is exactly what the deleted
 * {@code LATERAL} was.
 *
 * <p><b>Both are nullable, and null is COMMON.</b> Favouriting normally precedes booking, so a
 * client who hearts a provider they have never booked with gets {@code null} on both — that is
 * the design's accepted behaviour, not a defect, and the client hides categories with no rows.
 * {@code null} means "no booked history with this provider yet". No profile- or
 * offering-derived fallback is substituted to avoid the null: filing a provider under a
 * category the client never actually booked would answer a different question than the chip
 * asks. The two fields are always both present or both {@code null}.
 *
 * <p><b>Singular by design decision.</b> A provider who performs services in several categories
 * appears under only the one they were last booked for, and can therefore be hidden by a chip
 * they also match. This is a known, accepted consequence of the approved specification; a
 * plural {@code List<FavoriteCategory>} with client-side ANY matching was considered and NOT
 * adopted. Do not re-raise it as a defect.
 *
 * @param avgRating     master's aggregate rating, {@code null} when never reviewed
 * @param salonId       employing salon's id, {@code null} for an independent master
 * @param salonName     employing salon's name, {@code null} for an independent master
 * @param categoryCode  {@code platform_categories.name} (e.g. {@code MANICURE}) of the service
 *                      in this client's most recent booking with this master — the value
 *                      denormalised into {@code service_definitions.category}, not the
 *                      {@code BIGSERIAL} id, and the same vocabulary
 *                      {@code ApprovedCategoryResponse.name} publishes
 * @param categoryLabel that category's Ukrainian {@code platform_categories.display_name}
 *                      (e.g. «Манікюр»)
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
        String categoryCode,
        String categoryLabel
) {
}
