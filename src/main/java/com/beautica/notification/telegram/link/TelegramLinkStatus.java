package com.beautica.notification.telegram.link;

/** Phase 405 D4 — persisted state of a {@code telegram_links} row ({@code chk_tg_link_status}). */
public enum TelegramLinkStatus {
    /** Non-urgent guest messages go to this chat. */
    ACTIVE,
    /** Bot blocked / chat gone: messages fall back to SMS; kept for unblock reactivation, purged by phase 409. */
    BLOCKED
}
