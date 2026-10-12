package com.beautica.media.controller;

import com.beautica.auth.Role;
import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.media.dto.AvatarResponse;
import com.beautica.media.dto.MediaFileResponse;
import com.beautica.media.entity.EntityType;
import com.beautica.media.service.MediaService;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;

import java.util.UUID;

/**
 * Media upload/download endpoints — avatar + portfolio.
 *
 * <p><b>SEC-1 (BLOCKER carry-forward from Phase 7.1 audit).</b> The {@code userId} /
 * {@code actorId} for every authenticated mutation is derived from the
 * {@link Authentication} principal. There is intentionally no request-body or
 * path-variable field that lets a caller name a target user/master —
 * {@link MediaService} resolves the owning entity server-side from the actor's
 * role. This eliminates the IDOR vector flagged in the Phase 7.1 audit. (Salon
 * portfolio writes name the salon explicitly and are gated by {@code canManageSalon}
 * on {@link SalonMediaController} — Phase 362.)
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
    @PostMapping(value = "/media/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<AvatarResponse> uploadAvatar(
            @Parameter(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE))
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        UUID userId = AuthenticationUtils.userId(authentication);
        return ApiResponse.ok(mediaService.uploadAvatar(userId, file));
    }

    @DeleteMapping("/media/avatar")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAvatar(Authentication authentication) {
        UUID userId = AuthenticationUtils.userId(authentication);
        mediaService.deleteAvatar(userId);
    }

    // ── portfolio writes (INDEPENDENT_MASTER only — Phase 362 D4) ──────────────
    // Salon portfolios are written through SalonMediaController (/salons/{salonId}/portfolio).

    @Operation(summary = "Add a photo to the caller's own master portfolio (INDEPENDENT_MASTER only)",
            description = "Multipart field `file`: JPEG, PNG or WebP (detected by magic bytes), max 5 MB. "
                    + "A portfolio holds at most " + MediaService.MAX_PORTFOLIO_PHOTOS + " photos; the next upload "
                    + "is rejected with 409 (`data.code` = `PORTFOLIO_LIMIT_REACHED`). Salon portfolios use POST /api/v1/salons/{salonId}/portfolio.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Empty/oversized file or unsupported image format"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not an INDEPENDENT_MASTER with a master profile"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "Portfolio photo limit reached — `data.code` is `PORTFOLIO_LIMIT_REACHED`. On this "
                            + "endpoint 409 means the cap and nothing else."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "Media storage is not configured, or the portfolio is busy (a concurrent upload "
                            + "held the lock past its timeout) — the busy case carries a `Retry-After` header "
                            + "(seconds); retry after it. Nothing was stored.")
    })
    @PostMapping(value = "/media/portfolio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('INDEPENDENT_MASTER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MediaFileResponse> uploadPortfolioPhoto(
            @Parameter(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE))
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        Role actorRole = AuthenticationUtils.role(authentication);
        return ApiResponse.ok(mediaService.uploadPortfolioPhoto(actorId, actorRole, file));
    }

    @Operation(summary = "Remove a photo from the caller's own master portfolio (INDEPENDENT_MASTER only)",
            description = "Only rows of the caller's own master portfolio; a salon photo is 404 here "
                    + "(use DELETE /api/v1/salons/{salonId}/portfolio/{mediaId}).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not an INDEPENDENT_MASTER"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Not a photo of the caller's own master portfolio (unknown id, a salon photo "
                            + "or another master's photo — deliberately indistinguishable)")
    })
    @DeleteMapping("/media/portfolio/{mediaId}")
    @PreAuthorize("hasRole('INDEPENDENT_MASTER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePortfolioPhoto(
            @PathVariable UUID mediaId,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        mediaService.deletePortfolioPhoto(actorId, mediaId);
    }

    // ── portfolio listings (public) ───────────────────────────────────────────

    /**
     * Fixed page request used for all public portfolio listings (PERF-M3).
     *
     * <p>The paginated {@link MediaService#getPortfolio(EntityType, UUID, PageRequest)}
     * overload is now cached with the key {@code SimpleKey(entityType, entityId)}.
     * A fixed {@code PageRequest} ensures the key is stable per entity — a variable
     * {@code Pageable} from the caller would require per-page cache entries and
     * unbounded heap growth. Portfolios are small (&le;50 items for any realistic
     * entity at current scale), so returning all items in one cached page is correct.
     */
    private static final PageRequest PORTFOLIO_PAGE =
            PageRequest.of(0, 50, Sort.by(Direction.DESC, "createdAt"));

    @GetMapping("/salons/{salonId}/portfolio")
    public ApiResponse<Page<MediaFileResponse>> getSalonPortfolio(@PathVariable UUID salonId) {
        return ApiResponse.ok(mediaService.getPortfolio(EntityType.SALON, salonId, PORTFOLIO_PAGE));
    }

    @GetMapping("/masters/{masterId}/portfolio")
    public ApiResponse<Page<MediaFileResponse>> getMasterPortfolio(@PathVariable UUID masterId) {
        return ApiResponse.ok(mediaService.getPortfolio(EntityType.MASTER, masterId, PORTFOLIO_PAGE));
    }

}
