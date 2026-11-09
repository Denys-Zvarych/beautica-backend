package com.beautica.auth.phoneotp;

/**
 * Response body for {@code POST /api/v1/book/otp/verify} — carries the short-lived
 * guest JWT the client presents to create a single guest booking (Phase 13.3).
 */
public record GuestTokenResponse(String guestToken) {

    /**
     * Phase 442 audit — the guest JWT is a bearer credential and must never reach a log line: the default record
     * {@code toString} would print it.
     */
    @Override
    public String toString() {
        return "GuestTokenResponse[]";
    }
}
