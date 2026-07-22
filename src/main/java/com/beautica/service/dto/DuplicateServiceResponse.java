package com.beautica.service.dto;

import com.beautica.common.exception.DuplicateServiceException;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Response body for the {@code 409 DUPLICATE_SERVICE} returned when a provider tries to add a
 * service they already offer (same {@code ServiceType}, price/duration irrelevant).
 *
 * <p>{@code code} carries the stable {@link DuplicateServiceException#ERROR_CODE} the mobile
 * client branches on — never the top-level {@code message} string, which is generic copy shared
 * with the other 409s. {@code serviceName} is the provider's own menu entry (echoed so the
 * client can name it in the error copy without a second round-trip);
 * {@code existingServiceDefId} lets the client deep-link to the existing row.
 *
 * <p><b>Both detail fields are nullable and the client must render without them</b> — but they are
 * nullable for DIFFERENT reasons, and neither is simply "null whenever the DB index won":
 * <ul>
 *   <li>{@code existingServiceDefId} is populated only by the service-layer pre-check. When the
 *       DB index catches a race instead, it reports the constraint rather than the surviving row,
 *       and the aborted Postgres transaction rules out looking it up — null there.</li>
 *   <li>{@code serviceName} is populated on BOTH single-write paths, from different sources: the
 *       service TYPE's Ukrainian name on the pre-check path (the same label the menu displays),
 *       and the name the caller just submitted on the DB-index race path (the only label still in
 *       memory once the transaction is aborted). It is null ONLY on the bulk path, where the flush
 *       cannot say which of the N queued rows lost.</li>
 * </ul>
 * {@code code} is always present — it is the only field the client branches on.
 *
 * <p>Not a {@code permitAll} surface: every write path that can raise this is behind a role
 * gate plus an ownership check, so echoing the owner's own service id leaks nothing (anti-bug
 * §I concerns only unauthenticated responses).
 *
 * <p><b>Reachable only from {@code GlobalExceptionHandler}, which springdoc does not scan</b>, so
 * without the {@code @Schema}/{@code @ApiResponse} declarations on this type and on the write
 * endpoints in {@code ServiceController}, no schema for it reaches {@code /api-docs} at all and
 * openapi-generator emits no model for the very body the mobile client is supposed to branch on.
 * The envelope wrapper is declared by {@link DuplicateServiceErrorResponse} — declaring THIS type
 * as the 409 body would be wrong, since the wire shape is the {@code ApiResponse} envelope with
 * this record under {@code data}.
 */
@Schema(name = "DuplicateServiceResponse",
        description = "Payload under `data` of the 409 DUPLICATE_SERVICE response. Branch on `code`; "
                + "both detail fields may be null and the client must render without them.")
public record DuplicateServiceResponse(

        @Schema(description = "Stable machine-readable error code. Always present — the only field "
                + "the client branches on.",
                example = DuplicateServiceException.ERROR_CODE)
        String code,

        @Schema(types = {"string", "null"}, nullable = true,
                description = "Human-readable label for the conflicting service, so the client can "
                        + "name it without a second round-trip: the service TYPE's name when the "
                        + "service-layer pre-check caught the conflict, the name the caller just "
                        + "SUBMITTED when the DB index caught a race instead. Null only on the bulk "
                        + "path.")
        String serviceName,

        @Schema(types = {"string", "null"}, format = "uuid", nullable = true,
                description = "Id of the existing service definition, for a deep-link. Null when the "
                        + "DB index caught the conflict rather than the service-layer pre-check.")
        UUID existingServiceDefId) {

    public static DuplicateServiceResponse from(DuplicateServiceException ex) {
        return new DuplicateServiceResponse(
                DuplicateServiceException.ERROR_CODE,
                ex.getServiceName(),
                ex.getExistingServiceDefId());
    }
}
