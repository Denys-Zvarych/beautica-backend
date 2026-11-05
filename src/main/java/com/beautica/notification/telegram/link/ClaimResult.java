package com.beautica.notification.telegram.link;

/** Phase 405 D7 — outcome of {@link TelegramLinkService#claim}. */
public enum ClaimResult {
    /** Token now held by the chat; the bot asks for the phone share. */
    CLAIMED,
    /** Token already linked this chat's phone and that link is still active here (webhook retry / re-tap). */
    ALREADY_LINKED,
    /** Unknown, expired, held by another chat, or terminal. */
    INVALID
}
