package com.beautica.moderation.service;

import java.util.Objects;
import java.util.UUID;

/**
 * Phase 397 D1 — the owner of a block: an independent master ({@code masterId}) or a whole salon
 * ({@code salonId}). Exactly one is set, mirroring {@code chk_client_blocks_one_blocker}.
 */
public record BlockScope(UUID masterId, UUID salonId) {

    public BlockScope {
        if ((masterId == null) == (salonId == null)) {
            throw new IllegalArgumentException("Exactly one of masterId / salonId must be set");
        }
    }

    public static BlockScope master(UUID masterId) {
        return new BlockScope(Objects.requireNonNull(masterId), null);
    }

    public static BlockScope salon(UUID salonId) {
        return new BlockScope(null, Objects.requireNonNull(salonId));
    }
}
