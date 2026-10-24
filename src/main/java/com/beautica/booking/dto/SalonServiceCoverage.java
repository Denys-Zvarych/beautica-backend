package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Phase 391 — which bookable masters perform one salon service ({@code serviceCoverage} of
 * {@code GET /api/v1/book/s/{slug}/info}). One entry per service of {@code serviceCategories}, in catalogue
 * order; {@code masters} follow the roster order and only ever name masters of the roster.
 *
 * <p>The verdict is the app's: the same free-slot rule {@code GET /salons/{id}/services/{defId}/masters} applies
 * (active master, active assignment, ≥1 free future slot). An empty {@code masters} can only appear inside the
 * 60-second window where the cached catalogue and a fresher verdict disagree — the client treats such a service
 * as not bookable right now.
 */
public record SalonServiceCoverage(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid",
                description = "The service definition id — serviceCategories[].services[].id.")
        UUID serviceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<SalonServiceMasterRef> masters
) {
}
