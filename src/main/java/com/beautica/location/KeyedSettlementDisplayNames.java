package com.beautica.location;

import com.beautica.location.entity.SettlementType;

import java.util.UUID;

/**
 * One row of the batch {@code CityRepository#findDisplayNamesByIdIn} constructor projection:
 * {@link SettlementDisplayNames} keyed by the city id it was resolved for, plus the parent
 * {@code oblastId} — so a list endpoint that needs both ({@code GET /salons/mine}) resolves them in
 * ONE join over the ids instead of a second oblast-only batch over the same ids.
 *
 * <p>A separate flat record because a JPQL constructor expression cannot nest another
 * constructor; {@link #names()} rebuilds the shared value so every caller consumes the one
 * {@link SettlementDisplayNames} shape the single-id path also returns.
 */
public record KeyedSettlementDisplayNames(
        UUID cityId,
        UUID oblastId,
        String city,
        String region,
        SettlementType settlementType,
        String hromadaNameUk
) {

    public SettlementDisplayNames names() {
        return new SettlementDisplayNames(city, region, settlementType, hromadaNameUk);
    }
}
