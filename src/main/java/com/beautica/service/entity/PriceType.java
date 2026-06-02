package com.beautica.service.entity;

/**
 * Pricing mode for a {@link ServiceDefinition}.
 *
 * <ul>
 *   <li>{@link #FIXED} — a single static amount stored in {@code base_price}; {@code price_max} is {@code NULL}.</li>
 *   <li>{@link #RANGE} — a min..max band; {@code base_price} holds the minimum and {@code price_max} holds
 *       the ceiling. The DB CHECK constraint enforces {@code price_max >= base_price}.</li>
 * </ul>
 *
 * <p>{@code base_price} always represents the canonical floor (minimum price) for both modes,
 * which keeps {@code masters.min_effective_price} (V58) and {@code bookings.price_at_booking} working unchanged.
 */
public enum PriceType {
    FIXED,
    RANGE
}
