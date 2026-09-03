package com.beautica.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    /**
     * Bulk sibling of {@link #deleteByUserId} — purges every refresh token for every user in
     * {@code userIds} in ONE statement. Added for the salon-deletion staff cascade (Phase 290
     * perf finding #1): {@code SalonService.deactivateSalonStaff} previously called
     * {@link #deleteByUserId} once per staff member (N single-row round trips); this collapses
     * that to exactly one bulk {@code DELETE ... WHERE user_id IN (...)}.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.userId IN :userIds")
    void deleteByUserIdIn(@Param("userIds") Collection<UUID> userIds);

    /**
     * Hard-deletes every row whose {@code expires_at} is strictly before {@code cutoff}. Wired
     * to {@code RefreshTokenCleanupJob}'s daily sweep — the caller derives {@code cutoff} as
     * {@code now - cleanupRetention} (see {@code com.beautica.config.RefreshTokenPolicyConfig}),
     * so this only ever removes rows that expired at least {@code cleanupRetention} ago,
     * revoked or not. Returns the row count so the caller can log/assert on it.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoff")
    int deleteAllByExpiresAtBefore(@Param("cutoff") Instant cutoff);

    @Query("SELECT COUNT(rt) FROM RefreshToken rt WHERE rt.userId = :userId")
    long countByUserId(@Param("userId") UUID userId);

    /**
     * Reuse detection: revokes every non-revoked token sharing {@code familyId}. Called when
     * an already-revoked token is replayed — the whole rotation chain is treated as
     * compromised, not just the replayed token. Revokes rather than deletes, preserving the
     * audit trail. Returns the number of rows actually flipped (already-revoked rows in the
     * family, including the replayed one, are excluded by the WHERE clause).
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.isRevoked = true "
            + "WHERE rt.familyId = :familyId AND rt.isRevoked = false")
    int revokeAllByFamilyId(@Param("familyId") UUID familyId);
}
