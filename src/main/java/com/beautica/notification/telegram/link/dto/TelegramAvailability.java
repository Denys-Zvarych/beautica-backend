package com.beautica.notification.telegram.link.dto;

/**
 * Phase 407 D6 — the {@code telegram} block of a guest booking response: {@code available} = the channel is on;
 * {@code linked} = the booker's OTP-verified phone has an ACTIVE link. Never carries a phone, chat id or token.
 */
public record TelegramAvailability(boolean available, boolean linked) {

    public static final TelegramAvailability UNAVAILABLE = new TelegramAvailability(false, false);
}
