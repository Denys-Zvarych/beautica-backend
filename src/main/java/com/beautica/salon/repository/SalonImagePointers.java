package com.beautica.salon.repository;

/**
 * Interface projection of a salon's logo/cover pointers, read under {@code FOR UPDATE} by
 * {@link SalonRepository#lockImagePointers} (Phase 343 D8). No managed entity is loaded.
 */
public interface SalonImagePointers {

    String getAvatarUrl();

    String getAvatarR2Key();

    String getCoverImageUrl();

    String getCoverR2Key();
}
