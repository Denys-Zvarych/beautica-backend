package com.beautica.notification.telegram.link.dto;

import com.beautica.notification.telegram.link.IssuedLink;

import java.time.Instant;

/**
 * Phase 407 D3 — {@code POST /api/v1/book/telegram/link-token}: a fresh {@code https://t.me/<bot>?start=<token>} deep
 * link built by the backend (phase 432 D5: clients hold no bot username), or {@code alreadyLinked} with both fields
 * {@code null} when the phone already has an ACTIVE link.
 */
public record GuestTelegramLinkResponse(String url, Instant expiresAt, boolean alreadyLinked) {

    public static GuestTelegramLinkResponse issued(IssuedLink link) {
        return new GuestTelegramLinkResponse(link.url(), link.expiresAt(), false);
    }

    public static GuestTelegramLinkResponse linked() {
        return new GuestTelegramLinkResponse(null, null, true);
    }

    /** Redacted — the url carries the raw link token. */
    @Override
    public String toString() {
        return "GuestTelegramLinkResponse[url=" + (url == null ? "null" : "<redacted>") + ", expiresAt=" + expiresAt
                + ", alreadyLinked=" + alreadyLinked + "]";
    }
}
