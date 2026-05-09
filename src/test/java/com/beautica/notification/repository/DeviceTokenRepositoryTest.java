package com.beautica.notification.repository;

import com.beautica.auth.Role;
import com.beautica.notification.entity.DeviceToken;
import com.beautica.notification.entity.Platform;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DeviceTokenRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
    @DisplayName("should_persistDeviceToken_with_defaults")
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
    @DisplayName("should_findActiveTokensByUserId")
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
    @DisplayName("should_enforceUnique_sameUserAndToken")
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
    @DisplayName("should_deleteByUserIdAndToken")
    void should_deleteByUserIdAndToken() {
        DeviceToken token = DeviceToken.builder()
                .user(user)
                .token("token-to-delete-by-user-and-value")
                .platform(Platform.IOS)
                .build();
        em.persist(token);
        em.flush();
        em.clear();

        repo.deleteByUserIdAndToken(user.getId(), "token-to-delete-by-user-and-value");

        List<DeviceToken> result = repo.findByUserIdAndIsActiveTrue(user.getId());
        assertThat(result).isEmpty();
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
    @DisplayName("should_not_deleteTokensOfOtherUsers_when_deleteByUserIdAndToken")
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
}
