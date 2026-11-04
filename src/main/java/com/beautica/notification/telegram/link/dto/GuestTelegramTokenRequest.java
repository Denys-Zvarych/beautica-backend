package com.beautica.notification.telegram.link.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Phase 407 D1 — body of every guest Telegram link call: the booking's cancel token, the only credential (kept out of
 * the URL so it never reaches access logs). A cancel token is 16 base62 chars (phase 435 follow-up) or a legacy canonical
 * UUID ({@code GuestCancelToken}); the shape check accepts exactly those two (malformed → 400 before any lookup).
 */
public record GuestTelegramTokenRequest(
        @NotNull
        @Size(min = 16, max = 36)
        @Pattern(regexp = "^(?:[0-9A-Za-z]{16}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$")
        String cancelToken) {

    /** Redacted — the cancel token is a capability. */
    @Override
    public String toString() {
        return "GuestTelegramTokenRequest[cancelToken=<redacted>]";
    }
}
