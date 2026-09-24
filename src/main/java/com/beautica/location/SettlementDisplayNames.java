package com.beautica.location;

import com.beautica.location.entity.SettlementType;

import java.util.Objects;

/**
 * The human-readable labels of one settlement, as denormalised into the legacy
 * {@code users.city}/{@code users.region} and {@code salons.city}/{@code salons.region}
 * text columns whenever a {@code city_id} is written — plus the two structured parts a client
 * needs to compose the full settlement label («м. Львів, Львівська обл.» /
 * «с. Іванівка, Шишацька громада, Полтавська обл.») for a SAVED locality.
 *
 * <p>Built by the {@code CityRepository#findDisplayNamesById} constructor projection, whose
 * inner join on the {@code NOT NULL} {@code cities.oblast_id} FK guarantees both labels.
 *
 * <p>{@code settlementType} and {@code hromadaNameUk} follow EXACTLY the contract of
 * {@code GET /api/v1/settlements} ({@link com.beautica.location.dto.SettlementSearchResponse}):
 * the hromada is populated ONLY when the settlement's name+oblast pair is ambiguous
 * ({@code cities.ambiguous_in_oblast}), so the client composes the same label from a saved
 * locality as from the autocomplete row it picked, with no ambiguity logic of its own.
 *
 * @param city           the settlement's {@code cities.name_uk}
 * @param region         the parent oblast's {@code oblasts.name_uk}
 * @param settlementType the settlement's {@code cities.settlement_type}
 * @param hromadaNameUk  bare hromada adjective, or {@code null} when the oblast already
 *                       disambiguates the settlement
 */
public record SettlementDisplayNames(
        String city,
        String region,
        SettlementType settlementType,
        String hromadaNameUk
) {

    public SettlementDisplayNames {
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(settlementType, "settlementType");
    }
}
