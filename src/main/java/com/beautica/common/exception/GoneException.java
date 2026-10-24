package com.beautica.common.exception;

/**
 * Phase 379 D6 — a public resource that existed and was deliberately retired: today only a regenerated,
 * revoked or deleted booking link ({@code retired_booking_slugs}). Surfaces as {@code 410 Gone} carrying the
 * stable code {@link #BOOKING_LINK_RETIRED} under {@code data.code}, so the website can tell "this link is no
 * longer valid" apart from a typo (404) without parsing the message.
 *
 * <p>Built only through {@link #bookingLinkRetired()}: the client-facing message is a constant, so the
 * handler never echoes anything request-derived (the slug, in particular). Stack-trace capture is suppressed
 * — flow control, never logged.
 */
public class GoneException extends RuntimeException {

    /** Stable code echoed under {@code data.code}; the website maps it to «Посилання більше не дійсне». */
    public static final String BOOKING_LINK_RETIRED = "BOOKING_LINK_RETIRED";

    /** Client-facing message of {@link #BOOKING_LINK_RETIRED}; never contains the slug. */
    public static final String BOOKING_LINK_RETIRED_MESSAGE = "Booking link is no longer valid";

    private final String code;

    private GoneException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The retired-booking-link 410 (phase 379). */
    public static GoneException bookingLinkRetired() {
        return new GoneException(BOOKING_LINK_RETIRED, BOOKING_LINK_RETIRED_MESSAGE);
    }

    public String getCode() {
        return code;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
