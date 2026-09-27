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
     * Idempotency pre-check for POST /api/v1/devices/token.
     * Backed by the UNIQUE (user_id, token) index from the V29 migration —
     * an index-only scan that avoids the JPA persistence-context allocation
     * of {@code findByUserIdAndToken}.
     */
    boolean existsByUserIdAndToken(UUID userId, String token);

    @Query("SELECT dt.id AS id, dt.token AS token FROM DeviceToken dt WHERE dt.user.id = :userId AND dt.isActive = true")
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
