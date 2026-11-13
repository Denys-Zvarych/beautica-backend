package com.beautica.notification.service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 491 — one parsed {@code REQUEST_DECIDED} outbox payload: ids and the flag, nothing else (D2).
 *
 * @param kind            which request type was decided
 * @param requestId       the decided row's id as text (BIGINT for a category, UUID for a suggestion)
 * @param approved        {@code true} for approve, {@code false} for reject
 * @param recipientUserId the requester the row was enqueued for
 */
public record RequestDecisionEvent(RequestKind kind, String requestId, boolean approved, UUID recipientUserId) {

    public RequestDecisionEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(recipientUserId, "recipientUserId");
    }

    /**
     * Parses the payload written by {@link NotificationOutboxService#enqueueRequestDecided}.
     *
     * @throws IllegalArgumentException when a key is missing or unreadable — the row then fails on its
     *                                  own retry path; the message names the key only, never a value
     */
    public static RequestDecisionEvent parse(Map<String, String> payload) {
        String kind = required(payload, NotificationOutboxService.REQUEST_KIND);
        String requestId = required(payload, NotificationOutboxService.REQUEST_ID);
        String approved = required(payload, NotificationOutboxService.REQUEST_APPROVED);
        String recipient = required(payload, NotificationOutboxService.RECIPIENT_USER_ID);
        if (!"true".equals(approved) && !"false".equals(approved)) {
            throw new IllegalArgumentException("REQUEST_DECIDED payload has an unreadable approved flag");
        }
        try {
            return new RequestDecisionEvent(RequestKind.valueOf(kind), requestId,
                    Boolean.parseBoolean(approved), UUID.fromString(recipient));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("REQUEST_DECIDED payload has an unreadable kind or recipient");
        }
    }

    private static String required(Map<String, String> payload, String key) {
        String value = payload.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("REQUEST_DECIDED payload is missing " + key);
        }
        return value;
    }
}
