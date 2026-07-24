package com.beautica.booking.dto;

import com.beautica.common.exception.BookingElapsedException;

/**
 * Response body for the {@code 409 BOOKING_ALREADY_ELAPSED} returned when an authenticated
 * client tries to reschedule or cancel a booking whose appointment window has already elapsed.
 *
 * <p>The {@code code} field carries the stable error code
 * {@link BookingElapsedException#ERROR_CODE}; the client maps it to a localised user-facing
 * message. Nothing else is echoed — the caller already holds the booking id it acted on, and a
 * bare code keeps the payload to the minimum the routing logic needs.
 */
public record BookingElapsedResponse(String code) {}
