package com.beautica.favorite.dto;

import com.beautica.service.dto.ServicePricing;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the BEAUTY WISH LIST — {@code GET /api/v1/favorites/services} (Phase 31.4).
 *
 * <p>Carries everything the wish-list card renders (service name, master name + avatar,
 * duration, price band) <b>and</b> everything a rebook needs, so «Записатись» never costs a
 * second round-trip: {@code masterServiceId} + {@code masterId} are exactly the two ids
 * {@code CreateBookingRequest} requires.
 *
 * <h2>Price — FIXED vs RANGE</h2>
 * Derived by {@link ServicePricing}, the same code path
 * {@code MasterServiceResponse} uses, so the wish list and the master's own menu can never
 * disagree about a price (Phase 31.4 D2):
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
 * {@link ServicePricing} for the full reasoning.
 *
 * <h2>Sensitive data (§I)</h2>
 * No {@code priceOverride} (provider-internal bookkeeping — it discloses whether and by how
 * much a master deviates from the salon list price), no salon/owner ids. This route is
 * CLIENT-authenticated, not {@code permitAll}, and still carries only what the card renders.
 */
public record FavoriteServiceResponse(
        /** {@code master_services.id} — equals {@code favorites.target_id}; {@code CreateBookingRequest.masterServiceId}. */
        UUID masterServiceId,
        /** {@code masters.id} — the other id {@code POST /bookings} requires. */
        UUID masterId,
        String serviceName,
        @Schema(types = {"string", "null"}, nullable = true)
        String masterFirstName,
        @Schema(types = {"string", "null"}, nullable = true)
        String masterLastName,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "users.avatar_url of the performing master; null when unset.")
        String masterAvatarUrl,
        /** Effective duration: {@code COALESCE(durationOverrideMinutes, baseDurationMinutes)}. */
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
        String priceDisplay
) {

    /**
     * Maps one wish-list row: the fetch-joined assignment ({@code serviceDefinition} and
     * {@code master} must be initialised — see
     * {@code FavoriteRepository.findFavoriteServiceRows}) plus the master's identity read as
     * SCALARS off the same statement.
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
                msa.getId(),
                msa.getMaster().getId(),
                msa.getServiceDefinition().getName(),
                masterFirstName,
                masterLastName,
                masterAvatarUrl,
                pricing.effectiveDurationMinutes(),
                pricing.priceType(),
                pricing.priceMin(),
                pricing.priceMax(),
                pricing.priceDisplay()
        );
    }
}
