package com.beautica.service.dto;

import com.beautica.service.entity.PriceType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;

/**
 * {@code PATCH /api/v1/salons/{salonId}/masters/{masterId}/services/{serviceDefId}} request body
 * (Phase 311 D4) — edits ONE {@code master_services} assignment's own price band and/or duration
 * override.
 *
 * <h2>{@code null} means "leave unchanged"; clearing is explicit (D4)</h2>
 * Every field is optional. Sending a field means "change this"; omitting it (or sending
 * {@code null}) means "leave it as-is" — the house idiom shared with
 * {@link UpdateServiceDefinitionRequest}. There is no tri-state "set to null" mechanism in this
 * codebase (grepped: no {@code JsonNullable}, no {@code Optional<BigDecimal>} field, no
 * {@code @JsonInclude} beyond {@code ApiResponse}), so clearing the band or the duration override
 * is an explicit boolean flag rather than a null value:
 *
 * <ul>
 *   <li>{@code clearBand = true} — reverts ALL THREE band columns to {@code NULL}; the assignment
 *       returns to the Inherited state (D2) and resumes tracking the shared definition's band.
 *       D2 makes the band atomic, so there is one {@code clearBand}, not three per-column clears —
 *       a per-column clear could produce the partial state D2 forbids.</li>
 *   <li>{@code clearDurationOverride = true} — reverts {@code duration_override_minutes} to
 *       {@code NULL}. Independent of the band (D2 — duration carries no shape and no ceiling).</li>
 * </ul>
 *
 * <h2>Legal bodies</h2>
 * <table border="1">
 *   <caption>Examples</caption>
 *   <tr><th>Body</th><th>Effect</th></tr>
 *   <tr><td>{@code {"priceType":"FIXED","price":750}}</td><td>own band, FIXED 750; duration untouched</td></tr>
 *   <tr><td>{@code {"priceType":"RANGE","price":500,"priceMax":800}}</td><td>own band, RANGE 500-800</td></tr>
 *   <tr><td>{@code {"clearBand":true}}</td><td>reverts to Inherited</td></tr>
 *   <tr><td>{@code {"durationOverrideMinutes":90}}</td><td>duration only; band untouched</td></tr>
 *   <tr><td>{@code {"clearDurationOverride":true}}</td><td>duration reverts to Inherited</td></tr>
 * </table>
 *
 * <h2>Illegal bodies — all {@code 400}, standard bean-validation envelope (D3's last paragraph)</h2>
 * <ul>
 *   <li>{@code {"price":750}} with no {@code priceType} — partial band (D2, D3).</li>
 *   <li>A RANGE band with no {@code priceMax}, a FIXED band carrying a {@code priceMax}, or a
 *       {@code priceMax} below {@code price} — see {@link MasterServiceBand#isLegal}.</li>
 *   <li>{@code clearBand = true} combined with any band field — contradictory.</li>
 *   <li>{@code clearDurationOverride = true} combined with {@code durationOverrideMinutes} —
 *       contradictory, same shape as the {@code clearBand} rule above.</li>
 *   <li>{@code {}} — an entirely empty patch changes nothing and is rejected rather than silently
 *       accepted as a no-op {@code 200}; a mistyped field name (e.g. {@code price_override}
 *       instead of {@code price}) deserialises to exactly this all-null shape.</li>
 * </ul>
 *
 * <p><b>No typed error code.</b> Every rejection above surfaces through
 * {@code GlobalExceptionHandler}'s existing {@code MethodArgumentNotValidException} arm.
 * {@code SERVICE_PRICE_SHAPE_MISMATCH} is never raised by this endpoint — that code means "cannot
 * be represented against the salon's definition", a condition this phase abolishes (Phase 311 D3).
 *
 * <p><b>No envelope against the salon definition's band (D3).</b> {@link MasterServiceBand#isLegal}
 * checks only internal coherence — FIXED/RANGE shape, required fields, floor strictly
 * {@code <} ceiling (Phase 312 D8).
 * A master may price entirely outside the salon definition's band; see D3's justification.
 */
public record UpdateMasterServiceBandRequest(
        PriceType priceType,

        @DecimalMin(value = "0.01", message = "Price must be positive")
        @DecimalMax(value = "99999999.99", message = "Price exceeds maximum")
        @Digits(integer = 8, fraction = 2, message = "Price must have at most 8 integer digits and 2 decimal places")
        BigDecimal price,

        @DecimalMin(value = "0.01", message = "Price ceiling must be positive")
        @DecimalMax(value = "99999999.99", message = "Price ceiling exceeds maximum")
        @Digits(integer = 8, fraction = 2, message = "Price ceiling must have at most 8 integer digits and 2 decimal places")
        BigDecimal priceMax,

        @Min(value = 1, message = "Duration override must be at least 1 minute")
        @Max(value = 480, message = "Duration override cannot exceed 480 minutes")
        Integer durationOverrideMinutes,

        Boolean clearBand,

        Boolean clearDurationOverride
) {

    private boolean bandFieldsAbsent() {
        return MasterServiceBand.isAbsent(priceType, price, priceMax);
    }

    /**
     * D2/D3 — a band that IS present must be internally coherent. When {@code clearBand} is set,
     * this rule is vacuously satisfied here; {@link #isClearBandCoherent()} carries that case
     * instead so the two failure reasons are reported on the field that actually caused them.
     */
    @AssertTrue(message = "Price band must be fully specified (priceType + price, and priceMax "
            + "for RANGE) or entirely absent — a partial band is not representable")
    public boolean isBandLegal() {
        if (Boolean.TRUE.equals(clearBand)) {
            return true;
        }
        return bandFieldsAbsent() || MasterServiceBand.isLegal(priceType, price, priceMax);
    }

    /** D4 — {@code clearBand} and a new band in the same request are contradictory. */
    @AssertTrue(message = "clearBand cannot be combined with priceType/price/priceMax")
    public boolean isClearBandCoherent() {
        return !(Boolean.TRUE.equals(clearBand) && !bandFieldsAbsent());
    }

    /** Symmetric guard for the duration override, mirroring {@link #isClearBandCoherent()}. */
    @AssertTrue(message = "clearDurationOverride cannot be combined with durationOverrideMinutes")
    public boolean isClearDurationOverrideCoherent() {
        return !(Boolean.TRUE.equals(clearDurationOverride) && durationOverrideMinutes != null);
    }

    /** D4 — an entirely empty patch changes nothing and is rejected, not silently accepted. */
    @AssertTrue(message = "Request must change at least one of: price band, duration override")
    public boolean isNotEmpty() {
        return priceType != null
                || price != null
                || priceMax != null
                || durationOverrideMinutes != null
                || Boolean.TRUE.equals(clearBand)
                || Boolean.TRUE.equals(clearDurationOverride);
    }
}
