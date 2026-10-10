package com.beautica.notification.inapp.dto;

import com.beautica.notification.inapp.entity.InAppNotificationType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One in-app feed item, as served by {@code GET /api/v1/notifications} (phase 334). Never
 * exposes the underlying {@code InAppNotification} entity directly — {@code params} is resolved
 * fresh on every read by {@code NotificationViewAssembler}, never stored.
 *
 * @param target where a tap navigates; {@link TargetKind#NONE} when the referent is gone or no
 *               longer visible to this recipient
 * @param params display parameters, or null exactly when {@code target.kind() == NONE}
 */
public record NotificationResponse(
        UUID id,
        InAppNotificationType type,
        Instant createdAt,
        boolean read,
        NotificationTarget target,
        @Schema(types = {"object", "null"}, nullable = true) NotificationParams params
) {
}
