package com.beautica.booking.repository;

import java.util.UUID;

/**
 * Single-query projection used by {@code AuthorizationService.canCompleteBooking} (Phase 18.4).
 *
 * <p>Unlike {@link BookingViewAccess}, this projection carries the booking's {@code salonId}
 * (nullable — {@code null} for an INDEPENDENT_MASTER booking) so the completion predicate can
 * admit a {@code SALON_ADMIN} assigned to that salon, not only the salon owner. The actor's role
 * is resolved from {@code SecurityContextHolder} (set by {@code JwtAuthenticationFilter}).
 *
 * @param masterUserId the user id of the booking's master (used for the INDEPENDENT_MASTER branch)
 * @param salonId      the id of the master's salon, or {@code null} for an independent master
 */
public record BookingCompletionAccess(
        UUID masterUserId,
        UUID salonId
) {}
