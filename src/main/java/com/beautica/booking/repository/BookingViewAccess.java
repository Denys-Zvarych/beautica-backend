package com.beautica.booking.repository;

import java.util.UUID;

/**
 * Single-query projection used by {@code AuthorizationService.canViewBooking}.
 *
 * <p>Contains only the booking's ownership identifiers. The actor's role is resolved
 * from {@code SecurityContextHolder} (set by {@code JwtAuthenticationFilter}) rather
 * than from a separate DB join, eliminating the cross-entity Cartesian product that
 * the previous {@code JOIN com.beautica.user.User actor ON actor.id = :actorId} produced.
 *
 * <p><b>{@code masterIsActive} is read by exactly ONE branch and must stay that way</b> — the
 * {@code SALON_MASTER} leg of {@code canViewBooking}, the projection twin of the liveness conjunct
 * {@code AuthorizationService#enforceCanViewBooking} applies on the hydrated entity. It exists on
 * this shared projection (rather than in a third {@code *Access} record, the split
 * {@link BookingReviewAccess} made for the phase-316 WRITE grant) only because both consumers of
 * this record live in {@code AuthorizationService} and the other one, {@code canManageBooking},
 * rejects {@code ROLE_SALON_MASTER} before it reads a single field. <b>Do not consult it from the
 * salon-owner or independent-master arm of either method</b>: a salon owner keeps view, complete,
 * decline and reschedule over a booking whose master has since been deactivated — see
 * {@link BookingReviewAccess}'s javadoc for the full statement of that asymmetry.
 *
 * @param clientUserId      {@code bookings.client_id}, or {@code null} for a guest (LINK) booking
 * @param masterUserId      the booking master's {@code users} id, or {@code null} for a DETACHED
 *                          master (V157 / phase 294 D1)
 * @param masterIsActive    {@code masters.is_active} for the booking's master; {@code false} once
 *                          {@code MasterService.deactivateMasterInternal} has fired
 * @param salonOwnerUserId  the owner of the master's salon, or {@code null} for an independent
 *                          master's booking
 */
public record BookingViewAccess(
        UUID clientUserId,
        UUID masterUserId,
        boolean masterIsActive,
        UUID salonOwnerUserId
) {}
