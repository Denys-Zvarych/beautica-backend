package com.beautica.booking.enums;

/**
 * Time-based partition for {@code GET /bookings/me?partition=} (Phase 28.1). Unlike {@code
 * status}, which filters by {@link BookingStatus} alone, {@code partition} also considers WHETHER
 * the booking's window has elapsed — because no scheduled job ever transitions an elapsed {@link
 * BookingStatus#CONFIRMED} booking to {@code COMPLETED}/{@code NOT_COMPLETED}; that only happens
 * via an explicit provider action ({@code PATCH /bookings/{id}/complete} or {@code
 * /not-complete}). Left unclosed, such a booking would otherwise sit at the top of a client's
 * "upcoming" list forever and never appear in "past" — this partition closes that gap.
 *
 * <pre>
 * UPCOMING    CONFIRMED                          AND ends_at &gt;= now
 * PAST        COMPLETED OR NOT_COMPLETED         (regardless of ends_at)
 *             OR (CONFIRMED                      AND ends_at &lt;  now)
 * CANCELLED   CANCELLED OR DECLINED              (regardless of ends_at)
 * </pre>
 *
 * <p><b>Total, disjoint cover.</b> Every {@link BookingStatus} value is assigned to EXACTLY one
 * partition branch above — {@code CONFIRMED} splits across {@code UPCOMING}/{@code PAST} on the
 * {@code ends_at} boundary, the other four statuses each belong to exactly one partition
 * unconditionally. {@code count(UPCOMING) + count(PAST) + count(CANCELLED) == count(unfiltered)}
 * for any booking set — see {@code BookingMyBookingsPartitionIT}'s total-cover test.
 *
 * <p>See {@link com.beautica.booking.repository.BookingSpecifications#partition} for the DB
 * predicate this enum drives, including the clock/timezone invariants that predicate MUST honour.
 */
public enum BookingPartition {
    UPCOMING,
    PAST,
    CANCELLED
}
