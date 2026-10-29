package com.beautica.moderation.dto;

import com.beautica.moderation.entity.ReportTargetType;

import java.util.UUID;

/**
 * One target the caller has reported, OPEN or RESOLVED ({@code GET /api/v1/reports/mine}); the app hides the report
 * action for it permanently (one report per user and target). Ids only — no reason, comment or report id.
 */
public record ReportedTargetResponse(ReportTargetType targetType, UUID targetId) {
}
