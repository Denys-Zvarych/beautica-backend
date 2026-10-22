package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A Turnstile verification that Cloudflare (or a local pre-check) rejected (phase 382 D3). One fixed
 * client message for every rejection — a missing token, a failed challenge, a replayed token and a
 * hostname/action mismatch are indistinguishable from outside, so the endpoint is no oracle.
 * {@code GlobalExceptionHandler} renders it as 400 {@value #CLIENT_MESSAGE}.
 */
public class TurnstileRejectedException extends BusinessException {

    public static final String CLIENT_MESSAGE = "Verification failed";

    public TurnstileRejectedException() {
        super(HttpStatus.BAD_REQUEST, CLIENT_MESSAGE);
    }
}
