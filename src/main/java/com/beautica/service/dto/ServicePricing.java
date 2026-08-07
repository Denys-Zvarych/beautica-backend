package com.beautica.service.dto;

import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.util.PriceDisplayFormatter;

import java.math.BigDecimal;

/**
 * The single derivation of a service's money and duration, shared by every response DTO
 * that prints them. Not a wire type — a value object consumed by the DTO factories.
 *
 * <h2>Why this exists (Phase 31.4 D2)</h2>
 * The wish list ({@link com.beautica.favorite.dto.FavoriteServiceResponse}) and the master's
 * own service menu ({@link MasterServiceResponse}) must never print different prices for the
 * same service. Re-implementing the COALESCE chain in a second place is how they diverge, so
 * the chain lives here and both DTOs call it.
 *
 * <h2>The non-obvious part: there is no {@code priceMaxOverride}</h2>
 * {@link MasterServiceAssignment#getPriceOverride()} overrides {@code base_price} but nothing
 * overrides {@code price_max}. Deriving the two independently — {@code priceMin =
 * COALESCE(override, base_price)} against a raw {@code priceMax} — can therefore produce
 * {@code priceMin > priceMax} for a RANGE service whose master set a higher override.
 *
 * <p>This class avoids that by keeping the two concerns separate, exactly as
 * {@link MasterServiceResponse} has always done:
 * <ul>
 *   <li><b>The display band</b> ({@code priceType} / {@code priceMin} / {@code priceMax} /
 *       {@code priceDisplay}) comes wholly from the {@link ServiceDefinition} — it is the
 *       service's advertised band, internally consistent by the DB CHECK
 *       {@code price_max >= base_price}. {@code priceMin} is {@code base_price}, the canonical
 *       floor for both FIXED and RANGE.</li>
 *   <li><b>The booking floor</b> ({@code effectivePrice}) is the override-aware
 *       {@code COALESCE(priceOverride, base_price)} — the same formula as
 *       {@code masters.min_effective_price} (V58) and {@code bookings.price_at_booking}.</li>
 * </ul>
 * Mixing the two into one "effective band" is the bug, not the feature.
 *
 * <p>{@code priceDisplay} is {@code null} when the definition has no {@code priceType} or no
 * {@code base_price} (no {@code @NotNull} on the entity field; API-created definitions always
 * have both, but legacy rows must not blow up a list read).
 */
public record ServicePricing(
        PriceType priceType,
        /** Canonical floor — {@code base_price}, for both FIXED and RANGE. */
        BigDecimal priceMin,
        /** RANGE ceiling; {@code null} for FIXED. */
        BigDecimal priceMax,
        /** Pre-formatted band, e.g. {@code "500 ₴"} or {@code "від 600 до 900 ₴"}; nullable. */
        String priceDisplay,
        /** Override-aware booking floor: {@code COALESCE(priceOverride, base_price)}; nullable. */
        BigDecimal effectivePrice,
        /** Override-aware duration: {@code COALESCE(durationOverrideMinutes, baseDurationMinutes)}. */
        int effectiveDurationMinutes
) {

    /**
     * Derivation for a bare {@link ServiceDefinition} — no master assignment, so no overrides:
     * {@code effectivePrice == priceMin} and {@code effectiveDurationMinutes == baseDurationMinutes}.
     */
    public static ServicePricing ofDefinition(ServiceDefinition sd) {
        return derive(sd, null, null);
    }

    /**
     * Derivation for a master's assignment: the definition's display band plus this master's
     * override-aware price floor and duration.
     */
    public static ServicePricing ofAssignment(MasterServiceAssignment msa) {
        return derive(msa.getServiceDefinition(),
                msa.getPriceOverride(),
                msa.getDurationOverrideMinutes());
    }

    /**
     * The override-aware booking floor ALONE — {@code COALESCE(priceOverride, base_price)} —
     * without deriving or formatting the display band.
     *
     * <p>Exists for {@link MasterServiceResponse}, which already carries the band on its nested
     * {@link ServiceDefinitionResponse} (itself built by {@link #ofDefinition}). Calling
     * {@link #ofAssignment} there would run {@link PriceDisplayFormatter#format} — a
     * {@code setScale} + {@code stripTrailingZeros} + concat — a SECOND time per row and throw
     * the result away (2026-08 perf audit F4).
     *
     * <p>This is <b>not</b> a second derivation path: {@link #derive} calls exactly this method,
     * so the formula has one implementation and the D2 guarantee is untouched.
     */
    public static BigDecimal effectivePriceOf(MasterServiceAssignment msa) {
        return effectivePrice(msa.getServiceDefinition(), msa.getPriceOverride());
    }

    /**
     * The override-aware duration ALONE —
     * {@code COALESCE(durationOverrideMinutes, baseDurationMinutes)}. Companion to
     * {@link #effectivePriceOf}; see its javadoc for why the band-free variant exists.
     */
    public static int effectiveDurationMinutesOf(MasterServiceAssignment msa) {
        return effectiveDurationMinutes(msa.getServiceDefinition(), msa.getDurationOverrideMinutes());
    }

    private static ServicePricing derive(ServiceDefinition sd,
                                         BigDecimal priceOverride,
                                         Integer durationOverrideMinutes) {
        BigDecimal priceMin = sd.getBasePrice();
        BigDecimal priceMax = sd.getPriceMax();
        PriceType priceType = sd.getPriceType();

        String priceDisplay = (priceType != null && priceMin != null)
                ? PriceDisplayFormatter.format(priceType, priceMin, priceMax)
                : null;

        return new ServicePricing(priceType, priceMin, priceMax, priceDisplay,
                effectivePrice(sd, priceOverride),
                effectiveDurationMinutes(sd, durationOverrideMinutes));
    }

    private static BigDecimal effectivePrice(ServiceDefinition sd, BigDecimal priceOverride) {
        return priceOverride != null ? priceOverride : sd.getBasePrice();
    }

    private static int effectiveDurationMinutes(ServiceDefinition sd, Integer durationOverrideMinutes) {
        return durationOverrideMinutes != null
                ? durationOverrideMinutes
                : sd.getBaseDurationMinutes();
    }
}
