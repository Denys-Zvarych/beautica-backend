package com.beautica.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for the first-time bulk service setup flow.
 *
 * <p>Carries a non-empty list of {@link BulkServiceItemRequest} items, one per
 * service-type the master toggled on in the picker. The whole batch is created in a
 * single transaction (all-or-nothing): if any item is invalid — bean validation,
 * duplicate {@code serviceTypeId}, or an inactive/unknown type — the entire request is
 * rejected and nothing is persisted.
 *
 * <p>The {@code @Valid} on the element type cascades per-item bean validation
 * (including the per-item {@code @ServicePriceValid} price-mode check) so each item's
 * field errors surface at the controller boundary as a 400, never as a 500 from the DB.
 *
 * <p>The endpoint is only valid for a master with <em>zero</em> active services; the
 * service layer enforces this and returns 409 otherwise (first-time-only product rule).
 *
 * @param items the services to create — non-empty, capped to a sane upper bound.
 */
public record BulkCreateServicesRequest(

        @NotEmpty(message = "At least one service is required")
        @Size(max = 100, message = "At most 100 services can be created at once")
        @Valid
        List<BulkServiceItemRequest> items
) {
}
