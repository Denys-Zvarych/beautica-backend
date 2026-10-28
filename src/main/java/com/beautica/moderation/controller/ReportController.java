package com.beautica.moderation.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.moderation.dto.CreateReportRequest;
import com.beautica.moderation.dto.ReportResponse;
import com.beautica.moderation.service.ContentReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 395 — in-app UGC reporting (Google Play UGC policy). Every role may report (D8); the business rules
 * (visibility, self-report, idempotency, rate limit) live in {@link ContentReportService}.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ContentReportService contentReportService;

    @Operation(
            summary = "Report a review, photo or profile",
            description = """
                    Reports a piece of user-generated content the caller can currently see. One report per \
                    (caller, target): repeating it returns the existing report with 200 instead of 201. \
                    Own content cannot be reported (400). A target the caller cannot see answers 404. \
                    At most 20 new reports per 24 hours per user (429).""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Report created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Report already existed (idempotent repeat)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation error, OTHER without comment, or own content"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Target not visible"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENT','SALON_OWNER','SALON_ADMIN','SALON_MASTER','INDEPENDENT_MASTER')")
    public ResponseEntity<ApiResponse<ReportResponse>> createReport(
            @Valid @RequestBody CreateReportRequest request,
            Authentication authentication) {
        ContentReportService.CreateReportResult result = contentReportService.createReport(
                AuthenticationUtils.userId(authentication), AuthenticationUtils.role(authentication), request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result.report()));
    }
}
