package com.beautica.service.dto;

import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import io.swagger.v3.oas.annotations.media.Schema;

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
        /** Pre-formatted display string, e.g. {@code "500 ₴"} or {@code "від 500 до 800 ₴"}. */
        String priceDisplay,
        /**
         * Chosen service type id, lifted from the nested {@link ServiceDefinitionResponse}.
         * {@code null} when no service type was chosen (the picker is optional).
         */
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true,
                description = "Chosen service type id; null when no service type was selected.")
        UUID serviceTypeId,
        /**
         * Ukrainian display name of the chosen service type, lifted from the nested
         * {@link ServiceDefinitionResponse}. {@code null} when no service type was chosen.
         */
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Ukrainian display name of the chosen service type; null when none was selected.")
        String serviceTypeNameUk,
        /**
         * Stable slug of the chosen service type, lifted from the nested
         * {@link ServiceDefinitionResponse}. Matches the platform search filter's service-type
         * key ({@code CategoryServiceOption.key}) so the client can pre-check the searched
         * service in the booking flow. {@code null} when no service type was chosen.
         */
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Stable slug of the chosen platform service type (matches the "
                        + "search filter's service-type key); null when none was selected.")
        String serviceTypeSlug
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
                sdResponse.serviceTypeNameUk(),
                sdResponse.serviceTypeSlug()
        );
    }

    /**
     * Masked variant for the {@code permitAll} browse route
     * ({@code GET /masters/&#123;masterId&#125;/services}), mirroring the
     * {@code MasterDetailResponse#fromPublic} precedent.
     *
     * <p><b>What is stripped and why.</b> {@code priceOverride} is a PROVIDER-INTERNAL bookkeeping
     * field: it is non-null exactly when this master charges something other than the salon's
     * definition price, so serving it raw to an anonymous caller discloses whether — and by how
     * much — a master deviates from their salon's list price. That is commercially sensitive and
     * has no consumer: the discovery flow prices off {@code effectivePrice} (the
     * {@code COALESCE(priceOverride, base_price)} floor) and renders bands off
     * {@code priceMin}/{@code priceMax}/{@code priceDisplay}, all of which are retained here. The
     * masked field is the only one dropped; nothing else about the row changes.
     *
     * <p><b>Wire compatibility.</b> {@code priceOverride} is already absent from the vast majority
     * of responses today — any master who has NOT set an override serialises it as null — and it
     * is not a required property in the generated OpenAPI schema. Masking therefore emits a shape
     * clients already handle, and does not alter the schema: the property stays declared and
     * optional, only the runtime value becomes absent on this one anonymous route. Authenticated
     * routes ({@code GET /masters/me/services} and every write path) keep the full
     * {@link #from} variant, so a provider still sees their own override.
     */
    public static MasterServiceResponse fromPublic(MasterServiceResponse full) {
        return new MasterServiceResponse(
                full.id(),
                full.masterId(),
                full.serviceDefinition(),
                null,             // priceOverride — provider-internal, masked for anonymous callers
                full.durationOverrideMinutes(),
                full.effectivePrice(),
                full.effectiveDurationMinutes(),
                full.isActive(),
                full.priceType(),
                full.priceMin(),
                full.priceMax(),
                full.priceDisplay(),
                full.serviceTypeId(),
                full.serviceTypeNameUk(),
                full.serviceTypeSlug()
        );
    }
}
