package com.beautica.booking.retention;

/**
 * Counts from one anonymisation run or one erase call (phase 373). Ids are never carried — the
 * result is logged and returned to the internal caller.
 */
public record GuestPiiAnonymisationResult(
        int bookingsCancelled,
        int visitsCancelled,
        int bookingsAnonymised,
        int visitsAnonymised,
        int otpRowsDeleted
) {

    static GuestPiiAnonymisationResult ofRetention(int bookingsAnonymised, int visitsAnonymised) {
        return new GuestPiiAnonymisationResult(0, 0, bookingsAnonymised, visitsAnonymised, 0);
    }
}
