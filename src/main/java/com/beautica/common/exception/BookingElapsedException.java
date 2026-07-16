package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@code BookingService} when an authenticated client attempts to
 * <em>reschedule</em> or <em>cancel</em> a booking whose appointment window has already
 * fully elapsed ({@code endsAt} is before "now").
 *
 * <p>Encodes the locked product rule (track 24.x): once the appointment time passes, the
 * booking becomes read-only for the client and awaits provider resolution — the provider
 * (decline / complete / mark-no-show) remains the only party able to move it out of
 * {@code CONFIRMED}. A client mutation on an elapsed booking is therefore a state conflict.
 *
 * <p>Surfaces as a {@code 409 Conflict} carrying the stable error code
 * {@code BOOKING_ALREADY_ELAPSED} in the response body under {@code data.code}, so the mobile
 * client can distinguish it from the generic "Slot not available" / master-busy 409s and route
 * to a dedicated "this booking's time has passed" message. Like the other flow-control
 * exceptions translated straight to an HTTP response (see {@link ClientBookingConflictException},
 * {@link EmailAlreadyRegisteredException}), stack-trace capture is suppressed.
 */
public class BookingElapsedException extends BusinessException {

    /**
     * Stable error code echoed in the response body under {@code data.code}. The mobile client
     * maps it to the localised Ukrainian copy; the API never ships natural-language messages for
     * client routing.
     */
    public static final String ERROR_CODE = "BOOKING_ALREADY_ELAPSED";

    public BookingElapsedException() {
        super(HttpStatus.CONFLICT,
                "This booking's time has already passed and can no longer be modified by the client");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
