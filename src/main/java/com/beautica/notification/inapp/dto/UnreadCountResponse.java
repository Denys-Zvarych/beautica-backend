package com.beautica.notification.inapp.dto;

/**
 * {@code GET /api/v1/notifications/unread-count} — the bell red-dot count, capped at 99
 * server-side (see {@code NotificationFeedService#unreadCount}).
 */
public record UnreadCountResponse(int count) {
}
