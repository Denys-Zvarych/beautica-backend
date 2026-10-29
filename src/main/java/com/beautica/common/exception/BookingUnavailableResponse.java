package com.beautica.common.exception;

/**
 * {@code data} of the phase-398 {@code 409 BOOKING_UNAVAILABLE} envelope: only the stable code — no block id,
 * no scope id, nothing that reveals a block.
 */
public record BookingUnavailableResponse(String code) {
}
