package com.beautica.moderation.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 397 — one {@code GET /blocks} row as the list query selects it: the block's own columns plus the blocked
 * account's live first/last name and avatar URL (NULL for a GUEST row). A constructor projection, so the list never
 * hydrates a {@code User} (no {@code password_hash} or other account columns leave the database).
 *
 * @param blockedUserId non-null exactly for a CLIENT row
 * @param phoneLast4    non-null only for a GUEST row
 */
public record ClientBlockListRow(
        UUID id,
        UUID blockedUserId,
        String phoneLast4,
        String displayName,
        Instant createdAt,
        String userFirstName,
        String userLastName,
        String userAvatarUrl
) {

    public boolean isRegisteredClient() {
        return blockedUserId != null;
    }
}
