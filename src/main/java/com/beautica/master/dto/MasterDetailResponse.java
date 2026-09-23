package com.beautica.master.dto;

import com.beautica.booking.dto.BookingDetailResponse;
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
        /**
         * {@code null} when {@link #reviewCount} is 0 — never the stored {@code 0.00}.
         * {@code masters.avg_rating} is {@code NOT NULL DEFAULT 0.00} (V4), so an unreviewed
         * master persists a literal zero that is a storage artefact, not a rating. Normalised
         * through {@link BookingDetailResponse#masterAvgRatingOrNull} so this endpoint and
         * {@code GET /bookings/{id}} / {@code GET /masters/{id}/reviews/summary} cannot disagree
         * about the same master (Phase 240 audit, Finding 3).
         */
        BigDecimal avgRating,
        int reviewCount,
        MasterType masterType,
        PublicSalonResponse salon,
        List<WorkingHoursResponse> workingHours,
        // Locality cascade IDs — populated for authenticated callers only.
        // Null when the master has no location set or on the public endpoint.
        UUID cityId,
        UUID oblastId,
        UUID districtId,
        /**
         * Count of the master's CONFIRMED/COMPLETED bookings in the current Kyiv calendar month —
         * the «Записів місяця» tile on the master hub (Qase defect #25).
         *
         * <p><b>Self-read only.</b> {@code null} on every path but {@code GET /masters/me}:
         * {@link #from} never populates it and {@link #fromPublic} nulls it explicitly, so the
         * {@code permitAll()} {@code GET /masters/{masterId}} cannot publish a master's trading
         * volume to anonymous callers. Attach it with {@link #withBookingsThisMonth} at the point
         * of use, never inside a factory that both paths share.
         *
         * <p>Deliberately NOT part of the {@code master-detail-by-user} cache entry: the profile
         * is stable and the count changes with every booking, so it is resolved per request
         * against a cached, booking-free DTO.
         */
        Integer bookingsThisMonth
) {
    /**
     * Builds a fully-populated response including locality cascade IDs.
     *
     * @param master        the master entity (user + salon associations must be initialised)
     * @param hours         the master's active working-hours rows
     * @param oblastId      the PK of the Oblast that owns {@code master.getUser().getCityId()};
     *                      {@code null} when the user has no city set
     * @param salonOblastId the PK of the Oblast that owns {@code master.getSalon().getCityId()},
     *                      resolved by the caller (see {@code MasterService#resolveOblastId});
     *                      {@code null} when the master has no affiliated salon or the salon has
     *                      no city set. Deliberately a SEPARATE resolution from {@code oblastId}
     *                      above — the master's own locality and the salon's business locality
     *                      are different cities in general (e.g. an admin editing before the
     *                      master's profile address is synced).
     */
    public static MasterDetailResponse from(
            Master master, List<WorkingHours> hours, UUID oblastId, UUID salonOblastId) {
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
                BookingDetailResponse.masterAvgRatingOrNull(
                        master.getReviewCount(), master.getAvgRating()),
                master.getReviewCount(),
                master.getMasterType(),
                master.getSalon() != null
                        ? PublicSalonResponse.from(master.getSalon(), salonOblastId) : null,
                hours.stream().map(WorkingHoursResponse::from).toList(),
                master.getUser().getCityId(),
                oblastId,
                master.getUser().getDistrictId(),
                // bookingsThisMonth — never populated here. Three call sites share this factory
                // and all three are @Cacheable; a count baked in would be served stale. See the
                // component's own doc and `withBookingsThisMonth`.
                null
        );
    }

    /**
     * Returns a copy of {@code full} with PII masked for unauthenticated callers.
     * {@code phoneNumber} is always masked, regardless of master type.
     * <p>
     * Address fields (city, street, buildingNo, locationNote, cityId, oblastId, districtId) are
     * masked for {@link MasterType#SALON_MASTER} / {@link MasterType#SALON_OWNER} — a salon master's
     * precise address is the salon's business address and is not surfaced on this public-by-id
     * path. For {@link MasterType#INDEPENDENT_MASTER}, the full address is returned unmasked,
     * matching what the master sees on their own {@code /masters/me} profile — an independent
     * master's home/work address IS the discoverable location clients need to find them.
     * <p>
     * The predicate itself lives in {@link MasterType#disclosesOwnAddress(MasterType)} — it is
     * the locked matrix's single definition, shared with {@code GET /favorites/masters}
     * ({@code FavoriteService#mapMasterRow}). Do not re-inline the {@code == INDEPENDENT_MASTER}
     * comparison here: a second copy is exactly how the favourites surface drifted off the rule.
     */
    public static MasterDetailResponse fromPublic(MasterDetailResponse full) {
        boolean isIndependent = MasterType.disclosesOwnAddress(full.masterType());
        return new MasterDetailResponse(
                full.masterId(), full.firstName(), full.lastName(),
                null,             // phoneNumber — masked for public access, all master types
                // `city` is a DENORMALISED MIRROR of cities.name_uk, written beside cityId by
                // UserService (the same fact in human-readable form) — masking the id while
                // passing the name through would suppress nothing. Gated on the SAME
                // `isIndependent` predicate, not a fourth expression of the rule.
                isIndependent ? full.city() : null,
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
                isIndependent ? full.districtId() : null,
                // bookingsThisMonth — ALWAYS null here, for every master type. This endpoint is
                // `permitAll()`; a master's monthly trading volume is not public. Unlike the
                // address fields above there is no disclosure case to gate on, so this is a
                // constant, not a predicate.
                null
        );
    }

    /**
     * Returns a copy carrying {@code bookingsThisMonth} — the only supported way to populate it.
     *
     * <p>A wither rather than a {@link #from} parameter on purpose: {@code from} is shared by the
     * public path and by three cached call sites, and a count threaded through it would be cached
     * with the profile and served stale, or leaked by whichever caller forgot to pass null. Here
     * the field can only be set by a caller that has already decided it is entitled to it.
     */
    public MasterDetailResponse withBookingsThisMonth(Integer bookingsThisMonth) {
        return new MasterDetailResponse(
                masterId, firstName, lastName, phoneNumber, city, street, buildingNo,
                locationNote, bio, instagram, professionalTitle, avatarUrl, avgRating,
                reviewCount, masterType, salon, workingHours, cityId, oblastId, districtId,
                bookingsThisMonth);
    }
}
