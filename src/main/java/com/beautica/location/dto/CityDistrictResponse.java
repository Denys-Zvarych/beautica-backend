package com.beautica.location.dto;

import com.beautica.location.entity.CityDistrict;

import java.util.UUID;

/**
 * Public read-only projection of a {@link CityDistrict} for the cascading
 * locality picker (third tier).
 *
 * <p>{@code cityId} is supplied by the service from the request path variable
 * (the known parent id), not by traversing the LAZY
 * {@code CityDistrict#getCity()} association, so no lazy-load N+1 is
 * triggered across the district list (§E).
 */
public record CityDistrictResponse(
        UUID id,
        UUID cityId,
        String katotthCode,
        String nameUk,
        String nameEn
) {

    /**
     * @param district the urban-district entity (its LAZY city association is
     *                  never touched here)
     * @param cityId    the parent city id, passed from the request path
     */
    public static CityDistrictResponse from(CityDistrict district, UUID cityId) {
        return new CityDistrictResponse(
                district.getId(),
                cityId,
                district.getKatotthCode(),
                district.getNameUk(),
                district.getNameEn());
    }
}
