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
        String professionalTitle,
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
                master.getUser().getProfessionalTitle(),
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
     * Returns a copy of {@code full} with PII masked for unauthenticated callers.
     * {@code phoneNumber} is always masked, regardless of master type.
     * <p>
     * Address fields (street, buildingNo, locationNote, cityId, oblastId, districtId) are masked
     * for {@link MasterType#SALON_MASTER} / {@link MasterType#SALON_OWNER} — a salon master's
     * precise address is the salon's business address and is not surfaced on this public-by-id
     * path. For {@link MasterType#INDEPENDENT_MASTER}, the full address is returned unmasked,
     * matching what the master sees on their own {@code /masters/me} profile — an independent
     * master's home/work address IS the discoverable location clients need to find them.
     */
    public static MasterDetailResponse fromPublic(MasterDetailResponse full) {
        boolean isIndependent = full.masterType() == MasterType.INDEPENDENT_MASTER;
        return new MasterDetailResponse(
                full.masterId(), full.firstName(), full.lastName(),
                null,             // phoneNumber — masked for public access, all master types
                full.city(),
                isIndependent ? full.street() : null,
                isIndependent ? full.buildingNo() : null,
                isIndependent ? full.locationNote() : null,
                full.bio(), full.instagram(),
                // professionalTitle is a public-facing headline (like bio/instagram) — surfaced
                // unmasked to unauthenticated callers so master cards can render it.
                full.professionalTitle(),
                full.avatarUrl(), full.avgRating(), full.reviewCount(),
                full.masterType(), full.salon(), full.workingHours(),
                isIndependent ? full.cityId() : null,
                isIndependent ? full.oblastId() : null,
                isIndependent ? full.districtId() : null
        );
    }
}
