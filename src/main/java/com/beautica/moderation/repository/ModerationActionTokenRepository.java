package com.beautica.moderation.repository;

import com.beautica.moderation.entity.ModerationActionToken;
import com.beautica.moderation.entity.ReportTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 414 — e-mailed moderation action tokens (V218). Every state change is a CONDITIONAL update on a live row
 * ({@code used_at IS NULL AND revoked_at IS NULL AND expires_at > :now}), so two concurrent clicks, or a click racing
 * a resolve, can never both win. {@code :now} is always the injected clock's instant (never SQL {@code now()}), so
 * tests pin expiry.
 */
public interface ModerationActionTokenRepository extends JpaRepository<ModerationActionToken, UUID> {

    /** The row for one stored token hash (unique index {@code ux_mat_token_hash}), live or not. */
    Optional<ModerationActionToken> findByTokenHash(String tokenHash);

    /**
     * D8: stores a fresh confirm nonce on a live row, overwriting any earlier one (the latest confirm page wins).
     *
     * @return 1 when stored, 0 when the row is no longer live
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE moderation_action_tokens
               SET confirm_nonce_hash = :nonceHash,
                   confirm_nonce_expires_at = :nonceExpiresAt
             WHERE token_hash = :tokenHash
               AND used_at IS NULL
               AND revoked_at IS NULL
               AND expires_at > :now
            """, nativeQuery = true)
    int setNonce(@Param("tokenHash") String tokenHash,
                 @Param("nonceHash") String nonceHash,
                 @Param("nonceExpiresAt") Instant nonceExpiresAt,
                 @Param("now") Instant now);

    /**
     * D9 step 1: single-use claim. Succeeds only for a live row whose current, unexpired nonce matches; consumes the
     * nonce in the same statement.
     *
     * @return 1 when this call claimed the row, 0 otherwise (unknown, used, revoked, expired, or nonce mismatch)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE moderation_action_tokens
               SET used_at = :now,
                   confirm_nonce_hash = NULL,
                   confirm_nonce_expires_at = NULL
             WHERE token_hash = :tokenHash
               AND used_at IS NULL
               AND revoked_at IS NULL
               AND expires_at > :now
               AND confirm_nonce_hash = :nonceHash
               AND confirm_nonce_expires_at > :now
            """, nativeQuery = true)
    int claim(@Param("tokenHash") String tokenHash, @Param("nonceHash") String nonceHash, @Param("now") Instant now);

    /**
     * Audit-fix: undoes THIS claim (matched by its exact {@code used_at}) after an unexpected failure of the resolve
     * that followed it, so the e-mailed link can be retried. A row revoked meanwhile (a resolve won) stays dead.
     *
     * @return 1 when the claim was released
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE moderation_action_tokens
               SET used_at = NULL
             WHERE token_hash = :tokenHash
               AND used_at = :claimedAt
               AND revoked_at IS NULL
            """, nativeQuery = true)
    int releaseClaim(@Param("tokenHash") String tokenHash, @Param("claimedAt") Instant claimedAt);

    /**
     * D11: kills every not-yet-revoked link of one target (called by every resolve, in the resolve transaction).
     * Audit-fix: CLAIMED rows ({@code used_at} set) are revoked too, so a claim that is later released after an
     * unexpected failure ({@link #releaseClaim}, which requires {@code revoked_at IS NULL}) can never bring a link back
     * once a concurrent resolve has committed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE moderation_action_tokens
               SET revoked_at = :now
             WHERE target_type = :targetType
               AND target_id = :targetId
               AND revoked_at IS NULL
            """, nativeQuery = true)
    int revokeAllForTarget(@Param("targetType") String targetType,
                           @Param("targetId") UUID targetId,
                           @Param("now") Instant now);

    /** Typed convenience over {@link #revokeAllForTarget(String, UUID, Instant)}. */
    default int revokeAllForTarget(ReportTargetType targetType, UUID targetId, Instant now) {
        return revokeAllForTarget(targetType.name(), targetId, now);
    }

    /**
     * D11 (retention / TARGET_GONE path): revokes one batch of LIVE rows (unused, unrevoked, unexpired) whose target has
     * no OPEN report left, which covers the bulk native TARGET_GONE sweep that does not go through
     * {@code ModerationService#resolve}. Expired rows are left alone: they are already dead and only wait for the purge.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH due AS MATERIALIZED (
                SELECT t.id FROM moderation_action_tokens t
                 WHERE t.used_at IS NULL
                   AND t.revoked_at IS NULL
                   AND t.expires_at > :now
                   AND NOT EXISTS (SELECT 1 FROM content_reports cr
                                    WHERE cr.target_type = t.target_type
                                      AND cr.target_id = t.target_id
                                      AND cr.status = 'OPEN')
                 LIMIT :batchSize
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE moderation_action_tokens mat SET revoked_at = :now FROM due WHERE mat.id = due.id
            """, nativeQuery = true)
    int revokeLiveWithoutOpenReport(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /** D16: deletes one batch of rows that expired before {@code cutoff}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH due AS MATERIALIZED (
                SELECT id FROM moderation_action_tokens
                 WHERE expires_at < :cutoff
                 LIMIT :batchSize
                 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM moderation_action_tokens mat USING due WHERE mat.id = due.id
            """, nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
