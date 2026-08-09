package com.beautica.service.dto;

import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response DTO for a {@link ServiceDefinition}.
 *
 * <h2>Pricing fields</h2>
 * <ul>
 *   <li>{@code priceType} — the pricing mode ({@code FIXED} or {@code RANGE}).</li>
 *   <li>{@code priceMin} — the canonical floor ({@code base_price}) for both modes.</li>
 *   <li>{@code priceMax} — the ceiling for RANGE; {@code null} for FIXED.</li>
 *   <li>{@code priceDisplay} — pre-formatted display string, e.g. {@code "500 ₴"} or
 *       {@code "від 500 до 800 ₴"}. {@code null} when {@code priceMin} is {@code null}.</li>
 * </ul>
 */
public record ServiceDefinitionResponse(
        UUID id,
        String name,
        String description,
        String category,
        int baseDurationMinutes,
        int bufferMinutesAfter,
        boolean isActive,
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true,
                description = "Chosen service type id; null when no service type was selected.")
        UUID serviceTypeId,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Ukrainian display name of the chosen service type; null when none was selected.")
        String serviceTypeNameUk,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Stable slug of the chosen platform service type (matches "
                        + "CategoryServiceOption.key on the client); null when none was selected.")
        String serviceTypeSlug,
        String photoUrl,
        PriceType priceType,
        BigDecimal priceMin,
        BigDecimal priceMax,
        String priceDisplay
) {
    public static ServiceDefinitionResponse from(ServiceDefinition sd) {
        // Money is derived in exactly one place (Phase 31.4 D2) so that this DTO, the
        // master's service menu and the BEAUTY WISH LIST can never print different prices
        // for the same service. Behaviour is identical to the previous inline derivation.
        ServicePricing pricing = ServicePricing.ofDefinition(sd);

        return new ServiceDefinitionResponse(
                sd.getId(),
                sd.getName(),
                sd.getDescription(),
                sd.getCategory(),
                sd.getBaseDurationMinutes(),
                sd.getBufferMinutesAfter(),
                sd.isActive(),
                sd.getServiceType() != null ? sd.getServiceType().getId() : null,
                sd.getServiceType() != null ? sd.getServiceType().getNameUk() : null,
                sd.getServiceType() != null ? sd.getServiceType().getSlug() : null,
                sd.getPhotoUrl(),
                pricing.priceType(),
                pricing.priceMin(),
                pricing.priceMax(),
                pricing.priceDisplay()
        );
    }
}
