package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Thrown by {@code ServiceCatalogService} when a create/update would leave an owner
 * (a salon or a master) with two ACTIVE copies of the same {@code ServiceType} that share base
 * duration AND name.
 *
 * <p>Encodes the phase-464 rule: one active service per
 * {@code (owner, service type, base duration, lower(btrim(name)))}, <em>regardless of price</em>
 * — if «Класичне нарощення» 60 min is already in the menu, adding it again with a different price
 * is still a duplicate; copies of one type must differ in duration or name.
 *
 * <p>Backed at the storage layer by the partial unique index
 * {@code ux_service_def_owner_type_duration_name_active} (V233, replacing V121's per-type index),
 * which is what makes the guard race-proof — the service-layer pre-check is the friendly path, the index is the truth.
 *
 * <p>Surfaces as a {@code 409 Conflict} carrying the stable error code
 * {@code DUPLICATE_SERVICE} in the response body under {@code data.code}, so the mobile client
 * can branch on it (jump to / highlight the existing row) instead of showing the generic
 * conflict copy. {@code existingServiceDefId} lets the client deep-link straight to the
 * offending service. Like the other flow-control exceptions translated directly to an HTTP
 * response (see {@link BookingElapsedException}, {@link EmailAlreadyRegisteredException}),
 * stack-trace capture is suppressed — this is expected user input, not a fault.
 */
public class DuplicateServiceException extends BusinessException {

    /**
     * Stable error code echoed in the response body under {@code data.code}. The mobile client
     * maps it to the localised Ukrainian copy; the API never ships natural-language messages
     * for client routing.
     */
    public static final String ERROR_CODE = "DUPLICATE_SERVICE";

    /**
     * Display name of the service the owner already has. Safe to echo: it is the provider's
     * OWN menu entry, returned only to a caller already authorised to manage that menu.
     */
    private final String serviceName;

    /**
     * Id of the existing ACTIVE {@code ServiceDefinition} that blocks the write. On a race caught
     * by the V233 index it is looked up afterwards ({@code DuplicateServiceCopyLookup}); it is
     * {@code null} only when that lookup finds no match, fails, or is skipped on a busy pool.
     */
    private final UUID existingServiceDefId;

    public DuplicateServiceException(String serviceName, UUID existingServiceDefId) {
        super(HttpStatus.CONFLICT, "This service already exists for this owner");
        this.serviceName = serviceName;
        this.existingServiceDefId = existingServiceDefId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public UUID getExistingServiceDefId() {
        return existingServiceDefId;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
