package com.beautica.service.dto;

import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.util.PriceDisplayFormatter;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;

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
 * <p><b>This includes native projections.</b> A repository that projects raw columns instead of
 * entities feeds them through {@link Columns} and {@link #of(Columns)} rather than writing its own
 * {@code COALESCE} in SQL — {@code FavoriteRepository.findFavoriteServiceRows} is exactly that
 * case. The SQL selects the eight raw inputs; the rule that combines them stays here, so a wish-list
 * row and a master-menu row are the same arithmetic on the same numbers, not two copies of it.
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
 *
 * <h2>Phase 314 D1 — the cross-master hull</h2>
 * {@link #hullOfAssignments} folds SEVERAL masters' resolved bands into the one band a
 * salon-level row advertises. It is the same D9 rule applied per contributor and then unioned,
 * and it is the ONLY implementation of that fold — {@code GET /salons/{salonId}/services} and the
 * wish list's SALON arm both call it, so tapping a saved salon service through to the catalogue
 * cannot show a different number.
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
     * The eight raw column values D9's rule reads — the four {@code service_definitions} inputs
     * and the four {@code master_services} override inputs — as plain scalars.
     *
     * <p>Exists so a NATIVE projection row, which carries no {@link ServiceDefinition} or
     * {@link MasterServiceAssignment} entity to hand {@link #ofDefinition}/{@link #ofAssignment},
     * can still reach {@link #of(Columns)} — the one implementation of the rule — instead of
     * re-deriving the band with its own {@code COALESCE}s in SQL. Every override component is
     * {@code null} for a row that has no assignment (the wish list's SALON arm, a bare definition).
     */
    public record Columns(
            @Nullable PriceType definitionPriceType,
            @Nullable BigDecimal definitionBasePrice,
            @Nullable BigDecimal definitionPriceMax,
            int definitionBaseDurationMinutes,
            @Nullable PriceType priceTypeOverride,
            @Nullable BigDecimal priceOverride,
            @Nullable BigDecimal priceMaxOverride,
            @Nullable Integer durationOverrideMinutes
    ) {

        /** The definition's own columns, with every override absent. */
        public static Columns ofDefinition(ServiceDefinition sd) {
            return new Columns(sd.getPriceType(), sd.getBasePrice(), sd.getPriceMax(),
                    sd.getBaseDurationMinutes(),
                    null, null, null, null);
        }

        /** The assignment's columns plus its definition's. */
        public static Columns ofAssignment(MasterServiceAssignment msa) {
            ServiceDefinition sd = msa.getServiceDefinition();
            return new Columns(sd.getPriceType(), sd.getBasePrice(), sd.getPriceMax(),
                    sd.getBaseDurationMinutes(),
                    msa.getPriceTypeOverride(), msa.getPriceOverride(),
                    msa.getPriceMaxOverride(), msa.getDurationOverrideMinutes());
        }
    }

    /**
     * The union hull of several masters' resolved bands (Phase 314 D1) — what a SALON-level row
     * advertises when more than one master can perform the service at more than one price.
     * {@code priceMax} is {@code null} exactly when the hull collapsed to a single figure (every
     * contributor resolves to the same floor and ceiling), which is the COMMON case, not a
     * degenerate range.
     */
    public record Hull(PriceType priceType, BigDecimal priceMin, @Nullable BigDecimal priceMax) {
    }

    /**
     * Derivation for a bare {@link ServiceDefinition} — no master assignment, so no overrides:
     * {@code effectivePrice == priceMin} and {@code effectiveDurationMinutes == baseDurationMinutes}.
     */
    public static ServicePricing ofDefinition(ServiceDefinition sd) {
        return of(Columns.ofDefinition(sd));
    }

    /**
     * Derivation for a master's assignment (Phase 311 D9): the RESOLVED band — the assignment's
     * own {@code priceTypeOverride}/{@code priceOverride}/{@code priceMaxOverride} when it holds
     * one, else the definition's — plus this master's override-aware duration. See the class
     * javadoc for the full resolution rule.
     */
    public static ServicePricing ofAssignment(MasterServiceAssignment msa) {
        return of(Columns.ofAssignment(msa));
    }

    /**
     * The one implementation of Phase 311 D9's resolution rule. {@code priceTypeOverride == null}
     * (a bare definition via {@link #ofDefinition}, or an Inherited assignment) resolves every
     * component from the definition columns; a non-null override resolves {@code priceType} and
     * {@code priceMin} from the override and gates {@code priceMax} on the RESOLVED (not the
     * definition's) shape — a master with an own FIXED band against a RANGE definition must not
     * inherit the definition's ceiling.
     */
    public static ServicePricing of(Columns columns) {
        PriceType priceType = columns.priceTypeOverride() != null
                ? columns.priceTypeOverride()
                : columns.definitionPriceType();
        BigDecimal priceMin = columns.priceOverride() != null
                ? columns.priceOverride()
                : columns.definitionBasePrice();
        BigDecimal priceMax = priceType == PriceType.RANGE
                ? (columns.priceTypeOverride() != null
                        ? columns.priceMaxOverride()
                        : columns.definitionPriceMax())
                : null;

        return new ServicePricing(priceType, priceMin, priceMax,
                display(priceType, priceMin, priceMax),
                effectivePrice(columns.definitionBasePrice(), columns.priceOverride()),
                columns.durationOverrideMinutes() != null
                        ? columns.durationOverrideMinutes()
                        : columns.definitionBaseDurationMinutes());
    }

    /**
     * Phase 314 D1/D3 — the union hull of {@code assignments}' RESOLVED bands
     * ({@link #ofAssignment}), never any single {@link ServiceDefinition}'s own band. Pure
     * in-memory work over an already-loaded, already-bookability-filtered assignment list: this
     * method issues no query and knows nothing about who filtered the list.
     *
     * <p>A FIXED contributor is the degenerate interval {@code [floor, floor]}, per D1's
     * {@code ceiling(msa)} rule. A contributor whose floor resolves to {@code null}
     * ({@link ServiceDefinition#getBasePrice()} carries no entity-level {@code @NotNull}) is
     * skipped rather than allowed to corrupt the hull.
     *
     * <p><b>{@link Optional#empty()} means "no contributor priced this row"</b> — either
     * {@code assignments} was empty (nobody bookable performs the service) or every contributor
     * resolved to a null floor. It is the CALLER's decision what to render then, because the
     * right answer differs per endpoint; both current callers fall back to the definition's own
     * band. This method never returns a half-formed band and never throws for that case.
     */
    public static Optional<Hull> hullOfAssignments(Collection<MasterServiceAssignment> assignments) {
        BigDecimal min = null;
        BigDecimal max = null;

        for (MasterServiceAssignment msa : assignments) {
            ServicePricing pricing = ofAssignment(msa);
            BigDecimal floor = pricing.priceMin();
            if (floor == null) {
                continue;
            }
            BigDecimal ceiling = pricing.priceMax() != null ? pricing.priceMax() : floor;

            min = min == null ? floor : min.min(floor);
            max = max == null ? ceiling : max.max(ceiling);
        }

        if (min == null) {
            return Optional.empty();
        }

        // D4 — every contributing band is closed by construction (chk_master_service_price_mode
        // + MasterServiceBand.validate). Assert it anyway so a future loosening of either
        // guarantee fails loudly here rather than rendering an inverted band to a customer.
        if (max.compareTo(min) < 0) {
            throw new IllegalStateException(
                    "service price hull produced max < min across " + assignments.size()
                            + " assignment(s)");
        }

        BigDecimal renderedMax = max.compareTo(min) == 0 ? null : max;
        return Optional.of(new Hull(renderedMax == null ? PriceType.FIXED : PriceType.RANGE,
                min, renderedMax));
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
     * <p>This is <b>not</b> a second derivation path: {@link #of(Columns)} calls exactly this
     * method, so the formula has one implementation and the D2 guarantee is untouched.
     */
    public static BigDecimal effectivePriceOf(MasterServiceAssignment msa) {
        return effectivePrice(msa.getServiceDefinition().getBasePrice(), msa.getPriceOverride());
    }

    /**
     * The one place a band becomes a display string. Guarded so a legacy definition with no
     * {@code priceType}/{@code basePrice} yields {@code null} rather than a fabricated label.
     *
     * <p>Public for the two {@link Hull} consumers —
     * {@link ServiceDefinitionResponse#fromSalonAggregate} and
     * {@code FavoriteServiceResponse#withSalonHull} — which render an already-folded band and must
     * format it identically. Deriving a band is {@link #of(Columns)}'s job, not this method's.
     */
    public static String display(@Nullable PriceType priceType,
                                 @Nullable BigDecimal priceMin,
                                 @Nullable BigDecimal priceMax) {
        return (priceType != null && priceMin != null)
                ? PriceDisplayFormatter.format(priceType, priceMin, priceMax)
                : null;
    }

    private static BigDecimal effectivePrice(@Nullable BigDecimal basePrice,
                                             @Nullable BigDecimal priceOverride) {
        return priceOverride != null ? priceOverride : basePrice;
    }
}
