package com.beautica.notification.telegram;

/**
 * Which Telegram chats this deployment may talk to (Phase 432, D3). Staging runs its own public test bot, so only
 * tester chats may link to it or receive messages from it; everywhere else every chat is allowed.
 *
 * <p>Consumers: {@link AllowListTelegramMessenger} (outbound, staging only) and the webhook (phase 406, inbound:
 * an update from a chat that is not allow-listed is answered 200 with no reply, no claim and no DB write).
 *
 * <p>Beans are declared in {@code config/TelegramConfig}: {@link StagingTelegramChatAllowList} on the
 * {@code staging} profile, {@link AllowAllTelegramChatAllowList} on every other profile.
 */
public interface TelegramChatAllowList {

    /**
     * @param chatId Telegram chat id — never logged
     * @return {@code true} when this deployment may read updates from, and send messages to, the chat
     */
    boolean allows(long chatId);
}
