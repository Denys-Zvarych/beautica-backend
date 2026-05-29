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
        String city,
        String street,
        String buildingNo,
        String locationNote,
        String bio,
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
                master.getUser().getCity(),
                master.getUser().getStreet(),
                master.getUser().getBuildingNo(),
                master.getUser().getLocationNote(),
                null, // TODO: bio — no backing column yet (masters.bio / users.bio not in schema)
                master.getUser().getAvatarUrl(),
                master.getAvgRating(),
                master.getReviewCount(),
                master.getMasterType(),
                master.getSalon() != null ? PublicSalonResponse.from(master.getSalon()) : null,
                hours.stream().map(WorkingHoursResponse::from).toList()
        );
    }
}
