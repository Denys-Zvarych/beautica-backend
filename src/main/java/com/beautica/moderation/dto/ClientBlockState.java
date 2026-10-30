package com.beautica.moderation.dto;

import java.util.UUID;

/**
 * Phase 397 (mobile 425 inputs) — the viewer's block state for one booking, for the provider booking detail.
 *
 * @param blockable the viewer may block this booking's client (provider scope + a verified identity; never a
 *                  STAFF walk-in)
 * @param blockId   the existing block row in the viewer's scope, or {@code null} when not blocked
 */
public record ClientBlockState(boolean blockable, UUID blockId) {

    public static final ClientBlockState NOT_BLOCKABLE = new ClientBlockState(false, null);

    public boolean blocked() {
        return blockId != null;
    }
}
