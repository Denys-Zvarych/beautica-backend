package com.beautica.user;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1.2 — JPA persist round-trip for email-verification fields on {@link User}.
 *
 * <p>Extends {@link AbstractDataJpaTest} for a lightweight {@code @DataJpaTest} slice
 * (no full Spring Boot context, no web layer). Each test runs inside a transaction
 * that is rolled back automatically, so no {@code cleanDb()} call is needed.
 *
 * <p>{@code em.flush()} + {@code em.clear()} after save ensures the subsequent
 * {@code userRepository.findById} hits the DB rather than the first-level cache,
 * giving a genuine round-trip assertion.
 */
@DisplayName("User — emailVerified JPA persist round-trip (phase 1.2)")
class UserVerificationPersistTest extends AbstractDataJpaTest {

    private static final String PASSWORD_HASH =
            new BCryptPasswordEncoder(4).encode("test-password");

    @Autowired
    private TestEntityManager em;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("emailVerified=true survives save and reload; OTP fields remain null")
    void should_persistEmailVerifiedField_when_userSaved() {
        // Arrange
        var user = new User(
                "verify-persist-" + UUID.randomUUID() + "@example.com",
                PASSWORD_HASH,
                Role.CLIENT,
                "Oksana",
                "Moroz",
                "+380501234567"
        );
        user.setEmailVerified(true);

        // Act
        userRepository.save(user);
        em.flush();
        em.clear();

        var reloaded = userRepository.findById(user.getId());

        // Assert
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().isEmailVerified()).isTrue();
        assertThat(reloaded.get().getVerificationCodeHash()).isNull();
        assertThat(reloaded.get().getVerificationCodeExpiresAt()).isNull();
    }
}
