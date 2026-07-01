package com.beautica.favorite.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.favorite.dto.AddFavoriteRequest;
import com.beautica.favorite.dto.FavoriteMasterResponse;
import com.beautica.favorite.dto.FavoriteResponse;
import com.beautica.favorite.dto.FavoriteSalonResponse;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.service.FavoriteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * CLIENT-only favorites endpoints (Phase 19.1).
 *
 * <p>Every route is gated by {@code @PreAuthorize("hasRole('CLIENT')")} (role-only,
 * §D) and resolves the favoriting client from the authenticated principal — never
 * from the request body or a query param (§B; a caller cannot favorite on behalf of
 * another user).
 *
 * <ul>
 *   <li>{@code POST /favorites} — idempotent like; {@code 200} on both create and
 *       duplicate ({@code 404} missing target, {@code 400} salon-employed master).</li>
 *   <li>{@code DELETE /favorites?targetType=&targetId=} — idempotent unlike,
 *       {@code 204}.</li>
 *   <li>{@code GET /favorites/masters} / {@code GET /favorites/salons} — the two
 *       read lists.</li>
 * </ul>
 */
@Validated
@RestController
@RequestMapping("/api/v1/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @PreAuthorize("hasRole('CLIENT')")
    @PostMapping
    public ApiResponse<FavoriteResponse> addFavorite(
            @Valid @RequestBody AddFavoriteRequest request,
            Authentication auth) {
        FavoriteResponse response = favoriteService.addFavorite(
                AuthenticationUtils.userId(auth), request.targetType(), request.targetId());
        return ApiResponse.ok(response);
    }

    @PreAuthorize("hasRole('CLIENT')")
    @DeleteMapping
    public ResponseEntity<Void> removeFavorite(
            @RequestParam @NotNull FavoriteTargetType targetType,
            @RequestParam @NotNull UUID targetId,
            Authentication auth) {
        favoriteService.removeFavorite(AuthenticationUtils.userId(auth), targetType, targetId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('CLIENT')")
    @GetMapping("/masters")
    public ApiResponse<PageResponse<FavoriteMasterResponse>> listMasterFavorites(
            @PageableDefault(size = 20) Pageable pageable,
            Authentication auth) {
        Page<FavoriteMasterResponse> page =
                favoriteService.listMasterFavorites(AuthenticationUtils.userId(auth), pageable);
        return ApiResponse.ok(toPageResponse(page));
    }

    @PreAuthorize("hasRole('CLIENT')")
    @GetMapping("/salons")
    public ApiResponse<PageResponse<FavoriteSalonResponse>> listSalonFavorites(
            @PageableDefault(size = 20) Pageable pageable,
            Authentication auth) {
        Page<FavoriteSalonResponse> page =
                favoriteService.listSalonFavorites(AuthenticationUtils.userId(auth), pageable);
        return ApiResponse.ok(toPageResponse(page));
    }

    private static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
