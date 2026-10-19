package com.beautica.notification.mail;

import java.util.Arrays;
import java.util.Locale;

/**
 * Whitelist of Brevo API error {@code code} values (developers.brevo.com "Error codes", checked 2026-10-19). Only
 * these tokens may appear in an exception, a log line or {@code last_error}; anything else becomes {@link #OTHER}.
 * Brevo's free-text {@code message} is never read past the parser.
 */
enum BrevoErrorCode {
    INVALID_PARAMETER,
    MISSING_PARAMETER,
    OUT_OF_RANGE,
    UNAUTHORIZED,
    DOCUMENT_NOT_FOUND,
    METHOD_NOT_ALLOWED,
    NOT_ENOUGH_CREDITS,
    DUPLICATE_PARAMETER,
    DUPLICATE_REQUEST,
    ACCOUNT_UNDER_VALIDATION,
    PERMISSION_DENIED,
    NONE,
    OTHER;

    static BrevoErrorCode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String normalised = raw.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(c -> c != NONE && c != OTHER && c.name().equals(normalised))
                .findFirst()
                .orElse(OTHER);
    }

    /** The wire token ({@code invalid_parameter}), {@code none} or {@code other}. */
    String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Codes that mean the account/key is unusable — a system outage, not a bad message (D5). */
    boolean systemSide() {
        return this == UNAUTHORIZED || this == PERMISSION_DENIED || this == NOT_ENOUGH_CREDITS
                || this == ACCOUNT_UNDER_VALIDATION;
    }
}
