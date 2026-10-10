package com.beautica.notification.inapp.dto;

/** {@code PATCH /api/v1/notifications/read-all} — how many previously-unread rows were marked read. */
public record MarkAllReadResponse(int updated) {
}
