package com.beautica.booking.repository;

import java.util.UUID;

/**
 * Single-query projection used by {@code AuthorizationService.canViewBooking}.
 *
 * <p>Contains only the booking's ownership identifiers. The actor's role is resolved
 * from {@code SecurityContextHolder} (set by {@code JwtAuthenticationFilter}) rather
 * than from a separate DB join, eliminating the cross-entity Cartesian product that
 * the previous {@code JOIN com.beautica.user.User actor ON actor.id = :actorId} produced.
 */
public record BookingViewAccess(
        UUID clientUserId,
        UUID masterUserId,
        UUID salonOwnerUserId
) {}
