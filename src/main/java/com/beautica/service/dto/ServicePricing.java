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
 * <h2>Phase 311 D9 — the resolved band now reads the assignment's OWN band when it has one</h2>
 * Before Phase 311, {@code master_services} carried only a bare floor override
 * ({@link MasterServiceAssignment#getPriceOverride()}); there was no per-master ceiling or shape,
 * so the display band ({@code priceType}/{@code priceMin}/{@code priceMax}/{@code priceDisplay})
 * came wholly from the {@link ServiceDefinition} and only {@code effectivePrice} was
 * override-aware. {@code V165} added {@code price_type_override} / {@code price_max_override},
 * making a master's own band representable — D2's all-or-nothing invariant guarantees an
 * assignment is either fully Inherited (all three columns NULL, tracking the definition) or fully
 * own-band (all three set, immune to later definition edits). The resolution rule, applied
 * component-wise:
 * <pre>
 *   shape(msa)   = COALESCE(msa.priceTypeOverride, sd.priceType)
 *   floor(msa)   = COALESCE(msa.priceOverride,     sd.basePrice)
 *   ceiling(msa) = shape == RANGE ? COALESCE(msa.priceMaxOverride, sd.priceMax) : null
 * </pre>
 * D2's invariant makes these {@code COALESCE}s degenerate in practice — an assignment overrides
 * all three or none — but they are written component-wise so the expression stays correct for a
 * row written before {@code V165}'s constraint existed (none exist locally, but the formula must
 * not assume it).
 *
 * <p><b>{@code effectivePrice} equals {@code priceMin} now, by construction.</b> Both are
 * {@code floor(msa)} above. They were kept as two separately-named fields before this phase
 * because deriving them independently — band from the definition, floor from the override — could
 * produce {@code priceMin > priceMax} for a RANGE service whose master overrode only the floor.
 * That hazard is gone: the floor and the band it belongs to are now resolved from the SAME source
 * (either both Inherited or both the master's own), so {@code priceMin <= priceMax} is guaranteed
 * by {@code chk_master_service_price_mode} exactly as {@code chk_service_def_price_mode} already
 * guarantees it for a bare definition. The two fields are kept distinct on this record only for
 * backward wire compatibility with {@link MasterServiceResponse} and
 * {@link com.beautica.favorite.dto.FavoriteServiceResponse}, which have always shipped both names.
 *
 * <p>{@code priceDisplay} is {@code null} when the resolved {@code priceType} or {@code priceMin}
 * is absent (no {@code @NotNull} on the entity field; API-created definitions always have both,
 * but legacy rows must not blow up a list read).
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
        return derive(sd, null, null, null, null);
    }

    /**
     * Derivation for a master's assignment (Phase 311 D9): the RESOLVED band — the assignment's
     * own {@code priceTypeOverride}/{@code priceOverride}/{@code priceMaxOverride} when it holds
     * one, else the definition's — plus this master's override-aware duration. See the class
     * javadoc for the full resolution rule.
     */
    public static ServicePricing ofAssignment(MasterServiceAssignment msa) {
        return derive(msa.getServiceDefinition(),
                msa.getPriceTypeOverride(),
                msa.getPriceOverride(),
                msa.getPriceMaxOverride(),
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

    /**
     * The one implementation of Phase 311 D9's resolution rule. {@code priceTypeOverride == null}
     * (a bare definition via {@link #ofDefinition}, or an Inherited assignment) resolves every
     * component from {@code sd}; a non-null override resolves {@code priceType} and
     * {@code priceMin} from the override and gates {@code priceMax} on the RESOLVED (not the
     * definition's) shape — a master with an own FIXED band against a RANGE definition must not
     * inherit the definition's ceiling.
     */
    private static ServicePricing derive(ServiceDefinition sd,
                                         PriceType priceTypeOverride,
                                         BigDecimal priceOverride,
                                         BigDecimal priceMaxOverride,
                                         Integer durationOverrideMinutes) {
        PriceType priceType = priceTypeOverride != null ? priceTypeOverride : sd.getPriceType();
        BigDecimal priceMin = priceOverride != null ? priceOverride : sd.getBasePrice();
        BigDecimal priceMax = priceType == PriceType.RANGE
                ? (priceTypeOverride != null ? priceMaxOverride : sd.getPriceMax())
                : null;

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
