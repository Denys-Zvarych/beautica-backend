package com.beautica.notification.telegram.link.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Phase 407 D1 — body of every guest Telegram link call: the booking's cancel token, the only credential (kept out of
 * the URL so it never reaches access logs). A cancel token is a UUID; the shape check mirrors the {@code UUID} path
 * variable of {@code /api/v1/book/cancel/{token}} (malformed → 400 before any lookup).
 */
public record GuestTelegramTokenRequest(
        @NotNull
        @Size(min = 36, max = 36)
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String cancelToken) {

    /** Redacted — the cancel token is a capability. */
    @Override
    public String toString() {
        return "GuestTelegramTokenRequest[cancelToken=<redacted>]";
    }
}
