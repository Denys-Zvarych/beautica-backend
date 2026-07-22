package com.beautica.service.dto;

import com.beautica.common.ApiResponse;
import com.beautica.common.exception.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OpenAPI declaration of the <b>complete wire shape</b> of the {@code 409 DUPLICATE_SERVICE}
 * response — the standard {@link ApiResponse} envelope with a {@link DuplicateServiceResponse}
 * under {@code data}.
 *
 * <p><b>Why this type exists.</b> The 409 is emitted from
 * {@link GlobalExceptionHandler#handleDuplicateService}, and springdoc scans controller method
 * signatures, not {@code @RestControllerAdvice} handlers — so the shape reaches {@code /api-docs}
 * only if a controller declares it via {@code @ApiResponse}. A springdoc {@code @Schema} can only
 * name a raw {@code Class}, and {@code ApiResponse<T>} is a generic record whose type argument an
 * annotation cannot carry; naming {@code ApiResponse.class} would erase {@code data} to
 * {@code object}, and naming {@code DuplicateServiceResponse.class} would declare the payload as
 * if it were the whole body. Either misdeclaration produces a generated mobile model that decodes
 * a real 409 to all-nulls — the same class of spec defect commit {@code 677f214} fixed for
 * {@code priceMaxAtBooking}. Spelling the envelope out concretely is what makes the declared
 * schema match the bytes on the wire.
 *
 * <p><b>Documentation-only.</b> Nothing constructs or serialises this record; the runtime body is
 * built by the handler as {@code ApiResponse<DuplicateServiceResponse>}. It therefore mirrors
 * {@link ApiResponse}'s three-arg constructor exactly — and only those three components, because
 * that constructor leaves {@code errors} null and {@code @JsonInclude(NON_NULL)} omits it from the
 * JSON entirely on this path. Should {@link ApiResponse}'s wire shape ever change, this record
 * must change with it.
 */
@Schema(name = "DuplicateServiceErrorResponse",
        description = "409 response body when a provider adds a service they already offer. "
                + "Branch on `data.code` (DUPLICATE_SERVICE), never on `message`, which is generic "
                + "copy shared with the other 409s.")
public record DuplicateServiceErrorResponse(

        @Schema(description = "Always false on this response.", example = "false")
        boolean success,

        @Schema(description = "The duplicate-service detail payload.")
        DuplicateServiceResponse data,

        @Schema(description = "Generic human-readable copy. Not branchable.",
                example = "This service already exists")
        String message) {
}
