package com.beautica.moderation.repository;

import java.util.UUID;

/**
 * B507 — the identity of one block as the batch probe selects it ({@code ClientBlockRepository#findBlockedAmong}): a
 * CLIENT row carries {@code blockedUserId} only, a GUEST row carries {@code phoneHash} only. Constructor projection,
 * so no {@code ClientBlock} is hydrated. The hash is a peppered digest, never the phone; still never logged.
 */
public record ClientBlockKeyRow(UUID blockedUserId, byte[] phoneHash) {

    @Override
    public String toString() {
        return "ClientBlockKeyRow[kind=" + (blockedUserId != null ? "CLIENT" : "GUEST") + "]";
    }
}
