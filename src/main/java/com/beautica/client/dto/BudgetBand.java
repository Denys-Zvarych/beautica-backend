package com.beautica.client.dto;

import java.math.BigDecimal;

/**
 * Spend band derived from the signed-in client's COMPLETED bookings (Phase 19.5).
 *
 * <p>All amounts are the {@code priceAtBooking} frozen on each booking. Single
 * currency UAH — the platform is Ukraine-only, so no multi-currency / FX handling
 * exists (locked decision 6). {@code avg}/{@code min}/{@code max} are {@code null}
 * only inside the empty-state path, where the whole {@link PassportResponse#budget()}
 * is {@code null} rather than a band of nulls.
 *
 * @param avg      mean {@code priceAtBooking} across COMPLETED bookings
 * @param min      cheapest COMPLETED {@code priceAtBooking}
 * @param max      dearest COMPLETED {@code priceAtBooking}
 * @param currency always {@code "UAH"}
 */
public record BudgetBand(
        BigDecimal avg,
        BigDecimal min,
        BigDecimal max,
        String currency
) {
}
