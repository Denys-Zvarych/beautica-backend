package com.beautica.favorite.dto;

import com.beautica.service.dto.ServicePricing;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the BEAUTY WISH LIST — {@code GET /api/v1/favorites/services} (Phase 31.4;
 * extended by the salon-service-favourites track to merge a second source arm).
 *
 * <h2>Two source arms, one shape (salon-service-favourites track)</h2>
 * A row comes from EITHER a {@code SERVICE} favourite (a chosen master's
 * {@code master_services.id} — {@link #sourceType()} {@code == MASTER}) OR a
 * {@code SALON_SERVICE} favourite (a salon-owned {@code service_definitions.id}, favourited
 * before a master was chosen — {@link #sourceType()} {@code == SALON}). {@code masterServiceId}
 * and {@code masterId} are {@code null} on a SALON row (there is no assignment to point at
 * yet); {@code salonId}/{@code salonName}/{@code salonAvatarUrl} are {@code null} on a MASTER
 * row. {@code serviceDefId} is present on BOTH arms. This is a strictly ADDITIVE change to the
 * wire shape — every field that existed before this track still means exactly what it did.
 *
 * <p>Carries everything the wish-list card renders (service name, master OR salon identity,
 * duration, price band) <b>and</b>, for a MASTER row, everything a rebook needs so
 * «Записатись» never costs a second round-trip: {@code masterServiceId} + {@code masterId} are
 * exactly the two ids {@code CreateBookingRequest} requires. A SALON row has no assignment yet
 * — the client must still pick a master, exactly as browsing the salon catalogue itself works.
 *
 * <h2>Price — FIXED vs RANGE</h2>
 * The wire shape is the same for both arms:
 * <ul>
 *   <li><b>FIXED</b> — {@code priceMax == null}, {@code priceDisplay == "600 ₴"}.</li>
 *   <li><b>RANGE</b> — {@code priceMax} is the ceiling, {@code priceDisplay ==
 *       "від 600 до 900 ₴"}. The client renders {@code priceDisplay} verbatim and never
 *       re-derives a band from {@code priceMin}/{@code priceMax}.</li>
 * </ul>
 *
 * <h3>Where the numbers come from — one derivation, no divergence</h3>
 * The live endpoint builds every row through {@link #fromRow}, off
 * {@code FavoriteRepository.findFavoriteServiceRows}' merged native projection. That projection
 * selects RAW columns only — the definition's four price/duration columns AND the assignment's
 * four override columns — and derives nothing in SQL; {@link #fromRow} feeds them to
 * {@link ServicePricing#of(ServicePricing.Columns)}, the SAME single implementation of Phase 311
 * D9's resolution rule that {@link ServicePricing#ofAssignment} runs for
 * {@code MasterServiceResponse}. So a MASTER row and the master's own service menu print the same
 * band for the same service <b>by construction</b> — Phase 31.4 D2's guarantee, restored after
 * {@code V165} gave a master their own band. There is no second {@code COALESCE} chain anywhere on
 * this path; do not add one.
 *
 * <p>The entity-backed {@link #from} factory goes through {@link ServicePricing#ofAssignment} and
 * is therefore the same arithmetic reached from the other side. It is retained for the unit tests
 * that pin its output against {@code MasterServiceResponse}; no production read path calls it.
 *
 * <h3>A SALON row prints the salon-catalogue HULL, not the definition's band</h3>
 * A SALON row has no assignment, so {@link #fromRow} can only resolve the DEFINITION's own band
 * from the projection — and that is NOT what {@code GET /salons/&#123;salonId&#125;/services}
 * renders: since Phase 314 the catalogue prices each row as the union hull across the salon's
 * BOOKABLE masters (active, assigned, and with a free future slot — a master with no working hours
 * contributes nothing). {@code FavoriteService.listServiceFavorites} therefore overlays that same
 * hull onto every SALON row via {@link #withSalonHull}, computed by
 * {@code ServiceCatalogService#hullsForSalonServices} — the same aggregation the catalogue itself
 * uses — in ONE batched pass for the whole page. Tapping a saved salon service through to the
 * salon cannot show a different number.
 *
 * <p><b>Fallback, documented:</b> when a salon has no currently-bookable master the hull is empty
 * and the row keeps the definition's own band. It is the same fallback
 * {@code ServiceCatalogService#priceForSalonCatalogue} applies for an unpriceable hull, and it
 * keeps a saved card rendering a price rather than blanking it while the salon has nobody free —
 * the favourite row itself deliberately survives such transients (it is filtered only on
 * active-assignment/active-master/active-salon, never on free slots).
 *
 * <h2>Sensitive data (§I)</h2>
 * No {@code priceOverride} (provider-internal bookkeeping — it discloses whether and by how
 * much a master deviates from the salon list price). This route is CLIENT-authenticated, not
 * {@code permitAll}, and still carries only what the card renders — the salon's id/name/avatar
 * are the salon's own PUBLIC profile fields (identical to what {@code /favorites/salons}
 * already exposes), not an internal identifier.
 */
public record FavoriteServiceResponse(
        @Schema(description = "Which favourite arm this row came from — MASTER (a chosen "
                + "master's assignment) or SALON (a salon-catalogue service, no master chosen "
                + "yet).")
        SourceType sourceType,
        /** {@code master_services.id} — equals {@code favorites.target_id} for a MASTER row;
         *  {@code CreateBookingRequest.masterServiceId}. {@code null} for a SALON row. */
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true)
        UUID masterServiceId,
        /** {@code masters.id} — the other id {@code POST /bookings} requires. {@code null} for
         *  a SALON row. */
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true)
        UUID masterId,
        /** {@code service_definitions.id} — present on both arms; equals {@code favorites.target_id}
         *  for a SALON row. */
        UUID serviceDefId,
        String serviceName,
        @Schema(types = {"string", "null"}, nullable = true)
        String masterFirstName,
        @Schema(types = {"string", "null"}, nullable = true)
        String masterLastName,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "users.avatar_url of the performing master; null when unset or "
                        + "for a SALON row.")
        String masterAvatarUrl,
        /** Effective duration, resolved by {@link ServicePricing}:
         *  {@code COALESCE(durationOverrideMinutes, baseDurationMinutes)} for a MASTER row;
         *  {@code baseDurationMinutes} for a SALON row, whose override components are all
         *  {@code null} (no assignment to override from). */
        int durationMinutes,
        PriceType priceType,
        /** Band floor, for both FIXED and RANGE: {@code COALESCE(price_override, base_price)} on
         *  a MASTER row (both the {@link #fromRow} and {@link #from} paths), the lowest bookable
         *  master's floor on a SALON row (see the class javadoc). */
        BigDecimal priceMin,
        @Schema(types = {"number", "null"}, nullable = true,
                description = "RANGE ceiling; null for FIXED.")
        BigDecimal priceMax,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Pre-formatted band, e.g. \"600 ₴\" or \"від 600 до 900 ₴\"; "
                        + "null only for a legacy definition with no price.")
        String priceDisplay,
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true,
                description = "salons.id — null for a MASTER row.")
        UUID salonId,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "salons.name — null for a MASTER row.")
        String salonName,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "salons.avatar_url — null for a MASTER row.")
        String salonAvatarUrl
) {

    /** Discriminates the two favourite arms merged into one wish-list page. */
    public enum SourceType {
        MASTER,
        SALON
    }

    /**
     * Maps one wish-list row: the fetch-joined assignment ({@code serviceDefinition} and
     * {@code master} must be initialised — see
     * {@code FavoriteRepository.findFavoriteServiceRows}) plus the master's identity read as
     * SCALARS off the same statement. This is the MASTER-arm construction path from an actual
     * entity; the merged repository projection (both arms, scalars only) is mapped instead via
     * {@link #fromRow}. Retained because it is the direct, entity-backed path the D2 price-parity
     * unit tests pin against {@code MasterServiceResponse.from(msa)}.
     *
     * <p><b>The three name/avatar strings are parameters, not traversals, deliberately.</b>
     * {@code msa.getMaster().getUser()} would force the whole 32-column {@code users} row —
     * {@code passwordHash}, {@code passwordResetCodeHash}, {@code verificationCodeHash},
     * {@code tokensValidAfter} — into the persistence context for every row of the page, to
     * print three strings (§I, 2026-08 perf audit). The repository selects them as scalars
     * instead and hands them here, so {@code Master.user} stays an uninitialised LAZY proxy.
     * Do not "simplify" this back to a single-argument factory.
     */
    public static FavoriteServiceResponse from(MasterServiceAssignment msa,
                                               String masterFirstName,
                                               String masterLastName,
                                               String masterAvatarUrl) {
        ServicePricing pricing = ServicePricing.ofAssignment(msa);

        return new FavoriteServiceResponse(
                SourceType.MASTER,
                msa.getId(),
                msa.getMaster().getId(),
                msa.getServiceDefinition().getId(),
                msa.getServiceDefinition().getName(),
                masterFirstName,
                masterLastName,
                masterAvatarUrl,
                pricing.effectiveDurationMinutes(),
                pricing.priceType(),
                pricing.priceMin(),
                pricing.priceMax(),
                pricing.priceDisplay(),
                null,
                null,
                null
        );
    }

    /**
     * Maps one row of {@code FavoriteRepository.findFavoriteServiceRows}' merged native
     * projection — the column layout pinned in that method's javadoc (indices 0-18; 19/20 are
     * ordering-only and not read here).
     *
     * <p><b>Every money and duration field is derived by {@link ServicePricing}, never here.</b>
     * The projection hands over the eight RAW inputs (four {@code service_definitions} columns,
     * four {@code master_services} override columns — the latter all {@code NULL} on a SALON row);
     * this method only adapts them into {@link ServicePricing.Columns} so a native row reaches the
     * same single implementation of Phase 311 D9 that an entity does. That is why a wish-list row
     * and {@code MasterServiceResponse} cannot disagree, and why neither this method nor the SQL
     * behind it contains a {@code COALESCE}.
     *
     * <p>A SALON row's band is subsequently replaced by the salon-catalogue hull — see
     * {@link #withSalonHull} and the class javadoc.
     */
    public static FavoriteServiceResponse fromRow(Object[] row) {
        SourceType sourceType = SourceType.valueOf((String) row[0]);
        UUID masterServiceId = (UUID) row[1];
        UUID masterId = (UUID) row[2];
        UUID serviceDefId = (UUID) row[3];
        String serviceName = (String) row[4];
        String masterFirstName = (String) row[5];
        String masterLastName = (String) row[6];
        String masterAvatarUrl = (String) row[7];
        UUID salonId = (UUID) row[16];
        String salonName = (String) row[17];
        String salonAvatarUrl = (String) row[18];

        ServicePricing pricing = ServicePricing.of(new ServicePricing.Columns(
                priceType(row[10]),
                (BigDecimal) row[11],
                (BigDecimal) row[12],
                ((Number) row[8]).intValue(),
                priceType(row[13]),
                (BigDecimal) row[14],
                (BigDecimal) row[15],
                row[9] == null ? null : ((Number) row[9]).intValue()));

        return new FavoriteServiceResponse(
                sourceType,
                masterServiceId,
                masterId,
                serviceDefId,
                serviceName,
                masterFirstName,
                masterLastName,
                masterAvatarUrl,
                pricing.effectiveDurationMinutes(),
                pricing.priceType(),
                pricing.priceMin(),
                pricing.priceMax(),
                pricing.priceDisplay(),
                salonId,
                salonName,
                salonAvatarUrl
        );
    }

    /**
     * Returns a copy of this SALON row priced by the salon-catalogue HULL — the ONLY way to set
     * those fields, since a record has no setter (mirrors
     * {@code ServiceDefinitionResponse#withIsFavorite}).
     *
     * <p>Applied by {@code FavoriteService.listServiceFavorites} to every SALON row for which
     * {@code ServiceCatalogService#hullsForSalonServices} returned a hull, so a saved salon
     * service advertises exactly what {@code GET /salons/&#123;salonId&#125;/services} advertises.
     * A row with no hull (no currently-bookable master) is left as {@link #fromRow} built it — the
     * definition's own band, the documented fallback. Nothing else on the row changes: duration,
     * identity and the two booking ids are untouched.
     */
    public FavoriteServiceResponse withSalonHull(ServicePricing.Hull hull) {
        return new FavoriteServiceResponse(
                sourceType, masterServiceId, masterId, serviceDefId, serviceName,
                masterFirstName, masterLastName, masterAvatarUrl, durationMinutes,
                hull.priceType(), hull.priceMin(), hull.priceMax(),
                ServicePricing.display(hull.priceType(), hull.priceMin(), hull.priceMax()),
                salonId, salonName, salonAvatarUrl);
    }

    /** Native projections carry {@code price_type} as its {@code VARCHAR(10)} storage form. */
    private static PriceType priceType(Object column) {
        return column == null ? null : PriceType.valueOf((String) column);
    }
}
