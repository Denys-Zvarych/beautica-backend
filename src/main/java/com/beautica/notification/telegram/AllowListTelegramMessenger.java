package com.beautica.notification.telegram;

import lombok.extern.slf4j.Slf4j;

/**
 * Staging decorator over the live {@link TelegramMessenger} (Phase 432, D3): a chat that is not on the
 * {@link TelegramChatAllowList} gets no HTTP call and a {@link TelegramDeliveryException}, so the router (408)
 * falls back to SMS (captured in Mailpit on staging). Wired by {@code config/TelegramConfig} on staging only.
 *
 * <p>Logs never carry the chat id or the text (404 D5).
 */
@Slf4j
public final class AllowListTelegramMessenger implements TelegramMessenger {

    static final String NOT_ALLOW_LISTED = "Telegram chat is not on the staging allow-list";

    private final TelegramMessenger delegate;
    private final TelegramChatAllowList allowList;

    public AllowListTelegramMessenger(TelegramMessenger delegate, TelegramChatAllowList allowList) {
        this.delegate = delegate;
        this.allowList = allowList;
    }

    @Override
    public void send(long chatId, String text) {
        requireAllowed(chatId, text);
        delegate.send(chatId, text);
    }

    @Override
    public void sendWaiting(long chatId, String text) {
        requireAllowed(chatId, text);
        delegate.sendWaiting(chatId, text);
    }

    private void requireAllowed(long chatId, String text) {
        TelegramMessenger.requireSendable(text);
        if (!allowList.allows(chatId)) {
            log.info("event=telegram_send outcome=transient status=not_allow_listed");
            throw new TelegramNotAllowListedException(NOT_ALLOW_LISTED);
        }
    }
}
