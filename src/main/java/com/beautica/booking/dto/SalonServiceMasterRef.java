package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Phase 391 — one master who performs a salon service, as listed in {@code serviceCoverage} of
 * {@code GET /api/v1/book/s/{slug}/info}: the two ids the salon guest flow needs, nothing else.
 *
 * <p>Anti-Bug §I-2 ({@code permitAll}): {@code masterId} is the master's public identifier (also served by
 * {@code GET /salons/{id}/masters} and {@code GET /salons/{id}/services/{defId}/masters}); {@code masterServiceId}
 * is the assignment id the slot and booking calls take ({@code serviceId} on
 * {@code /book/s/{slug}/masters/{masterId}/availability|bookable-days}, {@code masterServiceIds} on its
 * {@code /booking}) — the same value {@code BookableMasterResponse.masterServiceId} gives the app. No name,
 * price or contact data here: the master's display data is on the {@code masters} roster item with the same
 * {@code masterId}; the price shown is the salon-wide catalogue price, as in the app.
 */
public record SalonServiceMasterRef(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid",
                description = "Matches SalonBookingSlugMasterItem.masterId; the {masterId} of the salon guest routes.")
        UUID masterId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid",
                description = "This master's assignment of the service: the serviceId / masterServiceIds value "
                        + "of the salon guest availability, bookable-days and booking routes.")
        UUID masterServiceId
) {
}
