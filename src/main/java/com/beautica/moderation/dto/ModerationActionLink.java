package com.beautica.moderation.dto;

import java.util.Objects;

/**
 * Phase 414 — one button of the support report e-mail. {@code url} embeds the RAW single-use token: it exists only in
 * the e-mail body and must never be logged or stored ({@link #toString()} omits it).
 *
 * @param action what the link does once confirmed
 * @param url    {@code PUBLIC_BASE_URL + /api/v1/moderation/actions/{token}}
 */
public record ModerationActionLink(ModerationAction action, String url) {

    public ModerationActionLink {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(url, "url");
    }

    @Override
    public String toString() {
        return "ModerationActionLink[action=" + action + ", url=<redacted>]";
    }
}
