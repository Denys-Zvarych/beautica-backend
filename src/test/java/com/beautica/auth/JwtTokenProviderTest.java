package com.beautica.auth;

import com.beautica.config.JwtConfig;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET =
            "test-secret-that-is-long-enough-for-hs256-ok-padding-here";
    private static final long ACCESS_EXPIRY_MS = 900_000L;
    private static final long REFRESH_EXPIRY_MS = 2_592_000_000L;

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        var config = new JwtConfig(SECRET, ACCESS_EXPIRY_MS, REFRESH_EXPIRY_MS);
        jwtTokenProvider = new JwtTokenProvider(config);
    }

    @Test
    void should_generateAccessToken_when_validInputsProvided() {
        var userId = UUID.randomUUID();
        var email = "test@example.com";
        var role = Role.CLIENT;

        String token = jwtTokenProvider.generateAccessToken(userId, email, role);

        assertThat(token).isNotBlank();
    }

    @Test
    void should_extractUserId_when_accessTokenParsed() {
        var userId = UUID.randomUUID();

        String token = jwtTokenProvider.generateAccessToken(userId, "test@example.com", Role.CLIENT);
        UUID extracted = jwtTokenProvider.getUserIdFromToken(token);

        assertThat(extracted).isEqualTo(userId);
    }

    @Test
    void should_extractEmail_when_accessTokenParsed() {
        var email = "user@beautica.com";

        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), email, Role.SALON_OWNER);
        String extracted = jwtTokenProvider.getEmailFromToken(token);

        assertThat(extracted).isEqualTo(email);
    }

    @Test
    void should_extractRole_when_accessTokenParsed() {
        var role = Role.INDEPENDENT_MASTER;

        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "m@example.com", role);
        Role extracted = jwtTokenProvider.getRoleFromToken(token);

        assertThat(extracted).isEqualTo(role);
    }

    @Test
    void should_generateRefreshToken_when_validUserIdProvided() {
        var userId = UUID.randomUUID();

        String token = jwtTokenProvider.generateRefreshToken(userId);

        assertThat(token).isNotBlank();
    }

    @Test
    void should_extractUserId_when_refreshTokenParsed() {
        var userId = UUID.randomUUID();

        String token = jwtTokenProvider.generateRefreshToken(userId);
        UUID extracted = jwtTokenProvider.getUserIdFromToken(token);

        assertThat(extracted).isEqualTo(userId);
    }

    @Test
    void should_validateToken_when_tokenIsValid() {
        String token = jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), "ok@test.com", Role.CLIENT);

        boolean valid = jwtTokenProvider.validateToken(token);

        assertThat(valid).isTrue();
    }

    @Test
    void should_throwJwtException_when_tokenIsExpired() {
        var expiredConfig = new JwtConfig(SECRET, -1L, REFRESH_EXPIRY_MS);
        var expiredProvider = new JwtTokenProvider(expiredConfig);
        String token = expiredProvider.generateAccessToken(
                UUID.randomUUID(), "exp@test.com", Role.CLIENT);

        assertThatThrownBy(() -> jwtTokenProvider.validateToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void should_throwJwtException_when_tokenIsTampered() {
        String token = jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), "t@test.com", Role.CLIENT);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThatThrownBy(() -> jwtTokenProvider.validateToken(tampered))
                .isInstanceOf(JwtException.class);
    }
}
