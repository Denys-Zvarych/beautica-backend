package com.beautica.common.exception;

/**
 * Phase 398 D3 — a booking create refused because the provider blocked this client (phase 397). Surfaces as
 * {@code 409 Conflict} with the stable code {@link #BOOKING_UNAVAILABLE} under {@code data.code} and a neutral
 * Ukrainian message (mobile 425 shows it as-is; website 020 maps the code to its own copy).
 *
 * <p><b>Silent by design (D4).</b> Nothing in the body says "blocked": the app and the LINK guest paths emit the
 * identical body, differing only by the master vs salon wording, which follows the TARGET's scope (an independent
 * master's block vs a whole-salon block) — never the caller. Built only through the two factories, so the message
 * is always a constant and nothing request-derived is echoed. Stack-trace capture is suppressed — flow control,
 * never logged.
 */
public class BookingUnavailableException extends RuntimeException {

    /** Stable code echoed under {@code data.code}. */
    public static final String BOOKING_UNAVAILABLE = "BOOKING_UNAVAILABLE";

    /** Message for an independent master's scope. */
    public static final String MASTER_MESSAGE = "Запис до цього майстра зараз недоступний";

    /** Message for a salon's scope (any master of the salon, including the owner). */
    public static final String SALON_MESSAGE = "Запис до цього салону зараз недоступний";

    private BookingUnavailableException(String message) {
        super(message);
    }

    public static BookingUnavailableException forMaster() {
        return new BookingUnavailableException(MASTER_MESSAGE);
    }

    public static BookingUnavailableException forSalon() {
        return new BookingUnavailableException(SALON_MESSAGE);
    }

    public String getCode() {
        return BOOKING_UNAVAILABLE;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
