package com.beautica.notification.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.notification.entity.DeviceToken;
import com.beautica.notification.entity.Platform;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link DeviceTokenRepository}.
 *
 * <p>Extends {@link AbstractDataJpaTest} so the PostgreSQL container is shared across
 * {@code @DataJpaTest} slice tests within the JVM. Slice annotations
 * ({@code @DataJpaTest}, {@code @AutoConfigureTestDatabase}, {@code @Testcontainers},
 * {@code @ActiveProfiles}) live on the base class.
 */
class DeviceTokenRepositoryTest extends AbstractDataJpaTest {

    @Autowired
    private DeviceTokenRepository repo;

    @Autowired
    private TestEntityManager em;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User(
                "device-token-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.CLIENT,
                "Device",
                "User",
                "+380501111111"
        );
        em.persist(user);
        em.flush();
    }

    @Test
    @DisplayName("Persists a device token with audit timestamps populated and is_active defaulting to true")
    void should_persistDeviceToken_with_defaults() {
        DeviceToken token = DeviceToken.builder()
                .user(user)
                .token("fcm-token-abc123")
                .platform(Platform.ANDROID)
                .build();

        repo.save(token);
        em.flush();
        em.clear();

        DeviceToken reloaded = em.find(DeviceToken.class, token.getId());

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Returns only active tokens for a given user, filtering out inactive rows")
    void should_findActiveTokensByUserId() {
        DeviceToken activeToken = DeviceToken.builder()
                .user(user)
                .token("fcm-active-token")
                .platform(Platform.IOS)
                .isActive(true)
                .build();
        em.persist(activeToken);

        DeviceToken inactiveToken = DeviceToken.builder()
                .user(user)
                .token("fcm-inactive-token")
                .platform(Platform.IOS)
                .isActive(false)
                .build();
        em.persist(inactiveToken);

        em.flush();
        em.clear();

        List<DeviceToken> result = repo.findByUserIdAndIsActiveTrue(user.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getToken()).isEqualTo("fcm-active-token");
    }

    @Test
    @DisplayName("Rejects a duplicate (user_id, token) pair via the UNIQUE constraint as DataIntegrityViolationException")
    void should_enforceUnique_sameUserAndToken() {
        DeviceToken first = DeviceToken.builder()
                .user(user)
                .token("duplicate-token")
                .platform(Platform.ANDROID)
                .build();
        repo.saveAndFlush(first);

        DeviceToken duplicate = DeviceToken.builder()
                .user(user)
                .token("duplicate-token")
                .platform(Platform.ANDROID)
                .build();

        // Spring Data's exception translation converts Hibernate's ConstraintViolationException
        // to DataIntegrityViolationException when going through the repository layer.
        assertThatThrownBy(() -> repo.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Performs a hard delete for the (userId, token) row — em.find returns null, not just an inactive row")
    void should_deleteByUserIdAndToken() {
        DeviceToken token = DeviceToken.builder()
                .user(user)
                .token("token-to-delete-by-user-and-value")
                .platform(Platform.IOS)
                .build();
        em.persist(token);
        em.flush();
        UUID tokenId = token.getId();
        em.clear();

        repo.deleteByUserIdAndToken(user.getId(), "token-to-delete-by-user-and-value");
        em.flush();
        em.clear();

        // Strong assertion: prove the row is GONE, not merely deactivated.
        // findByUserIdAndIsActiveTrue would also return empty for a soft-delete (is_active=false),
        // so it cannot distinguish hard delete from deactivation. em.find directly inspects the row.
        DeviceToken reloaded = em.find(DeviceToken.class, tokenId);
        assertThat(reloaded).isNull();
    }

    @Test
    @DisplayName("Returns true when (userId, token) row exists for the given user")
    void should_returnTrue_when_userTokenExists() {
        DeviceToken token = DeviceToken.builder()
                .user(user)
                .token("present-token")
                .platform(Platform.ANDROID)
                .build();
        em.persist(token);
        em.flush();
        em.clear();

        boolean result = repo.existsByUserIdAndToken(user.getId(), "present-token");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Returns false when no (userId, token) row matches — different token, same user")
    void should_returnFalse_when_userTokenAbsent() {
        DeviceToken token = DeviceToken.builder()
                .user(user)
                .token("existing-token")
                .platform(Platform.ANDROID)
                .build();
        em.persist(token);
        em.flush();
        em.clear();

        boolean result = repo.existsByUserIdAndToken(user.getId(), "missing-token");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Scopes deleteByUserIdAndToken to the owning user — does not remove a same-token row owned by a different user")
    void should_not_deleteTokensOfOtherUsers_when_deleteByUserIdAndToken() {
        User userB = new User(
                "device-token-b-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.CLIENT,
                "Device",
                "UserB",
                "+380502222222"
        );
        em.persist(userB);
        em.flush();

        DeviceToken tokenA = DeviceToken.builder()
                .user(user)
                .token("token-x")
                .platform(Platform.ANDROID)
                .build();
        em.persist(tokenA);

        DeviceToken tokenB = DeviceToken.builder()
                .user(userB)
                .token("token-y")
                .platform(Platform.ANDROID)
                .build();
        em.persist(tokenB);

        em.flush();
        em.clear();

        repo.deleteByUserIdAndToken(user.getId(), "token-x");

        List<DeviceToken> userBTokens = repo.findByUserIdAndIsActiveTrue(userB.getId());
        assertThat(userBTokens).hasSize(1);
        assertThat(userBTokens.get(0).getToken()).isEqualTo("token-y");
    }

    // -------------------------------------------------------------------------
    // findAllByIsActiveFalseAndUpdatedAtBefore — TTL eviction window query
    // -------------------------------------------------------------------------
    //
    // updated_at is populated by Hibernate's @UpdateTimestamp on every flush, so
    // setting it via the entity setter has no effect — Hibernate overwrites the
    // value on the way to the DB. Each TTL test therefore persists the row, then
    // overwrites updated_at with a native UPDATE so the cutoff comparison is
    // deterministic.

    @Test
    @DisplayName("Returns an empty list when only active tokens exist, regardless of their updated_at vs cutoff")
    void should_returnEmpty_when_onlyActiveTokensExist() {
        DeviceToken active = DeviceToken.builder()
                .user(user)
                .token("ttl-active-token")
                .platform(Platform.ANDROID)
                .isActive(true)
                .build();
        em.persist(active);
        em.flush();
        // Drive updated_at well into the past — query still must skip this row because is_active=true.
        forceUpdatedAt(active.getId(), Instant.now().minus(30, ChronoUnit.DAYS));
        em.clear();

        List<DeviceToken> result = repo.findAllByIsActiveFalseAndUpdatedAtBefore(Instant.now());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Excludes recently-deactivated tokens whose updated_at is after the cutoff (still inside TTL window)")
    void should_excludeRecentlyInactiveTokens_when_updatedAtAfterCutoff() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(7, ChronoUnit.DAYS);

        DeviceToken recent = DeviceToken.builder()
                .user(user)
                .token("ttl-recent-inactive-token")
                .platform(Platform.IOS)
                .isActive(false)
                .build();
        em.persist(recent);
        em.flush();
        // Recently inactive — updated_at one day ago, cutoff is seven days ago, so token must be excluded.
        forceUpdatedAt(recent.getId(), now.minus(1, ChronoUnit.DAYS));
        em.clear();

        List<DeviceToken> result = repo.findAllByIsActiveFalseAndUpdatedAtBefore(cutoff);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Includes stale inactive tokens whose updated_at is before the cutoff — eligible for eviction")
    void should_includeStaleInactiveTokens_when_updatedAtBeforeCutoff() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(7, ChronoUnit.DAYS);

        DeviceToken stale = DeviceToken.builder()
                .user(user)
                .token("ttl-stale-inactive-token")
                .platform(Platform.IOS)
                .isActive(false)
                .build();
        em.persist(stale);
        em.flush();
        UUID staleId = stale.getId();
        // Stale — updated_at ten days ago, cutoff is seven days ago, so token MUST appear.
        forceUpdatedAt(staleId, now.minus(10, ChronoUnit.DAYS));
        em.clear();

        List<DeviceToken> result = repo.findAllByIsActiveFalseAndUpdatedAtBefore(cutoff);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(staleId);
        assertThat(result.get(0).getToken()).isEqualTo("ttl-stale-inactive-token");
    }

    /**
     * Bypasses Hibernate's {@code @UpdateTimestamp} by writing {@code updated_at} directly via
     * a native UPDATE so each TTL test can pin the timestamp deterministically. Must be followed
     * by {@code em.clear()} (or a fresh load) so the persistence context observes the new value.
     */
    private void forceUpdatedAt(UUID tokenId, Instant updatedAt) {
        em.getEntityManager()
                .createNativeQuery("UPDATE device_tokens SET updated_at = ?1 WHERE id = ?2")
                .setParameter(1, updatedAt)
                .setParameter(2, tokenId)
                .executeUpdate();
    }
}
