package com.beautica.service.dto;

import com.beautica.common.exception.DuplicateServiceException;

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
 * <p><b>Both detail fields are nullable and the client must render without them.</b> They are
 * populated by the service-layer pre-check; when the DB index catches a race instead, it reports
 * the constraint rather than the surviving row, and the aborted Postgres transaction rules out
 * looking either one up. On the bulk path the flush additionally cannot say which of the queued
 * rows lost, so {@code serviceName} is null there too. {@code code} is always present — it is
 * the only field the client branches on.
 *
 * <p>Not a {@code permitAll} surface: every write path that can raise this is behind a role
 * gate plus an ownership check, so echoing the owner's own service id leaks nothing (anti-bug
 * §I concerns only unauthenticated responses).
 */
public record DuplicateServiceResponse(String code, String serviceName, UUID existingServiceDefId) {

    public static DuplicateServiceResponse from(DuplicateServiceException ex) {
        return new DuplicateServiceResponse(
                DuplicateServiceException.ERROR_CODE,
                ex.getServiceName(),
                ex.getExistingServiceDefId());
    }
}
