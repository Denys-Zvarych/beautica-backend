package com.beautica.moderation.event;

import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportTargetType;

import java.util.UUID;

/**
 * Published by {@code ContentReportService} when a NEW report row was inserted (never on an idempotent repeat).
 * Ids and enums only — the reporter's comment and the reported text are deliberately absent (D7).
 */
public record ContentReportCreatedEvent(
        UUID reportId,
        ReportTargetType targetType,
        UUID targetId,
        ReportReason reason,
        UUID reporterUserId) {
}
