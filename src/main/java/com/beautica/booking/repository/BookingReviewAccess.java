package com.beautica.booking.repository;

import java.util.UUID;

/**
 * Single-query projection backing {@code AuthorizationService.canReviewClient} (Phase 316).
 *
 * <p><b>Deliberately NOT {@link BookingCompletionAccess} with a third component.</b> It carries the
 * same {@code (masterUserId, salonId)} pair, plus {@code masterIsActive} — and that extra leg is
 * exactly why the two projections must stay separate. {@code BookingCompletionAccess} feeds the
 * shared {@code hasProviderAuthorityOverRow} kernel that gates {@code /complete},
 * {@code /not-complete}, {@code /decline} and {@code /reschedule}; a salon OWNER or ADMIN must keep
 * every one of those over a booking whose master has since been deactivated, so that kernel must
 * never see an {@code is_active} conjunct. The Phase 316 review grant is the opposite: it is keyed
 * on the performing master's own account and has to LAPSE the moment that account is deactivated.
 * Two different data requirements, two projections — merging them is how the completion kernel
 * would silently acquire a liveness predicate it must not have.
 *
 * @param masterUserId   the user id of the booking's master, or {@code null} for a DETACHED master
 *                       (V157 / phase 294 D1 — the staff {@code users} row was hard-deleted)
 * @param masterIsActive {@code masters.is_active} for the booking's master; {@code false} once
 *                       {@code MasterService.deactivateMasterInternal} has fired
 * @param salonId        the id of the master's salon, or {@code null} for an independent master
 */
public record BookingReviewAccess(
        UUID masterUserId,
        boolean masterIsActive,
        UUID salonId
) {}
