package com.beautica.notification.telegram;

import java.util.Collection;
import java.util.Set;

/**
 * Staging tester allow-list (Phase 432, D3), read from {@code app.telegram.staging-allowed-chat-ids}
 * ({@code TELEGRAM_STAGING_ALLOWED_CHAT_IDS}). The staging bot is public, so anyone can {@code /start} it; only the
 * listed private chats (chat id = user id, always positive) may link or be messaged.
 *
 * <p>An empty list allows nothing (fail closed). {@code TelegramEnvGuard} refuses to boot staging with the channel
 * enabled in live mode and an empty list, so an empty list here means the channel is off or in capture mode.
 * Entries are validated at bind time by {@code TelegramProperties}.
 */
public final class StagingTelegramChatAllowList implements TelegramChatAllowList {

    private final Set<Long> allowedChatIds;

    public StagingTelegramChatAllowList(Collection<Long> allowedChatIds) {
        this.allowedChatIds = Set.copyOf(allowedChatIds);
    }

    @Override
    public boolean allows(long chatId) {
        return allowedChatIds.contains(chatId);
    }
}
