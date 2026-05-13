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
}
