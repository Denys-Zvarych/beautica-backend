package com.beautica.favorite.entity;

/**
 * Discriminator for a polymorphic {@link Favorite} row.
 *
 * <p>{@code MASTER} means an <b>independent master</b> — a {@code users} row with
 * role {@code INDEPENDENT_MASTER} that owns a {@code masters} row; the favorite's
 * {@code targetId} is the {@code masters.id}. A {@code SALON_MASTER} is never a
 * valid favorite target; that rejection lives in
 * {@link com.beautica.favorite.service.FavoriteService}, not in the DB CHECK.
 *
 * <p>{@code SALON} means a {@code salons} row; the favorite's {@code targetId} is
 * the {@code salons.id}.
 */
public enum FavoriteTargetType {
    MASTER,
    SALON
}
