package com.beautica.notification.telegram.link;

/** Phase 405 D7 — lifecycle of a {@code telegram_link_tokens} row ({@code chk_tg_token_state}). */
public enum TelegramTokenState {
    ISSUED,
    CLAIMED,
    /** Terminal: the claiming chat shared the matching phone. */
    LINKED,
    /** Terminal: the shared phone differed (or did not parse); a new attempt needs a new link. */
    MISMATCH
}
