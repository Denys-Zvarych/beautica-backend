package com.beautica.config;

/**
 * How Telegram messages are delivered (Phase 404, D6). Bound from {@code app.telegram.mode}; only consulted when
 * {@code app.telegram.enabled=true}.
 */
public enum TelegramMode {
    /** The real Bot API (default). Prod only — {@link TelegramEnvGuard} refuses it on staging. */
    LIVE,
    /** Mail each message to a Mailpit inbox; nothing reaches Telegram. Local dev / staging smoke. */
    CAPTURE
}
