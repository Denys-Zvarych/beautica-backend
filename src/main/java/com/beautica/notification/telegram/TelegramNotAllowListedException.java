package com.beautica.notification.telegram;

/**
 * Phase 408 audit-fix — the staging tester allow-list refused the chat (432 D3). A {@link TelegramDeliveryException}
 * (callers fall back to SMS exactly as before), typed apart so the router counts it as {@code not_allow_listed}
 * instead of inflating {@code transient_fallback}, the alerting signal.
 */
public class TelegramNotAllowListedException extends TelegramDeliveryException {

    public TelegramNotAllowListedException(String message) {
        super(message);
    }
}
