package com.beautica.master.dto;

import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.entity.WorkingHours;
import com.beautica.salon.dto.PublicSalonResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record MasterDetailResponse(
        UUID masterId,
        String firstName,
        String lastName,
        String phoneNumber,
        String city,
        String street,
        String buildingNo,
        String locationNote,
        String bio,
        String instagram,
        String avatarUrl,
        BigDecimal avgRating,
        int reviewCount,
        MasterType masterType,
        PublicSalonResponse salon,
        List<WorkingHoursResponse> workingHours,
        // Locality cascade IDs — populated for authenticated callers only.
        // Null when the master has no location set or on the public endpoint.
        UUID cityId,
        UUID oblastId,
        UUID districtId
) {
    /**
     * Builds a fully-populated response including locality cascade IDs.
     *
     * @param master    the master entity (user + salon associations must be initialised)
     * @param hours     the master's active working-hours rows
     * @param oblastId  the PK of the Oblast that owns {@code master.getUser().getCityId()};
     *                  {@code null} when the user has no city set
     */
    public static MasterDetailResponse from(Master master, List<WorkingHours> hours, UUID oblastId) {
        return new MasterDetailResponse(
                master.getId(),
                master.getUser().getFirstName(),
                master.getUser().getLastName(),
                master.getUser().getPhoneNumber(),
                master.getUser().getCity(),
                master.getUser().getStreet(),
                master.getUser().getBuildingNo(),
                master.getUser().getLocationNote(),
                master.getUser().getBio(),
                master.getUser().getInstagram(),
                master.getUser().getAvatarUrl(),
                master.getAvgRating(),
                master.getReviewCount(),
                master.getMasterType(),
                master.getSalon() != null ? PublicSalonResponse.from(master.getSalon()) : null,
                hours.stream().map(WorkingHoursResponse::from).toList(),
                master.getUser().getCityId(),
                oblastId,
                master.getUser().getDistrictId()
        );
    }

    /**
     * Returns a copy of {@code full} with PII and internal IDs masked for unauthenticated callers.
     * Masked: phoneNumber, street, buildingNo, locationNote, cityId, oblastId, districtId.
     * Retained: city display name (non-precise locality), all other public fields.
     */
    public static MasterDetailResponse fromPublic(MasterDetailResponse full) {
        return new MasterDetailResponse(
                full.masterId(), full.firstName(), full.lastName(),
                null,             // phoneNumber — masked for public access
                full.city(),
                null, null, null, // street, buildingNo, locationNote — masked for public access
                full.bio(), full.instagram(),
                full.avatarUrl(), full.avgRating(), full.reviewCount(),
                full.masterType(), full.salon(), full.workingHours(),
                null, null, null  // cityId, oblastId, districtId — masked for public access
        );
    }
}
