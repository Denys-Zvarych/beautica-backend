package com.beautica.location;

import java.util.UUID;

/**
 * Immutable carrier for the locality fields a profile-save submits, decoupled
 * from any particular request DTO.
 *
 * <p>Salon-update and independent-master / client profile-update DTOs each
 * project their own {@code cityId}/{@code districtId} fields into this record
 * so {@link LocalityWriteValidator} has a single input shape to reason about
 * (the most-specific-node rule is identical regardless of which surface the
 * write came from). Only {@code cityId} and {@code districtId} participate in
 * the most-specific-node rule; the light address fields (street / building /
 * note) are free-text and validated for length at the DTO boundary, not here.
 *
 * @param cityId     chosen city FK, or {@code null} if the caller omitted it
 * @param districtId chosen urban-district FK, or {@code null}
 */
public record LocalityWriteInput(UUID cityId, UUID districtId) {

    /**
     * Convenience factory for callers that only carry the FK pair.
     *
     * @param cityId     chosen city FK, or {@code null}
     * @param districtId chosen urban-district FK, or {@code null}
     * @return a new {@code LocalityWriteInput}
     */
    public static LocalityWriteInput of(UUID cityId, UUID districtId) {
        return new LocalityWriteInput(cityId, districtId);
    }
}
