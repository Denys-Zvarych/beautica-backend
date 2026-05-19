package com.beautica.location.dto;

import com.beautica.location.entity.City;

import java.util.UUID;

/**
 * Public read-only projection of a {@link City} for the cascading locality
 * picker (second tier).
 *
 * <p>{@code hasDistricts} tells the future mobile picker whether to descend
 * to the urban-district step. It is computed set-based by the service from a
 * single {@code CityDistrictRepository} query over the whole oblast — never
 * via a per-row {@code existsByCityId} call (§E: no N+1 in the picker).
 *
 * <p>{@code oblastId} is supplied by the service from the request path
 * variable (the known parent id), not by traversing the LAZY
 * {@code City#getOblast()} association, so no lazy-load N+1 is triggered.
 */
public record CityResponse(
        UUID id,
        UUID oblastId,
        String katotthCode,
        String nameUk,
        String nameEn,
        boolean hasDistricts
) {

    /**
     * @param city         the city entity (its LAZY oblast association is
     *                     never touched here)
     * @param oblastId     the parent oblast id, passed from the request path
     * @param hasDistricts pre-computed set-based district presence flag
     */
    public static CityResponse from(City city, UUID oblastId, boolean hasDistricts) {
        return new CityResponse(
                city.getId(),
                oblastId,
                city.getKatotthCode(),
                city.getNameUk(),
                city.getNameEn(),
                hasDistricts);
    }
}
