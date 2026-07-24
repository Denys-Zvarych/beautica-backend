package com.beautica.booking.dto;

import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A master eligible for booking-flow selection under a specific salon service (Phase 23.x —
 * {@code GET /salons/{salonId}/services/{serviceDefId}/masters}).
 *
 * <p>Field names deliberately mirror {@link com.beautica.master.dto.MasterSummaryResponse} (the
 * DTO the salon-profile master roster returns) so the mobile booking-selection screen can swap
 * its data source 1:1 without a shape change. {@code masterType} is omitted — this response is
 * scoped to a single booking decision, not the salon-profile roster.
 *
 * <p>{@code masterServiceId} is the id of THIS master's active
 * {@link MasterServiceAssignment} for the requested service definition — the key the downstream
 * slot ({@code GET /masters/{masterId}/slots?serviceId=…}) and booking-create calls require.
 * Surfacing it here lets the mobile client drop its per-master {@code getMasterServices} fan-out
 * entirely (it no longer has to re-fetch the assignment id after picking a master).
 *
 * <p>Unlike the roster, every master in this list is guaranteed bookable: active, actively
 * assigned to the service, and resolved (via {@code BookingMasterService}) to have at least one
 * working day in the near-term booking horizon — see that class for the schedule-usability gate.
 */
public record BookableMasterResponse(
        UUID masterId,
        UUID masterServiceId,
        String firstName,
        String lastName,
        String professionalTitle,
        String avatarUrl,
        BigDecimal avgRating,
        int reviewCount
) {
    /**
     * @param assignment a {@link MasterServiceAssignment} whose {@code master} and
     *                   {@code master.user} associations are already initialised (JOIN FETCH-ed
     *                   by the caller's query) — dereferencing them here triggers no additional
     *                   lazy load.
     */
    public static BookableMasterResponse from(MasterServiceAssignment assignment) {
        Master master = assignment.getMaster();
        return new BookableMasterResponse(
                master.getId(),
                assignment.getId(),
                master.getUser().getFirstName(),
                master.getUser().getLastName(),
                master.getUser().getProfessionalTitle(),
                master.getUser().getAvatarUrl(),
                master.getAvgRating(),
                master.getReviewCount()
        );
    }
}
