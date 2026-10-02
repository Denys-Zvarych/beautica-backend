package com.beautica.notification.inapp.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * {@code PATCH /api/v1/notifications/read-all} request body. {@code upTo} is optional and
 * defaults to "now" — it is the client's page-load cutoff, so an item that arrives AFTER the
 * client opened the feed is not swept up as read before the client ever saw it. Null is a
 * legitimate value (not a missing-required-field error), so no {@code @NotNull} here.
 */
public record MarkAllReadRequest(
        @Schema(types = {"string", "null"}, format = "date-time", nullable = true,
                description = "Mark every still-unread item created at or before this instant. "
                        + "Defaults to the server's current time when omitted or null.")
        Instant upTo
) {
}
