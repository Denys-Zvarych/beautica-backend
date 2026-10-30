package com.beautica.notification.telegram;

/**
 * Every chat is allowed (Phase 432, D3) — the {@link TelegramChatAllowList} for every profile except
 * {@code staging}. Prod ignores {@code app.telegram.staging-allowed-chat-ids} entirely.
 */
public final class AllowAllTelegramChatAllowList implements TelegramChatAllowList {

    @Override
    public boolean allows(long chatId) {
        return true;
    }
}
