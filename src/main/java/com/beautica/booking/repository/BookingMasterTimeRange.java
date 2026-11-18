package com.beautica.booking.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * One CONFIRMED booking's master and {@code [startsAt, endsAt)} range — the row of
 * {@link BookingRepository#findActiveTimeRangesByMasterIdsInRange}. Carries {@code masterId} so the
 * batched result set can be regrouped per master in memory. A native interface projection (B523 perf
 * fix: the finder joins {@code unnest(:masterIds)}); instants, since the gate compares instants only.
 */
public interface BookingMasterTimeRange {

    UUID getMasterId();

    Instant getStartsAt();

    Instant getEndsAt();
}
