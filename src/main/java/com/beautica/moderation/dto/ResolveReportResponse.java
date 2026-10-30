package com.beautica.moderation.dto;

import com.beautica.moderation.entity.ReportResolution;
import com.beautica.moderation.entity.ReportTargetType;

import java.util.UUID;

/**
 * Outcome of a resolve (phase 396 D4). Internal endpoint only.
 *
 * @param targetType      the reported target's type
 * @param targetId        the reported target's id
 * @param resolution      what the OPEN reports were closed as ({@code TARGET_GONE} when the target had vanished)
 * @param resolvedReports how many OPEN reports on the target this call closed
 */
public record ResolveReportResponse(
        ReportTargetType targetType,
        UUID targetId,
        ReportResolution resolution,
        int resolvedReports
) {
}
