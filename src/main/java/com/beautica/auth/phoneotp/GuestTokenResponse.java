package com.beautica.auth.phoneotp;

/**
 * Response body for {@code POST /api/v1/book/otp/verify} — carries the short-lived
 * guest JWT the client presents to create a single guest booking (Phase 13.3).
 */
public record GuestTokenResponse(String guestToken) {}
