package com.beautica.user;

import java.util.UUID;

/**
 * Interface projection of a user's avatar pointers, read under {@code FOR UPDATE} by
 * {@link UserRepository#lockAvatarPointersByIdIn} (security S-L2 / perf P-L2). No managed entity is loaded.
 */
public interface UserAvatarPointers {

    UUID getId();

    String getAvatarR2Key();

    String getAvatarUrl();
}
