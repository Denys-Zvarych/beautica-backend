package com.beautica.moderation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/internal/moderation/reports/{id}/resolve} body (phase 396 D4). {@code note} is support's own
 * free text, stored in {@code content_reports.resolution_note} (V208, 500 chars) and never logged; same character
 * rules as a reporter's comment.
 */
public record ResolveReportRequest(
        @NotNull
        ModerationAction action,

        @Size(max = CreateReportRequest.MAX_COMMENT_LENGTH)
        @Pattern(regexp = CreateReportRequest.COMMENT_PATTERN,
                message = "must not contain control or bidirectional-override characters")
        String note
) {
}
