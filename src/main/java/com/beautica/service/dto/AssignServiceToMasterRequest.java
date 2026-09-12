package com.beautica.service.dto;

import com.beautica.service.entity.PriceType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code POST /api/v1/salons/{salonId}/masters/{masterId}/services} request body.
 *
 * <h2>Phase 312 D1 — the assignment can now set a full price band at creation time</h2>
 * {@code priceOverride} keeps its name and wire position — it always was, and remains, the
 * band's <b>floor</b>. {@code priceType} and {@code priceMax} are NEW and both optional, so the
 * "Inherited" request every existing caller sends today ({@code {"serviceDefId": "..."}}) is
 * unchanged and still produces an Inherited row (all three {@code master_services} band columns
 * {@code NULL}, tracking the salon definition's own band).
 *
 * <p><b>Semantic narrowing, not just an addition.</b> A body sending {@code priceOverride}
 * WITHOUT {@code priceType} was previously accepted (and silently produced a partial,
 * now-illegal row); it is now a {@code 400} — see {@link #isBandLegal()}. Phase 312 D5 swept
 * every known caller: the mobile single-assign call site sends neither field (locked decision,
 * {@code service_target.dart}), so this narrowing is purely additive in practice.
 *
 * <p><b>REUSE-FIRST (Phase 312 D2).</b> The coherence table is {@link MasterServiceBand}'s —
 * the SAME static validator {@code PATCH .../services/{serviceDefId}} (Phase 311) and the
 * {@code /bulk} sibling (Phase 312) both defer to. This is the single {@code @AssertTrue} site
 * for the create path; do not hand-roll a second FIXED/RANGE comparison here.
 *
 * <p><b>No typed error code.</b> An illegal band surfaces through the standard
 * {@code MethodArgumentNotValidException} 400 envelope, identical to the {@code PATCH}
 * endpoint's. {@code SERVICE_PRICE_SHAPE_MISMATCH} is never raised here — Phase 312 retires it
 * (see {@code com.beautica.common.exception.ServicePriceShapeMismatchException}'s javadoc).
 *
 * <p><b>No envelope against the salon definition's band (Phase 311 D3, inherited here).</b> A
 * master may set a band of any shape/floor/ceiling, entirely independent of what the salon
 * definition offers — that is exactly the capability this phase exists to add to the create
 * path (it already existed on the {@code PATCH}).
 */
public record AssignServiceToMasterRequest(
        @NotNull(message = "Service definition ID is required") UUID serviceDefId,

        /** Band shape — presence switches the assignment to the "own band" state (Phase 311 D2). */
        PriceType priceType,

        @DecimalMin(value = "0.01", message = "Price override must be positive")
        @DecimalMax(value = "99999.99", message = "Price override exceeds maximum")
        @Digits(integer = 7, fraction = 2, message = "Price override must have at most 7 integer digits and 2 decimal places")
        BigDecimal priceOverride,

        /** RANGE ceiling — required (and only legal) when {@code priceType == RANGE}. */
        @DecimalMin(value = "0.01", message = "Price ceiling must be positive")
        @DecimalMax(value = "99999.99", message = "Price ceiling exceeds maximum")
        @Digits(integer = 7, fraction = 2, message = "Price ceiling must have at most 7 integer digits and 2 decimal places")
        BigDecimal priceMax,

        @Min(value = 1, message = "Duration override must be at least 1 minute")
        @Max(value = 480, message = "Duration override cannot exceed 480 minutes")
        Integer durationOverrideMinutes
) {

    /**
     * Phase 312 D2/D1 — {@code (priceType, priceOverride, priceMax)} must be a legal band per
     * {@link MasterServiceBand#isLegal}: either fully absent (Inherited — every existing caller)
     * or fully, coherently specified (own band). {@code isLegal}'s {@code priceType == null}
     * branch already requires {@code price}/{@code priceMax} to both be null, so this single
     * check also rejects the partial-band case (bare {@code priceOverride}, no {@code priceType}).
     */
    @AssertTrue(message = "Price band must be fully specified (priceType + priceOverride, and "
            + "priceMax for RANGE) or entirely absent — a partial band is not representable")
    public boolean isBandLegal() {
        return MasterServiceBand.isLegal(priceType, priceOverride, priceMax);
    }
}
