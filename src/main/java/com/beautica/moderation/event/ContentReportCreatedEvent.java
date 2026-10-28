package com.beautica.moderation.event;

import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportTargetType;

import java.util.UUID;

/**
 * Published by {@code ContentReportService} when a NEW report row was inserted (never on an idempotent repeat).
 * Ids and enums only — the reporter's comment and the reported text are deliberately absent (D7).
 * {@code burst} (phase 396) is {@code true} when this report tripped or fell inside a provider burst: the support
 * e-mail is flagged so support looks at the wave as a whole.
 */
public record ContentReportCreatedEvent(
        UUID reportId,
        ReportTargetType targetType,
        UUID targetId,
        ReportReason reason,
        UUID reporterUserId,
        boolean burst) {
}
