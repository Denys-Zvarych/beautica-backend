package com.beautica.booking.repository;

import java.util.UUID;

/**
 * Single-query projection backing {@code AuthorizationService.canReviewClient} (Phase 355): the
 * {@link BookingCompletionAccess} pair plus {@code masters.is_active}, so an INDEPENDENT_MASTER whose
 * master row was deactivated cannot rate. Deliberately NOT a third component on
 * {@code BookingCompletionAccess}: that projection feeds the shared completion kernel, where a salon
 * owner/admin must keep complete / decline / reschedule over a deactivated master's bookings.
 *
 * @param masterUserId   user id of the booking's master, or {@code null} for a DETACHED master
 * @param masterIsActive {@code masters.is_active}
 * @param salonId        the master's salon id, or {@code null} for an independent master
 */
public record BookingReviewAccess(
        UUID masterUserId,
        boolean masterIsActive,
        UUID salonId
) {}
