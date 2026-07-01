package com.beautica.favorite.dto;

import com.beautica.favorite.entity.FavoriteTargetType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/favorites}.
 *
 * <p>{@code clientUserId} is intentionally absent — the favoriting client is
 * always derived from the authenticated principal, never trusted from the body
 * (Anti-Bug §B; a caller must not be able to favorite on behalf of another user).
 *
 * <p>{@code targetType} carries no per-field annotation beyond {@code @NotNull}:
 * an unrecognised enum string is rejected by Jackson and translated to a generic
 * 400 by {@code GlobalExceptionHandler#handleMessageNotReadable} without echoing
 * the valid-constant list (§A enum row).
 */
public record AddFavoriteRequest(

        @NotNull
        FavoriteTargetType targetType,

        @NotNull
        UUID targetId
) {
}
