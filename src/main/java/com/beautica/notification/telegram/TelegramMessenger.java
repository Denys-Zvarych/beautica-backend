package com.beautica.notification.telegram;

/**
 * Provider-agnostic Telegram sending port (Phase 404, D1). Mirrors the {@code SmsService} port: the
 * notification router (phase 408) depends on this interface only, and knows exactly two failure outcomes.
 *
 * <p>Implementations are selected by {@code config/TelegramConfig}: {@link TelegramBotApiClient} (live),
 * {@link CapturingTelegramMessenger} (capture → Mailpit) or {@link NoOpTelegramMessenger} (flag off).
 */
public interface TelegramMessenger {

    /** Bot API hard limit for a {@code sendMessage} text. */
    int MAX_TEXT_LENGTH = 4096;

    /**
     * Sends one plain-text message (no {@code parse_mode}, link previews disabled).
     *
     * @param chatId Telegram chat id of the recipient — never logged
     * @param text   message body, at most {@value #MAX_TEXT_LENGTH} chars — never logged
     * @throws TelegramRecipientGoneException permanent: the recipient is unreachable (blocked the bot,
     *                                        deactivated, chat not found)
     * @throws TelegramDeliveryException      transient: anything else (rate limit, 5xx, timeout, channel off)
     * @throws IllegalArgumentException       when {@code text} is null, blank or longer than the limit
     */
    void send(long chatId, String text);

    /**
     * Shared argument check for every implementation that actually delivers (live and capture).
     *
     * @throws IllegalArgumentException when {@code text} is null, blank or over {@value #MAX_TEXT_LENGTH} chars (D3)
     */
    static void requireSendable(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Telegram text must not be blank");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Telegram text exceeds " + MAX_TEXT_LENGTH + " chars");
        }
    }
}
