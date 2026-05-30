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
        List<WorkingHoursResponse> workingHours
) {
    public static MasterDetailResponse from(Master master, List<WorkingHours> hours) {
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
                hours.stream().map(WorkingHoursResponse::from).toList()
        );
    }

    /**
     * Returns a copy of {@code full} with PII fields masked for unauthenticated callers.
     * Masked: phoneNumber, street, buildingNo, locationNote.
     * Retained: city (non-precise locality), all other public fields.
     */
    public static MasterDetailResponse fromPublic(MasterDetailResponse full) {
        return new MasterDetailResponse(
                full.masterId(), full.firstName(), full.lastName(),
                null,             // phoneNumber — masked for public access
                full.city(),
                null, null, null, // street, buildingNo, locationNote — masked for public access
                full.bio(), full.instagram(),
                full.avatarUrl(), full.avgRating(), full.reviewCount(),
                full.masterType(), full.salon(), full.workingHours()
        );
    }
}
