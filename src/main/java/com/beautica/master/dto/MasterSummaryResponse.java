package com.beautica.master.dto;

import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;

import java.math.BigDecimal;
import java.util.UUID;

public record MasterSummaryResponse(
        UUID masterId,
        String firstName,
        String lastName,
        String professionalTitle,
        String avatarUrl,
        /**
         * {@code null} when {@link #reviewCount} is 0 — see
         * {@link BookingDetailResponse#masterAvgRatingOrNull}. The salon roster must not render
         * a phantom "0.0" for a master the booking screen renders as "no reviews yet"
         * (Phase 240 audit, Finding 3).
         */
        BigDecimal avgRating,
        int reviewCount,
        MasterType masterType
) {
    public static MasterSummaryResponse from(Master master) {
        return new MasterSummaryResponse(
                master.getId(),
                master.getUser().getFirstName(),
                master.getUser().getLastName(),
                // Free — the User is already graph-fetched (findBySalonId...WithUser) and
                // dereferenced above for firstName/lastName, so no new N+1 is introduced.
                master.getUser().getProfessionalTitle(),
                null, // TODO: map from user.avatarUrl once Phase 2-B adds it to User
                BookingDetailResponse.masterAvgRatingOrNull(
                        master.getReviewCount(), master.getAvgRating()),
                master.getReviewCount(),
                master.getMasterType()
        );
    }
}
