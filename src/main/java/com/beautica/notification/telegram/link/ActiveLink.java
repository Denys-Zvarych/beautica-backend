package com.beautica.notification.telegram.link;

import java.util.Objects;
import java.util.UUID;

/**
 * Phase 405 — an {@code ACTIVE} link, as phase 408's router needs it: send to {@code chatId}, then
 * {@code touchLastSent(linkId)} or {@code markBlocked(linkId)}.
 */
public record ActiveLink(UUID linkId, long chatId) {

    public ActiveLink {
        Objects.requireNonNull(linkId, "linkId");
    }

    /** Redacted — a chat id is personal data (D3) and must not reach a log line via string concatenation. */
    @Override
    public String toString() {
        return "ActiveLink[linkId=" + linkId + ", chatId=<redacted>]";
    }
}
