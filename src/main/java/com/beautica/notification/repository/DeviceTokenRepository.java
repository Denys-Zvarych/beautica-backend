package com.beautica.notification.repository;

import com.beautica.notification.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    interface DeviceTokenSummary {
        java.util.UUID getId();
        String getToken();
    }

    /**
     * @deprecated Use {@link #findActiveTokenSummaryByUserId(UUID)} for the push-notification
     * hot path — it returns a lightweight projection and avoids loading full entities.
     * This overload fetches full entities and risks N+1 if associations are added to
     * {@link com.beautica.notification.entity.DeviceToken} in the future.
     */
    @Deprecated
    List<DeviceToken> findByUserIdAndIsActiveTrue(UUID userId);

    /**
     * Phase 339 (D10) — atomically registers {@code token} for {@code userId}: inserts it, or, when
     * the token already exists under ANY user (a shared device switching accounts) or is inactive,
     * rebinds it to the caller, refreshes {@code platform} and re-activates it. One statement, so two
     * concurrent registrations of the same token can never interleave a reassign/exists/save sequence
     * (no lost update, no unique-violation 500).
     *
     * <p>{@code ON CONFLICT (token)} is the governing arbiter: {@code ux_device_tokens_token} (V185)
     * makes the token globally unique. V29's redundant {@code UNIQUE (user_id, token)} is dropped by
     * V186 — while it existed it could fire before the arbiter on a same-user concurrent upsert. Repeating
     * the call for the same owner is idempotent (one row, same owner).
     *
     * @return the number of rows written (always 1 — inserted or updated)
     */
    @Modifying
    @Query(value = """
            INSERT INTO device_tokens (id, user_id, token, platform, is_active)
            VALUES (gen_random_uuid(), :userId, :token, :platform, true)
            ON CONFLICT (token) DO UPDATE
               SET user_id = EXCLUDED.user_id,
                   platform = EXCLUDED.platform,
                   is_active = true,
                   updated_at = now()
            """, nativeQuery = true)
    int upsertToken(@Param("token") String token, @Param("userId") UUID userId,
                    @Param("platform") String platform);

    /** A token row plus its owner — the batch lookup's projection (phase 339 drain pre-load). */
    interface UserDeviceToken extends DeviceTokenSummary {
        UUID getUserId();
    }

    /**
     * Phase 339 — active tokens of EVERY user in {@code userIds} in one statement, for the outbox
     * drainer's pre-load block (one query per batch instead of one per push). Backed by
     * {@code idx_device_tokens_user_active}.
     */
    @Query("SELECT dt.id AS id, dt.token AS token, dt.user.id AS userId FROM DeviceToken dt "
            + "WHERE dt.user.id IN :userIds AND dt.isActive = true")
    List<UserDeviceToken> findActiveTokensByUserIdIn(@Param("userIds") Collection<UUID> userIds);

    /**
     * The user's ACTIVE tokens, and none at all when the USER is deactivated — the push ownership
     * re-check relies on this so an account deactivated between prepare and send gets no push.
     */
    @Query("SELECT dt.id AS id, dt.token AS token FROM DeviceToken dt "
            + "WHERE dt.user.id = :userId AND dt.isActive = true AND dt.user.isActive = true")
    List<DeviceTokenSummary> findActiveTokenSummaryByUserId(@Param("userId") java.util.UUID userId);

    @Transactional
    @Modifying
    @Query("DELETE FROM DeviceToken dt WHERE dt.user.id = :userId AND dt.token IN :tokens")
    void deleteByUserIdAndTokenIn(@Param("userId") java.util.UUID userId, @Param("tokens") Collection<String> tokens);

    /**
     * Per-user token removal — removes a specific token owned by a specific user.
     * Used on logout or explicit device deregistration.
     * Bulk DELETE avoids the SELECT-then-DELETE double round-trip produced by derived delete methods.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM DeviceToken dt WHERE dt.user.id = :userId AND dt.token = :token")
    void deleteByUserIdAndToken(@Param("userId") UUID userId, @Param("token") String token);

    /**
     * TTL eviction query — returns all inactive tokens whose updatedAt timestamp is older than
     * the given cutoff. The {@code updatedAt} field (inherited from {@code AuditableEntity} via
     * {@code @UpdateTimestamp}) serves as the TTL anchor; a scheduled job calls this to clean up
     * stale records.
     */
    List<DeviceToken> findAllByIsActiveFalseAndUpdatedAtBefore(Instant cutoff);

    /**
     * Bulk-purges EVERY device token row for a user, active or not — added for the salon-deletion
     * staff cascade (Phase 290), which does not have specific token VALUES in hand (unlike
     * {@link #deleteByUserIdAndToken}/{@link #deleteByUserIdAndTokenIn}, both driven by a value
     * the calling device just presented on logout/deregistration). Mirrors
     * {@code RefreshTokenRepository.deleteByUserId} exactly: a deactivated staff account must not
     * keep receiving push notifications addressed to a userId whose salon employment just ended,
     * for however long the device happens to hold a registered token.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM DeviceToken dt WHERE dt.user.id = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    /**
     * Bulk sibling of {@link #deleteByUserId} — purges every device token for every user in
     * {@code userIds} in ONE statement. Added for the salon-deletion staff cascade (Phase 290
     * perf finding #1): {@code SalonService.deleteSalonStaff} previously called
     * {@link #deleteByUserId} once per staff member (N single-row round trips); this collapses
     * that to exactly one bulk {@code DELETE ... WHERE user_id IN (...)}.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM DeviceToken dt WHERE dt.user.id IN :userIds")
    void deleteByUserIdIn(@Param("userIds") Collection<UUID> userIds);
}
