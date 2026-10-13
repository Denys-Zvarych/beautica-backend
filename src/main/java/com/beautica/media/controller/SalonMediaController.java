package com.beautica.media.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.media.dto.MediaFileResponse;
import com.beautica.media.service.MediaService;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.entity.SalonImageSlot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.beans.PropertyEditorSupport;
import java.util.UUID;

/**
 * Salon-scoped media writes. HTTP only; the flows live in {@link MediaService}.
 *
 * <p><b>Logo and cover (banner) — Phase 343, OWNER only (locked product rule).</b> The gate is
 * {@code hasRole('SALON_OWNER') and @authz.isOwnerOf(authentication, #salonId)} — never {@code canManageSalon},
 * which also admits the assigned {@code SALON_ADMIN}. The service re-proves ownership before the R2 upload and
 * again under the row lock. One generic pair of handlers covers both slots ({@code logo} / {@code cover}); an
 * unknown slot is 400.
 *
 * <p><b>Portfolio — Phase 362, owner OR the salon's bound SALON_ADMIN.</b> Salon portfolio photos belong to the
 * salon (locked rule 2026-10-12), so the gate is {@code canManageSalon}, re-enforced in the service by
 * {@code enforceCanManageSalon}. Only {@code POST}/{@code DELETE} live here; the public
 * {@code GET /salons/{salonId}/portfolio} stays on {@link MediaController}.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}")
@RequiredArgsConstructor
public class SalonMediaController {

    private static final String OWNER_ONLY =
            "hasRole('SALON_OWNER') and @authz.isOwnerOf(authentication, #salonId)";

    private static final String SALON_MANAGER =
            "hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)";

    private final MediaService mediaService;

    /**
     * Binds {@code {slot}} by EXACT path segment ({@code logo} / {@code cover}) through
     * {@link SalonImageSlot#fromPathSegment} — security audit cycle 1. A custom editor, not a
     * {@code Converter} bean: when a conversion-service converter FAILS, {@code TypeConverterDelegate} falls
     * back to its built-in String→enum step ({@code Enum.valueOf} on the TRIMMED text), which silently accepted
     * {@code /media/LOGO} and {@code /media/%20LOGO%20} as aliases. A registered editor is used instead of
     * that path, so its {@link IllegalArgumentException} is final: {@code MethodArgumentTypeMismatchException}
     * → {@code GlobalExceptionHandler} → a generic 400 that echoes neither the value nor the constants.
     */
    @InitBinder
    void bindSalonImageSlot(WebDataBinder binder) {
        binder.registerCustomEditor(SalonImageSlot.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue(SalonImageSlot.fromPathSegment(text)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown salon image slot")));
            }
        });
    }

    @Operation(summary = "Upload or replace the salon logo or cover (SALON_OWNER of this salon only)",
            description = "Multipart field `file`: JPEG, PNG or WebP (detected by magic bytes), max 5 MB. "
                    + "`slot` is `logo` (1:1) or `cover` (16:9). Returns the updated salon.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Unknown slot, empty/oversized file or unsupported image format"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not the SALON_OWNER of this salon (SALON_ADMIN included)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Salon not found or inactive"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "Request too large"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "Not multipart/form-data"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limited"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Media storage is not configured")
    })
    @PostMapping(value = "/media/{slot}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(OWNER_ONLY)
    public ResponseEntity<ApiResponse<SalonResponse>> uploadSalonImage(
            @PathVariable UUID salonId,
            @Parameter(schema = @Schema(allowableValues = {"logo", "cover"})) @PathVariable SalonImageSlot slot,
            @Parameter(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE))
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        return ResponseEntity.ok(ApiResponse.ok(mediaService.uploadSalonImage(actorId, salonId, slot, file)));
    }

    @Operation(summary = "Remove the salon logo or cover (SALON_OWNER of this salon only)",
            description = "Idempotent: 204 even when the slot is already empty.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Unknown slot"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not the SALON_OWNER of this salon (SALON_ADMIN included)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Salon not found or inactive"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limited"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Media storage is not configured")
    })
    @DeleteMapping("/media/{slot}")
    @PreAuthorize(OWNER_ONLY)
    public ResponseEntity<Void> deleteSalonImage(
            @PathVariable UUID salonId,
            @Parameter(schema = @Schema(allowableValues = {"logo", "cover"})) @PathVariable SalonImageSlot slot,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        mediaService.deleteSalonImage(actorId, salonId, slot);
        return ResponseEntity.noContent().build();
    }

    // ── portfolio (Phase 362) ────────────────────────────────────────────────────

    @Operation(summary = "Add a photo to the salon portfolio (SALON_OWNER or the salon's SALON_ADMIN)",
            description = "Multipart field `file`: JPEG, PNG or WebP (detected by magic bytes), max 5 MB. "
                    + "A portfolio holds at most " + MediaService.MAX_PORTFOLIO_PHOTOS + " photos; the next "
                    + "upload is rejected with 409 (`data.code` = `PORTFOLIO_LIMIT_REACHED`).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Empty/oversized file or unsupported image format"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller neither owns nor administers this salon"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Salon not found or inactive"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "Portfolio photo limit reached — `data.code` is `PORTFOLIO_LIMIT_REACHED`. On this "
                            + "endpoint 409 means the cap and nothing else."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "Request too large"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "Not multipart/form-data"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limited"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "Media storage is not configured, or the portfolio is busy (a concurrent upload "
                            + "held the lock past its timeout) — the busy case carries a `Retry-After` header "
                            + "(seconds); retry after it. Nothing was stored.")
    })
    @PostMapping(value = "/portfolio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(SALON_MANAGER)
    public ResponseEntity<ApiResponse<MediaFileResponse>> uploadSalonPortfolioPhoto(
            @PathVariable UUID salonId,
            @Parameter(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE))
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mediaService.uploadSalonPortfolioPhoto(actorId, salonId, file)));
    }

    @Operation(summary = "Remove a photo from the salon portfolio (SALON_OWNER or the salon's SALON_ADMIN)",
            description = "Any manager of the salon may remove any of its photos, whoever uploaded it.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller neither owns nor administers this salon"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Salon not found or inactive, or the photo is not in this salon's portfolio"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limited")
    })
    @DeleteMapping("/portfolio/{mediaId}")
    @PreAuthorize(SALON_MANAGER)
    public ResponseEntity<Void> deleteSalonPortfolioPhoto(
            @PathVariable UUID salonId,
            @PathVariable UUID mediaId,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        mediaService.deleteSalonPortfolioPhoto(actorId, salonId, mediaId);
        return ResponseEntity.noContent().build();
    }
}
