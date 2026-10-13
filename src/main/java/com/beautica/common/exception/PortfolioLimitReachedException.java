package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a portfolio upload would exceed the per-entity cap (Phase 362 D5,
 * {@code MediaService#MAX_PORTFOLIO_PHOTOS}).
 *
 * <p>Surfaces as {@code 409 Conflict} carrying the stable error code {@link #ERROR_CODE} under
 * {@code data.code} (audit cycle 1, F6b), so the mobile client can tell "limit reached" apart from
 * every other 409 — {@link GlobalExceptionHandler#handleBusiness} genericises the {@code message} of
 * all CONFLICTs, so a code in the message alone would never reach the wire. Same shape as
 * {@link BookingElapsedException}; stack-trace capture is suppressed (flow control, never logged).
 */
public class PortfolioLimitReachedException extends BusinessException {

    /** Stable error code echoed under {@code data.code}; the client maps it to localised copy. */
    public static final String ERROR_CODE = "PORTFOLIO_LIMIT_REACHED";

    public PortfolioLimitReachedException() {
        super(HttpStatus.CONFLICT, "Portfolio photo limit reached");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
