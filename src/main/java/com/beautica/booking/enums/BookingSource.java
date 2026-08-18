package com.beautica.booking.enums;

/**
 * Origin of a booking (Phase 13.3).
 *
 * <p>Values must match the DB CHECK constraint {@code chk_bookings_source}, defined
 * in {@code V89__add_guest_booking_columns.sql} and widened to admit {@code STAFF} by
 * {@code V137__bookings_staff_source.sql}. Any divergence causes an
 * {@link IllegalArgumentException} during Hibernate hydration.
 *
 * <p>This enum answers <em>what kind of booking</em> this is. The orthogonal question of
 * <em>which human keyed it in</em> is answered by {@code Booking.createdByUserId} (V137) —
 * deliberately not by a fourth enum value.
 */
public enum BookingSource {

    /** Placed by a registered CLIENT through the authenticated app booking flow. */
    APP,

    /**
     * Placed by a phone-verified, account-less guest via the public booking link
     * ({@code beautica.app/book/{slug}}); auto-confirmed on creation.
     */
    LINK,

    /**
     * Created by a {@code SALON_OWNER}/{@code SALON_ADMIN} on behalf of one of their salon's
     * masters (walk-in / phone booking); auto-confirmed on creation. The client is either a
     * linked platform {@code CLIENT} or an account-less walk-in whose first name, last name
     * and phone are all required.
     *
     * <p>Carries no {@code cancelToken} — a staff booking has no self-service guest cancel
     * link; only the owner/admin cancels it via a management endpoint.
     *
     * <p>Scope note (2026-08-18): the service layer ships the walk-in mode only. The
     * linked-{@code CLIENT} mode is already permitted by {@code chk_bookings_guest_fields}
     * (V137) so a later phase can enable it with no migration.
     */
    STAFF
}
