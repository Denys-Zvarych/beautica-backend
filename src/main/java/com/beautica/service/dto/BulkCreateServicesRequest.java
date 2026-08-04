package com.beautica.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for the bulk service-create flow.
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
 * <p>The flow is <em>additive</em>: the batch is appended to whatever the master already
 * offers, so the same request backs both initial catalogue setup and later "add more
 * services" passes. The only state-conflict is per-service — an item whose service type the
 * master already offers is rejected with 409 {@code DUPLICATE_SERVICE}.
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
