package com.beautica.notification.telegram;

/**
 * Transient Telegram failure (Phase 404, D2): 429, 5xx, any other 4xx, a 3xx (redirects are never followed),
 * {@code ok:false}, timeout, I/O, an empty send limiter, or the channel being switched off. The router (408)
 * falls back to SMS immediately; nothing retries inside the client.
 *
 * <p>The message carries the HTTP status and Telegram's {@code description}, or the transport exception's simple
 * class name — never the request URL (it holds the bot token), the chat id or the text (D5). The transport
 * exception is deliberately NOT chained as a cause: Spring's {@code ResourceAccessException} message embeds the
 * full request URL, so a stack trace logged anywhere upstream would leak the token.
 */
public class TelegramDeliveryException extends RuntimeException {

    public TelegramDeliveryException(String message) {
        super(message);
    }
}
