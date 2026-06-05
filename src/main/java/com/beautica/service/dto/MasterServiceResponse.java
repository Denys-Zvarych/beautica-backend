package com.beautica.service.dto;

import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response DTO for a master's service assignment ({@link MasterServiceAssignment}).
 *
 * <h2>Pricing semantics</h2>
 * <ul>
 *   <li>{@code effectivePrice} — single numeric floor for booking use:
 *       {@code COALESCE(priceOverride, base_price)}. Identical semantics to pre-V67;
 *       {@code masters.min_effective_price} (V58) and booking logic remain unchanged.</li>
 *   <li>{@code priceType}, {@code priceMin}, {@code priceMax}, {@code priceDisplay} —
 *       surfaced from the nested {@link ServiceDefinitionResponse} for display purposes.</li>
 * </ul>
 *
 * <h2>Service type</h2>
 * {@code serviceTypeId} / {@code serviceTypeNameUk} are lifted from the nested
 * {@link ServiceDefinitionResponse} for client convenience; both are {@code null} when no
 * service type was chosen on the create path (the picker is optional).
 */
public record MasterServiceResponse(
        UUID id,
        UUID masterId,
        ServiceDefinitionResponse serviceDefinition,
        BigDecimal priceOverride,
        Integer durationOverrideMinutes,
        /** Single numeric floor: {@code COALESCE(priceOverride, base_price)}. */
        BigDecimal effectivePrice,
        int effectiveDurationMinutes,
        boolean isActive,
        /** Pricing mode surfaced from the service definition. */
        PriceType priceType,
        /** Canonical floor (base_price) surfaced from the service definition. */
        BigDecimal priceMin,
        /** RANGE ceiling surfaced from the service definition; null for FIXED. */
        BigDecimal priceMax,
        /** Pre-formatted display string, e.g. {@code "500 грн"} or {@code "від 500 до 800 грн"}. */
        String priceDisplay,
        /**
         * Chosen service type id, lifted from the nested {@link ServiceDefinitionResponse}.
         * {@code null} when no service type was chosen (the picker is optional).
         */
        UUID serviceTypeId,
        /**
         * Ukrainian display name of the chosen service type, lifted from the nested
         * {@link ServiceDefinitionResponse}. {@code null} when no service type was chosen.
         */
        String serviceTypeNameUk
) {
    public static MasterServiceResponse from(MasterServiceAssignment msa) {
        var sdResponse = ServiceDefinitionResponse.from(msa.getServiceDefinition());

        // effectivePrice is null when both priceOverride and basePrice are null (no @NotNull on basePrice entity field).
        // All API-created ServiceDefinitions have a non-null basePrice, but callers mapping this DTO must null-check.
        // base_price is the canonical RANGE floor, so COALESCE(priceOverride, base_price) = minimum effective price
        // for both modes. masters.min_effective_price (V58) uses the same formula — no change required there.
        var effectivePrice = msa.getPriceOverride() != null
                ? msa.getPriceOverride()
                : msa.getServiceDefinition().getBasePrice();

        var effectiveDuration = msa.getDurationOverrideMinutes() != null
                ? msa.getDurationOverrideMinutes()
                : msa.getServiceDefinition().getBaseDurationMinutes();

        return new MasterServiceResponse(
                msa.getId(),
                msa.getMaster().getId(),
                sdResponse,
                msa.getPriceOverride(),
                msa.getDurationOverrideMinutes(),
                effectivePrice,
                effectiveDuration,
                msa.isActive(),
                sdResponse.priceType(),
                sdResponse.priceMin(),
                sdResponse.priceMax(),
                sdResponse.priceDisplay(),
                sdResponse.serviceTypeId(),
                sdResponse.serviceTypeNameUk()
        );
    }
}
