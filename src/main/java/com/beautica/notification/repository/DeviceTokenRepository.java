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
     * Hot read path: fetches only active tokens for a user.
     * Backed by the partial index idx_device_tokens_user_active (V31 migration).
     */
    List<DeviceToken> findByUserIdAndIsActiveTrue(UUID userId);

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
