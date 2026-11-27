package com.beautica.booking.repository;

import com.beautica.master.entity.MasterType;

import java.util.UUID;

/**
 * Single-query projection backing {@code AuthorizationService.canReviewClient} (Phase 355; B546 1b + B547): the
 * {@link BookingCompletionAccess} pair plus {@code masters.is_active}, {@code masters.master_type} and the master's
 * salon {@code is_active}. Deliberately NOT extra components on {@code BookingCompletionAccess}: that projection feeds
 * the shared completion kernel, where a salon owner/admin must keep complete / decline / reschedule over a deactivated
 * master's bookings.
 *
 * @param masterUserId   user id of the booking's master, or {@code null} for a DETACHED master
 * @param masterIsActive {@code masters.is_active}
 * @param salonId        the master's salon id, or {@code null} when the master has none
 * @param masterType     {@code masters.master_type} — the independent arm is derived from THIS (B546 1b), the same as
 *                       the entity form, never from {@code salonId == null}
 * @param salonIsActive  the master's salon {@code is_active}; {@code null} when {@code salonId} is {@code null}
 */
public record BookingReviewAccess(
        UUID masterUserId,
        boolean masterIsActive,
        UUID salonId,
        MasterType masterType,
        Boolean salonIsActive
) {}
