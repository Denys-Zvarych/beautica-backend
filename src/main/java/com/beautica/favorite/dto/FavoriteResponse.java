package com.beautica.favorite.dto;

import com.beautica.favorite.entity.Favorite;
import com.beautica.favorite.entity.FavoriteTargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound representation of a single favorite, returned by
 * {@code POST /api/v1/favorites} on both create and idempotent duplicate.
 *
 * <p>Authenticated CLIENT-only endpoint: {@code clientId} is the caller's own id,
 * so echoing identity fields here is not a §I leak (the caller already knows who
 * they are). Even so, the client id is omitted — the client never needs it back.
 */
public record FavoriteResponse(
        UUID id,
        FavoriteTargetType targetType,
        UUID targetId,
        Instant createdAt
) {
    public static FavoriteResponse from(Favorite favorite) {
        return new FavoriteResponse(
                favorite.getId(),
                favorite.getTargetType(),
                favorite.getTargetId(),
                favorite.getCreatedAt()
        );
    }
}
