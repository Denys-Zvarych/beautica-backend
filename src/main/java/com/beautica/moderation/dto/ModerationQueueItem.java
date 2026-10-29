package com.beautica.moderation.dto;

import com.beautica.moderation.entity.ContentReport;
import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportResolution;
import com.beautica.moderation.entity.ReportStatus;
import com.beautica.moderation.entity.ReportTargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the support moderation queue ({@code GET /api/v1/internal/moderation/reports}, phase 396 D4). Internal
 * endpoint behind {@code X-Internal-Key} only — it carries the reporter's comment and id, which no public response
 * ever does.
 */
public record ModerationQueueItem(
        UUID id,
        ReportTargetType targetType,
        UUID targetId,
        ReportReason reason,
        String comment,
        UUID reporterUserId,
        ReportStatus status,
        ReportResolution resolution,
        boolean burstHold,
        Instant createdAt,
        Instant resolvedAt,
        Instant targetGoneAt,
        ModerationTargetSnapshot target
) {

    public static ModerationQueueItem of(ContentReport report, ModerationTargetSnapshot target) {
        return new ModerationQueueItem(
                report.getId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReason(),
                report.getComment(),
                report.getReporterUserId(),
                report.getStatus(),
                report.getResolution(),
                report.isBurstHold(),
                report.getCreatedAt(),
                report.getResolvedAt(),
                report.getTargetGoneAt(),
                target);
    }
}
