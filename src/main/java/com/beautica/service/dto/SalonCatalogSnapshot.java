package com.beautica.service.dto;

import com.beautica.booking.dto.SalonServiceMasterRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One salon's catalogue AND its per-service bookable performers, computed from the SAME candidate load and the SAME
 * free-slot batch gate ({@code ServiceCatalogService#getSalonCatalogSnapshot}). Internal value only: the public salon
 * booking page ({@code GET /book/s/{slug}/info}) reads both halves from it, so its {@code serviceCategories} and its
 * {@code serviceCoverage} can never come from two different verdict runs. Never serialised as such.
 *
 * @param catalog  exactly what {@code GET /salons/{salonId}/services} returns for the salon
 * @param coverage service definition id → its bookable {@code (masterId, masterServiceId)} pairs, unordered (the
 *                 caller orders); a key exists for every catalogue service and for nothing else
 */
public record SalonCatalogSnapshot(
        SalonServiceCatalogResponse catalog,
        Map<UUID, List<SalonServiceMasterRef>> coverage
) {

    /** The snapshot of a salon with nothing bookable. */
    public static final SalonCatalogSnapshot EMPTY =
            new SalonCatalogSnapshot(new SalonServiceCatalogResponse(List.of()), Map.of());

    /** Defensive, unmodifiable copy (the value is shared through a cache). */
    public SalonCatalogSnapshot {
        Map<UUID, List<SalonServiceMasterRef>> frozen = new LinkedHashMap<>();
        coverage.forEach((serviceId, refs) -> frozen.put(serviceId, List.copyOf(refs)));
        coverage = java.util.Collections.unmodifiableMap(frozen);
    }
}
