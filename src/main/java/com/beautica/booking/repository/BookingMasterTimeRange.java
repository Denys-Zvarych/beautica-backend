package com.beautica.booking.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 315 (D4) — the multi-master sibling of {@link BookingTimeRange}, carrying {@code master.id}
 * so a batched result set spanning several masters can be regrouped in memory.
 *
 * <p>{@link BookingTimeRange} deliberately stays a two-column projection: it backs
 * {@link BookingRepository#findActiveTimeRangesByMasterInRange}, which is still called per-master by
 * every single-master consumer ({@code SlotCalculationService#getAvailableSlots},
 * {@code #getBookableWorkingDays}, {@code #hasBookableFutureSlot}) and needs no master id since the
 * caller already knows which master it asked for. A batched load over several masters
 * ({@link BookingRepository#findActiveTimeRangesByMasterIdsInRange}) has no such caller-side
 * anchor — the rows for every master interleave in one result set — so this sibling widens the
 * projection by exactly the one column the regroup needs, rather than widening
 * {@code BookingTimeRange} itself and disturbing its other consumers.
 */
public record BookingMasterTimeRange(UUID masterId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
}
