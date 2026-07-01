package com.beautica.client.repository;

import java.math.BigDecimal;

/**
 * Single-row aggregate of the client's COMPLETED-booking spend (Phase 19.5).
 *
 * <p>Computed entirely in SQL ({@code AVG/MIN/MAX/COUNT} over {@code price_at_booking});
 * the service never pulls the underlying rows. When the client has no COMPLETED
 * bookings {@code total} is {@code 0} and the amounts are {@code null} (SQL aggregates
 * over an empty set) — the service treats that as the empty-state.
 *
 * @param avg   mean {@code priceAtBooking}, or {@code null} when {@code total == 0}
 * @param min   minimum {@code priceAtBooking}, or {@code null} when {@code total == 0}
 * @param max   maximum {@code priceAtBooking}, or {@code null} when {@code total == 0}
 * @param total count of COMPLETED bookings considered
 */
public record BudgetAggregate(
        BigDecimal avg,
        BigDecimal min,
        BigDecimal max,
        long total
) {
}
