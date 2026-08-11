package com.beautica.favorite.dto;

import com.beautica.service.dto.ServicePricing;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import com.beautica.service.util.PriceDisplayFormatter;
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
 * Derived from the SAME three {@code service_definitions} columns
 * ({@code price_type}/{@code base_price}/{@code price_max}) {@link ServicePricing#ofDefinition}
 * reads, for BOTH arms — so the wish list and the master's own menu (or the salon catalogue)
 * can never disagree about a price (Phase 31.4 D2, extended):
 * <ul>
 *   <li><b>FIXED</b> — {@code priceMax == null}, {@code priceDisplay == "600 ₴"}.</li>
 *   <li><b>RANGE</b> — {@code priceMax} is the ceiling, {@code priceDisplay ==
 *       "від 600 до 900 ₴"}. The client renders {@code priceDisplay} verbatim and never
 *       re-derives a band from {@code priceMin}/{@code priceMax}.</li>
 * </ul>
 * {@code priceMin} is the definition's canonical floor ({@code base_price}) — the same value
 * {@code MasterServiceResponse.priceMin} carries, <b>not</b> the override-aware
 * {@code effectivePrice}. That is deliberate: there is no {@code priceMaxOverride}, so pairing
 * an override-aware floor with a raw ceiling can yield {@code priceMin > priceMax}. See
 * {@link ServicePricing} for the full reasoning. A SALON row has no assignment to override from
 * at all, so its band is simply the definition's own — identical to what
 * {@code GET /salons/{salonId}/services} prints for that same definition (anti-divergence: see
 * {@code FavoriteServiceListIT}).
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
        /** Effective duration: {@code COALESCE(durationOverrideMinutes, baseDurationMinutes)}
         *  for a MASTER row; bare {@code baseDurationMinutes} for a SALON row (no override to
         *  apply). */
        int durationMinutes,
        PriceType priceType,
        /** Canonical floor ({@code base_price}) for both FIXED and RANGE. */
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
     * projection — the column layout pinned in that method's javadoc (indices 0-14; 15/16 are
     * ordering-only and not read here).
     *
     * <p>{@code priceDisplay} is derived by calling {@link PriceDisplayFormatter#format} with
     * exactly the arguments {@link ServicePricing#ofDefinition} would pass it for the same
     * {@code service_definitions} row — the repository already selects
     * {@code price_type}/{@code base_price}/{@code price_max} straight off that row for both
     * arms (see that method's javadoc), so there is no second formula, only a second call site
     * forced by the fact that a merged native row carries no {@code ServiceDefinition} entity to
     * hand {@code ofDefinition} itself. Guarded exactly as {@code ServicePricing.derive} guards
     * it: {@code null} unless both {@code priceType} and {@code priceMin} are present (a legacy
     * definition with no price never fabricates a display string).
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
        int durationMinutes = ((Number) row[8]).intValue();
        PriceType priceType = row[9] == null ? null : PriceType.valueOf((String) row[9]);
        BigDecimal priceMin = (BigDecimal) row[10];
        BigDecimal priceMax = (BigDecimal) row[11];
        UUID salonId = (UUID) row[12];
        String salonName = (String) row[13];
        String salonAvatarUrl = (String) row[14];

        String priceDisplay = (priceType != null && priceMin != null)
                ? PriceDisplayFormatter.format(priceType, priceMin, priceMax)
                : null;

        return new FavoriteServiceResponse(
                sourceType,
                masterServiceId,
                masterId,
                serviceDefId,
                serviceName,
                masterFirstName,
                masterLastName,
                masterAvatarUrl,
                durationMinutes,
                priceType,
                priceMin,
                priceMax,
                priceDisplay,
                salonId,
                salonName,
                salonAvatarUrl
        );
    }
}
