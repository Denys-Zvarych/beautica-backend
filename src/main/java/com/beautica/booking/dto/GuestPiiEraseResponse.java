package com.beautica.booking.dto;

import com.beautica.booking.retention.GuestPiiAnonymisationResult;

/**
 * Counts returned by the internal guest-PII erase (phase 373 D9). Zero matches → all zeros, so the
 * response reveals nothing about a phone beyond what the key holder already knows.
 */
public record GuestPiiEraseResponse(
        int bookingsCancelled,
        int visitsCancelled,
        int bookingsAnonymised,
        int visitsAnonymised,
        int otpRowsDeleted
) {

    public static GuestPiiEraseResponse from(GuestPiiAnonymisationResult result) {
        return new GuestPiiEraseResponse(
                result.bookingsCancelled(),
                result.visitsCancelled(),
                result.bookingsAnonymised(),
                result.visitsAnonymised(),
                result.otpRowsDeleted());
    }
}
