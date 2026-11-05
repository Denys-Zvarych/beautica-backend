package com.beautica.notification.telegram.link;

import java.time.Instant;
import java.util.Objects;

/**
 * Phase 405 — a freshly issued deep link {@code https://t.me/<botUsername>?start=<token>} and its expiry.
 * The URL carries the raw token (a capability), so it is handed to the guest only and never logged.
 */
public record IssuedLink(String url, Instant expiresAt) {

    public IssuedLink {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** Redacted — the generated record {@code toString} would print the raw token. */
    @Override
    public String toString() {
        return "IssuedLink[url=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
