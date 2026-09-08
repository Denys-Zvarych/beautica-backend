package com.beautica.service.dto;

import com.beautica.common.exception.ServicePriceShapeMismatchException;
import com.beautica.service.entity.PriceType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response body for the {@code 400 SERVICE_PRICE_SHAPE_MISMATCH} returned when a Phase 302 bulk
 * item's price shape cannot be represented against the salon definition it would reuse
 * ({@code master_services} has a {@code price_override} floor but no per-master ceiling and no
 * per-master price type — see {@link ServicePriceShapeMismatchException}).
 *
 * <p>{@code code} carries the stable {@link ServicePriceShapeMismatchException#ERROR_CODE} the
 * mobile client branches on — never the top-level {@code message}, which is generic copy. The
 * remaining fields name the salon's GOVERNING shape so the setup screen can say
 * «салон пропонує цю послугу як 400–900 ₴» without a second round-trip, and deep-link to the
 * salon's row via {@code existingServiceDefId}.
 *
 * <p>{@code salonPriceMax} is null exactly when {@code salonPriceType = FIXED}. Every other field
 * is populated on this path, unlike {@link DuplicateServiceResponse} — the shape check is a pure
 * in-memory comparison against a definition already loaded in the transaction, so there is no
 * DB-race arm that could leave the detail fields empty.
 *
 * <p>Not a {@code permitAll} surface: the only endpoint that can raise this is the salon
 * on-behalf bulk create, behind a role gate plus the salon-management ownership check, so echoing
 * the salon's own price band leaks nothing (anti-bug §I concerns unauthenticated responses).
 */
@Schema(name = "ServicePriceShapeMismatchResponse",
        description = "Payload under `data` of the 400 SERVICE_PRICE_SHAPE_MISMATCH response. "
                + "Branch on `code`; the remaining fields describe the salon definition's "
                + "governing price shape.")
public record ServicePriceShapeMismatchResponse(

        @Schema(description = "Stable machine-readable error code. Always present — the only field "
                + "the client branches on.",
                example = ServicePriceShapeMismatchException.ERROR_CODE)
        String code,

        @Schema(types = {"string", "null"}, nullable = true,
                description = "Display name of the service whose shape clashed (the service type's "
                        + "Ukrainian name).")
        String serviceName,

        @Schema(types = {"string", "null"}, format = "uuid", nullable = true,
                description = "Id of the salon definition that governs the shape, for a deep-link.")
        UUID existingServiceDefId,

        @Schema(description = "The salon definition's pricing mode — the shape the submitted item "
                + "had to match.")
        PriceType salonPriceType,

        @Schema(types = {"string", "null"}, nullable = true,
                description = "The salon definition's floor (base_price), in both modes.")
        BigDecimal salonPriceMin,

        @Schema(types = {"string", "null"}, nullable = true,
                description = "The salon definition's ceiling; null when the salon prices FIXED.")
        BigDecimal salonPriceMax) {

    public static ServicePriceShapeMismatchResponse from(ServicePriceShapeMismatchException ex) {
        return new ServicePriceShapeMismatchResponse(
                ServicePriceShapeMismatchException.ERROR_CODE,
                ex.getServiceName(),
                ex.getExistingServiceDefId(),
                ex.getSalonPriceType(),
                ex.getSalonPriceMin(),
                ex.getSalonPriceMax());
    }
}
