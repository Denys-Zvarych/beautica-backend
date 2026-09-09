package com.beautica.service.dto;

import com.beautica.common.ApiResponse;
import com.beautica.common.exception.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OpenAPI declaration of the <b>complete wire shape</b> of the {@code 400
 * SERVICE_PRICE_SHAPE_MISMATCH} response — the standard {@link ApiResponse} envelope with a
 * {@link ServicePriceShapeMismatchResponse} under {@code data}.
 *
 * <p><b>Why this type exists — inherited from Phase 302, carried out here (Phase 306).</b> The
 * 400 is emitted from {@link GlobalExceptionHandler#handleServicePriceShapeMismatch}, and springdoc
 * scans controller method signatures, not {@code @RestControllerAdvice} handlers — so the shape
 * reaches {@code /api-docs} only if a controller declares it via {@code @ApiResponse}. Phase 302
 * shipped the exception/response pair with a live and correct wire contract but was forbidden from
 * touching {@code ServiceController.java}, so the declaration never landed and the generated mobile
 * Dio client has no model for this 400. Phase 306 opens that file anyway (D4/D5), so it carries the
 * task. Same reasoning as {@link DuplicateServiceErrorResponse}: a springdoc {@code @Schema} can
 * only name a raw {@code Class}, and {@code ApiResponse<T>} is a generic record whose type argument
 * an annotation cannot carry — naming {@code ApiResponse.class} would erase {@code data} to
 * {@code object}, and naming {@code ServicePriceShapeMismatchResponse.class} would declare the
 * payload as if it were the whole body. Spelling the envelope out concretely is what makes the
 * declared schema match the bytes on the wire.
 *
 * <p><b>Documentation-only.</b> Nothing constructs or serialises this record; the runtime body is
 * built by the handler as {@code ApiResponse<ServicePriceShapeMismatchResponse>}. It therefore
 * mirrors {@link ApiResponse}'s three-arg constructor exactly — and only those three components,
 * because that constructor leaves {@code errors} null and {@code @JsonInclude(NON_NULL)} omits it
 * from the JSON entirely on this path. Should {@link ApiResponse}'s wire shape ever change, this
 * record must change with it.
 *
 * <p>Additive documentation of existing behaviour — this declaration changes no runtime path. The
 * only endpoint that can raise this error is the salon on-behalf bulk endpoint
 * ({@code POST /salons/{salonId}/masters/{masterId}/services/bulk}); the sibling independent-master
 * bulk endpoint never reuses another owner's definition, so it cannot raise this error and does not
 * carry this declaration.
 */
@Schema(name = "ServicePriceShapeMismatchErrorResponse",
        description = "400 response body when a bulk item's price shape cannot be represented "
                + "against the salon definition it would reuse. Branch on `data.code` "
                + "(SERVICE_PRICE_SHAPE_MISMATCH), never on `message`, which is generic copy.")
public record ServicePriceShapeMismatchErrorResponse(

        @Schema(description = "Always false on this response.", example = "false")
        boolean success,

        @Schema(description = "The price-shape-mismatch detail payload.")
        ServicePriceShapeMismatchResponse data,

        @Schema(description = "Generic human-readable copy. Not branchable.",
                example = "Service price does not match the salon's existing service")
        String message) {
}
