package com.beautica.master.dto;

import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.entity.WorkingHours;
import com.beautica.salon.dto.SalonResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record MasterDetailResponse(
        UUID masterId,
        UUID userId,
        String firstName,
        String lastName,
        String city,
        String bio,
        String avatarUrl,
        BigDecimal avgRating,
        int reviewCount,
        MasterType masterType,
        SalonResponse salon,
        List<WorkingHoursResponse> workingHours
) {
    public static MasterDetailResponse from(Master master, List<WorkingHours> hours) {
        return new MasterDetailResponse(
                master.getId(),
                master.getUser().getId(),
                master.getUser().getFirstName(),
                master.getUser().getLastName(),
                null, // TODO: map from user.city once Phase 2-B adds it to User
                null, // TODO: map from user.bio once Phase 2-B adds it to User
                null, // TODO: map from user.avatarUrl once Phase 2-B adds it to User
                master.getAvgRating(),
                master.getReviewCount(),
                master.getMasterType(),
                master.getSalon() != null ? SalonResponse.from(master.getSalon()) : null,
                hours.stream().map(WorkingHoursResponse::from).toList()
        );
    }
}
