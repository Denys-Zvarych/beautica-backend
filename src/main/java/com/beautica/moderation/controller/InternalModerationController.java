package com.beautica.moderation.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.moderation.dto.ModerationQueueItem;
import com.beautica.moderation.dto.ResolveReportRequest;
import com.beautica.moderation.dto.ResolveReportResponse;
import com.beautica.moderation.entity.ReportStatus;
import com.beautica.moderation.service.ModerationQueueService;
import com.beautica.moderation.service.ModerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 396 D4 — support moderation API (curl runbook: {@code docs/release/moderation-runbook.md}). Guarded by
 * {@code InternalApiKeyFilter} ({@code X-Internal-Key} on {@code /api/v1/internal/**}); no role, no UI, excluded from
 * the public OpenAPI spec ({@code springdoc.paths-to-exclude}).
 */
@RestController
@RequestMapping("/api/v1/internal/moderation")
@RequiredArgsConstructor
public class InternalModerationController {

    private final ModerationQueueService moderationQueueService;
    private final ModerationService moderationService;

    /** The queue: OPEN oldest first (default), or RESOLVED newest first; each row with a target snapshot. */
    @GetMapping("/reports")
    public ApiResponse<PageResponse<ModerationQueueItem>> listReports(
            @RequestParam(defaultValue = "OPEN") ReportStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ModerationQueueItem> page = moderationQueueService.list(status, pageable);
        return ApiResponse.ok(PageResponse.of(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }

    /** Acts on the report's target and resolves every OPEN report on it. */
    @PostMapping("/reports/{reportId}/resolve")
    public ApiResponse<ResolveReportResponse> resolve(
            @PathVariable UUID reportId,
            @Valid @RequestBody ResolveReportRequest request) {
        return ApiResponse.ok(moderationService.resolve(reportId, request));
    }
}
