package com.beautica.media.service;

import com.beautica.media.repository.MediaFileKey;

import java.util.List;
import java.util.UUID;

/**
 * Immutable, entity-free snapshot of one deleted account's external-storage pointers, captured BEFORE the
 * account's rows are deleted (P-L3: after-commit closures hold only these scalars). The avatar
 * pointers are RAW — {@link MediaService#purgeUserBlobsAfterCommit} resolves and own-prefix-verifies them
 * (S-L1), so no caller can smuggle an unchecked key into the purge.
 *
 * @param userId      the deleted account's id
 * @param avatarR2Key {@code users.avatar_r2_key} at deletion time, or {@code null}
 * @param avatarUrl   {@code users.avatar_url} at deletion time (legacy rows carry only this), or {@code null}
 * @param media       the account's own {@code media_files} keys (its USER rows and its master's MASTER portfolio,
 *                    entity-keyed — Phase 363; never a SALON row) + cache-eviction ids
 */
public record AccountBlobPointers(UUID userId, String avatarR2Key, String avatarUrl, List<MediaFileKey> media) {

    public AccountBlobPointers {
        media = List.copyOf(media);
    }
}
