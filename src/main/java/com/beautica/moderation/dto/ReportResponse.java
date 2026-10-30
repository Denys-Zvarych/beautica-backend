package com.beautica.moderation.dto;

import com.beautica.moderation.entity.ContentReport;
import com.beautica.moderation.entity.ReportStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /api/v1/reports} response (phase 395) — the reporter's own report, no target or reporter data
 * echoed back.
 */
public record ReportResponse(UUID id, ReportStatus status, Instant createdAt) {

    public static ReportResponse from(ContentReport report) {
        return new ReportResponse(report.getId(), report.getStatus(), report.getCreatedAt());
    }
}
