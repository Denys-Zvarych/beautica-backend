package com.beautica.notification.telegram;

/**
 * Permanent Telegram failure (Phase 404, D2): the recipient can no longer be reached by the bot — 403 (blocked,
 * deactivated, …) or 400 "chat not found". The router (408) treats the link as dead and falls back to SMS.
 *
 * <p>The message carries the HTTP status and Telegram's {@code description} only — never the request URL (it
 * holds the bot token), the chat id or the message text (D5). No cause is chained for the same reason.
 */
public class TelegramRecipientGoneException extends RuntimeException {

    public TelegramRecipientGoneException(String message) {
        super(message);
    }
}
