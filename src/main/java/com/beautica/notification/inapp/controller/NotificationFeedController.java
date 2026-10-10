package com.beautica.notification.inapp.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.notification.inapp.dto.MarkAllReadRequest;
import com.beautica.notification.inapp.dto.MarkAllReadResponse;
import com.beautica.notification.inapp.dto.NotificationResponse;
import com.beautica.notification.inapp.dto.UnreadCountResponse;
import com.beautica.notification.inapp.service.NotificationFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The bell-dot and feed-screen read API (phase 334) — any authenticated role. HTTP-only; every
 * business rule (visibility, params resolution, idempotency) lives in
 * {@code NotificationFeedService}/{@code NotificationViewAssembler}. Rate-limited (60/min per
 * user, shared by all four endpoints below) by {@code BookingRateLimitFilter}'s
 * {@code notificationFeedBuckets} — see {@code RateLimitConfig#notificationFeedCapacity}.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Validated
@Tag(name = "Notifications", description = "In-app notification feed — bell dot, list, mark read")
public class NotificationFeedController {

    private final NotificationFeedService notificationFeedService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The recipient's own notification feed, newest first")
    public PageResponse<NotificationResponse> listFeed(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            Authentication authentication) {
        UUID recipientId = AuthenticationUtils.userId(authentication);
        var role = AuthenticationUtils.role(authentication);
        return notificationFeedService.listFeed(recipientId, role, page, size);
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The bell red-dot count, capped at 99")
    public ApiResponse<UnreadCountResponse> unreadCount(Authentication authentication) {
        UUID recipientId = AuthenticationUtils.userId(authentication);
        return ApiResponse.ok(notificationFeedService.unreadCount(recipientId));
    }

    @PatchMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Marks one item read — idempotent; 404 for a missing or foreign id (never 403)")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, Authentication authentication) {
        UUID recipientId = AuthenticationUtils.userId(authentication);
        notificationFeedService.markRead(recipientId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Marks every still-unread item created at or before the given cutoff (default: now) read")
    public ApiResponse<MarkAllReadResponse> markAllRead(
            @RequestBody(required = false) MarkAllReadRequest request, Authentication authentication) {
        UUID recipientId = AuthenticationUtils.userId(authentication);
        var upTo = request != null ? request.upTo() : null;
        return ApiResponse.ok(notificationFeedService.markAllRead(recipientId, upTo));
    }
}
