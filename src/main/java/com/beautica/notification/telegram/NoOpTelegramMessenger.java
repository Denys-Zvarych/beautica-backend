package com.beautica.notification.telegram;

/**
 * The {@link TelegramMessenger} while {@code app.telegram.enabled=false} — the default everywhere (Phase 404, D6).
 *
 * <p>Unlike {@code NoOpSmsService} this one <b>throws</b> {@link TelegramDeliveryException}: a disabled Telegram
 * channel must never look like a delivered message, so even a mis-wired router falls back to SMS. Never touches
 * the network and never logs (nothing about the recipient or text is worth recording).
 */
public class NoOpTelegramMessenger implements TelegramMessenger {

    static final String DISABLED_MESSAGE = "Telegram channel is disabled (app.telegram.enabled=false)";

    @Override
    public void send(long chatId, String text) {
        throw new TelegramDeliveryException(DISABLED_MESSAGE);
    }
}
