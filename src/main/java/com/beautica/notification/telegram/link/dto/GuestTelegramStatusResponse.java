package com.beautica.notification.telegram.link.dto;

/**
 * Phase 407 D3/D4 — {@code POST /api/v1/book/telegram/status}. {@code available} is always {@code true} on a 200 (an
 * unavailable channel answers 404); {@code linked} = the booking phone has an ACTIVE link; {@code offerChoice} = show
 * the SMS / Telegram choice on the cancel page — walk-ins (STAFF) only, and only while not linked.
 */
public record GuestTelegramStatusResponse(boolean available, boolean linked, boolean offerChoice) {
}
