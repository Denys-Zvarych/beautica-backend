package com.beautica.media.controller;

import com.beautica.auth.Role;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.media.dto.AvatarResponse;
import com.beautica.media.dto.MediaFileResponse;
import com.beautica.media.entity.EntityType;
import com.beautica.media.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;

import java.util.UUID;

/**
 * Media upload/download endpoints — avatar + portfolio.
 *
 * <p><b>SEC-1 (BLOCKER carry-forward from Phase 7.1 audit).</b> The {@code userId} /
 * {@code actorId} for every authenticated mutation is derived from the
 * {@link Authentication} principal. There is intentionally no request-body or
 * path-variable field that lets a caller name a target user/salon/master —
 * {@link MediaService} resolves the owning entity server-side from the actor's
 * role. This eliminates the IDOR vector flagged in the Phase 7.1 audit.
 *
 * <p><b>Path layout.</b> Base mapping is {@code /api/v1} (not {@code /api/v1/media})
 * because the public listing endpoints diverge from the media root —
 * {@code /api/v1/salons/{id}/portfolio} and {@code /api/v1/masters/{id}/portfolio}
 * are nested under their owning resource for REST consistency with the rest of
 * the API.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    // ── avatar ────────────────────────────────────────────────────────────────

    /**
     * Replace the authenticated user's avatar. Any role may call this — every
     * user has exactly one avatar slot keyed by the JWT principal.
     */
    @PostMapping("/media/avatar")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<AvatarResponse> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        UUID userId = extractUserId(authentication);
        return ApiResponse.ok(mediaService.uploadAvatar(userId, file));
    }

    @DeleteMapping("/media/avatar")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAvatar(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        mediaService.deleteAvatar(userId);
    }

    // ── portfolio (uploads — SALON_OWNER + INDEPENDENT_MASTER only) ───────────

    @PostMapping("/media/portfolio")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'INDEPENDENT_MASTER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MediaFileResponse> uploadPortfolioPhoto(
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        UUID actorId = extractUserId(authentication);
        Role actorRole = extractRole(authentication);
        return ApiResponse.ok(mediaService.uploadPortfolioPhoto(actorId, actorRole, file));
    }

    @DeleteMapping("/media/portfolio/{mediaId}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePortfolioPhoto(
            @PathVariable UUID mediaId,
            Authentication authentication
    ) {
        UUID actorId = extractUserId(authentication);
        mediaService.deletePortfolioPhoto(actorId, mediaId);
    }

    // ── portfolio listings (public) ───────────────────────────────────────────

    @GetMapping("/salons/{salonId}/portfolio")
    public ApiResponse<Page<MediaFileResponse>> getSalonPortfolio(
            @PathVariable UUID salonId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(mediaService.getPortfolio(EntityType.SALON, salonId, pageable));
    }

    @GetMapping("/masters/{masterId}/portfolio")
    public ApiResponse<Page<MediaFileResponse>> getMasterPortfolio(
            @PathVariable UUID masterId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(mediaService.getPortfolio(EntityType.MASTER, masterId, pageable));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolve the authenticated user's UUID from the principal. Uses the safer
     * {@code instanceof UUID id} pattern (Anti-Bug Playbook § B) so a malformed
     * authentication object surfaces as 403, not a {@link ClassCastException}
     * 500.
     */
    private UUID extractUserId(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID id) {
            return id;
        }
        throw new ForbiddenException("Not authenticated");
    }

    /**
     * Resolve the authenticated user's {@link Role} from the granted authority
     * (e.g. {@code ROLE_SALON_OWNER} → {@link Role#SALON_OWNER}).
     *
     * <p>Each principal carries exactly one role in this project — pick the
     * first granted authority that has the {@code ROLE_} prefix and translate.
     */
    private Role extractRole(Authentication authentication) {
        if (authentication == null) {
            throw new ForbiddenException("Not authenticated");
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                try {
                    return Role.valueOf(name.substring("ROLE_".length()));
                } catch (IllegalArgumentException ignored) {
                    // fall through — another authority might be a valid role
                }
            }
        }
        throw new ForbiddenException("No role assigned");
    }
}
