package com.beautica.location;

import java.util.Objects;

/**
 * The human-readable labels of one settlement, as denormalised into the legacy
 * {@code users.city}/{@code users.region} and {@code salons.city}/{@code salons.region}
 * text columns whenever a {@code city_id} is written.
 *
 * <p>Built by the {@code CityRepository#findDisplayNamesById} constructor projection, whose
 * inner join on the {@code NOT NULL} {@code cities.oblast_id} FK guarantees both labels.
 *
 * @param city   the settlement's {@code cities.name_uk}
 * @param region the parent oblast's {@code oblasts.name_uk}
 */
public record SettlementDisplayNames(String city, String region) {

    public SettlementDisplayNames {
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(region, "region");
    }
}
