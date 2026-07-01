package com.beautica.booking.enums;

/**
 * Origin of a booking (Phase 13.3).
 *
 * <p>Values must match the DB CHECK constraint {@code chk_bookings_source} defined
 * in {@code V89__add_guest_booking_columns.sql}. Any divergence causes an
 * {@link IllegalArgumentException} during Hibernate hydration.
 */
public enum BookingSource {

    /** Placed by a registered CLIENT through the authenticated app booking flow. */
    APP,

    /**
     * Placed by a phone-verified, account-less guest via the public booking link
     * ({@code beautica.app/book/{slug}}); auto-confirmed on creation.
     */
    LINK
}
