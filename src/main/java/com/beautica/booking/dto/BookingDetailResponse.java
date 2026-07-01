package com.beautica.booking.dto;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.TimeZones;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * PII access contract: the controller MUST verify the caller is the booking's client
 * or the assigned master/owner before invoking {@code from(booking, ...)}.
 *
 * <p>{@code clientFirstName}/{@code clientLastName} are intentionally visible to SALON_MASTER
 * actors — the master needs the client's name on their calendar. No field-level role
 * differentiation is applied. If {@code canViewBooking} scope ever widens, audit this DTO.
 *
 * <p><b>Phase 19.3 — client enrichment.</b> Adds the master's avatar/type, the (nullable)
 * salon name, the master's discovery address (district-primary locality labels + street/
 * building), the service category, and {@code canReview}. {@code canReview} and the resolved
 * {@code cityLabel}/{@code districtLabel} are NOT derivable from the entity graph alone —
 * {@code canReview} is the COMPLETED+no-review predicate computed by the service, and the
 * locality labels come from the {@code DiscoveryLocationResolver} M2 seam (same FK-join
 * label resolution {@code MasterSearchResult} uses). The discovery locality is district-
 * primary via the salon link when the master is salon-employed, else the master's own user
 * row — mirroring {@code SearchService}'s {@code COALESCE(salon, user)} rule.
 */
public record BookingDetailResponse(
        UUID id,
        UUID clientId,
        UUID masterId,
        UUID masterServiceId,
        String serviceName,
        BookingStatus status,
        ZonedDateTime startsAt,
        ZonedDateTime endsAt,
        BigDecimal priceAtBooking,
        int durationMinutesAtBooking,
        OffsetDateTime createdAt,
        String clientFirstName,
        String clientLastName,
        String masterFirstName,
        String masterLastName,
        String clientComment,
        String providerComment,
        // ── Phase 19.3 client-enrichment fields ──────────────────────────────
        String masterAvatarUrl,
        Role masterType,
        String salonName,
        String cityLabel,
        String districtLabel,
        String street,
        String buildingNo,
        String categoryName,
        boolean canReview
) {

    /**
     * Builds the enriched detail view for the single-entity path. The caller (the service)
     * must supply {@code canReview} (the COMPLETED + no-existing-review predicate) and the
     * resolved discovery locality labels — neither is derivable from the entity graph.
     *
     * <p>The caller MUST have hydrated the full graph (client, master.user, master.salon,
     * masterService.serviceDefinition) — e.g. via {@code BookingRepository.findByIdWithFullGraph}
     * — so the field reads below trigger no lazy SELECTs.
     *
     * <p>The master's discovery address (salon vs own-user) is resolved by the salon-primary
     * rule: a salon-employed master surfaces the salon's name + street/building; an independent
     * master surfaces no salon name and the master's own street/building.
     */
    public static BookingDetailResponse from(
            Booking booking,
            boolean canReview,
            String cityLabel,
            String districtLabel
    ) {
        Master master = booking.getMaster();
        User masterUser = master.getUser();
        Salon salon = master.getSalon();

        String resolvedStreet = salon != null ? salon.getStreet() : masterUser.getStreet();
        String resolvedBuildingNo = salon != null ? salon.getBuildingNo() : masterUser.getBuildingNo();

        return new BookingDetailResponse(
                booking.getId(),
                booking.getClient().getId(),
                master.getId(),
                booking.getMasterService().getId(),
                booking.getMasterService().getServiceDefinition().getName(),
                booking.getStatus(),
                booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getEndsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getPriceAtBooking(),
                booking.getDurationMinutesAtBooking(),
                booking.getCreatedAt().atOffset(ZoneOffset.UTC),
                booking.getClient().getFirstName(),
                booking.getClient().getLastName(),
                masterUser.getFirstName(),
                masterUser.getLastName(),
                booking.getClientComment(),
                booking.getProviderComment(),
                masterUser.getAvatarUrl(),
                masterUser.getRole(),
                salon != null ? salon.getName() : null,
                cityLabel,
                districtLabel,
                resolvedStreet,
                resolvedBuildingNo,
                booking.getMasterService().getServiceDefinition().getCategory(),
                canReview
        );
    }
}
