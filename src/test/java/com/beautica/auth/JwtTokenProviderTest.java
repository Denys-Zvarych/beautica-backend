package com.beautica.auth;

import com.beautica.config.JwtConfig;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtTokenProvider — unit")
class JwtTokenProviderTest {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProviderTest.class);

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
    @DisplayName("generateAccessToken returns a non-blank token")
    void should_generateAccessToken_when_validInputsProvided() {
        var userId = UUID.randomUUID();
        var email = "test@example.com";
        var role = Role.CLIENT;
        log.debug("Arrange: userId={}, email={}, role={}", userId, email, role);

        log.debug("Act: calling jwtTokenProvider.generateAccessToken");
        String token = jwtTokenProvider.generateAccessToken(userId, email, role);

        log.trace("Assert: token length={}", token.length());
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("getUserIdFromToken returns correct user ID from access token")
    void should_extractUserId_when_accessTokenParsed() {
        var userId = UUID.randomUUID();
        log.debug("Arrange: expected userId={}", userId);

        log.debug("Act: generating token and extracting userId");
        String token = jwtTokenProvider.generateAccessToken(userId, "test@example.com", Role.CLIENT);
        UUID extracted = jwtTokenProvider.getUserIdFromToken(token);

        log.trace("Assert: extracted userId={}", extracted);
        assertThat(extracted).isEqualTo(userId);
    }

    @Test
    @DisplayName("getEmailFromToken returns correct email from access token")
    void should_extractEmail_when_accessTokenParsed() {
        var email = "user@beautica.com";
        log.debug("Arrange: expected email={}", email);

        log.debug("Act: generating token and extracting email");
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), email, Role.SALON_OWNER);
        String extracted = jwtTokenProvider.getEmailFromToken(token);

        log.trace("Assert: extracted email={}", extracted);
        assertThat(extracted).isEqualTo(email);
    }

    @Test
    @DisplayName("getRoleFromToken returns correct role from access token")
    void should_extractRole_when_accessTokenParsed() {
        var role = Role.INDEPENDENT_MASTER;
        log.debug("Arrange: expected role={}", role);

        log.debug("Act: generating token and extracting role");
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "m@example.com", role);
        Role extracted = jwtTokenProvider.getRoleFromToken(token);

        log.trace("Assert: extracted role={}", extracted);
        assertThat(extracted).isEqualTo(role);
    }

    @Test
    @DisplayName("generateRefreshToken returns a non-blank token")
    void should_generateRefreshToken_when_validUserIdProvided() {
        var userId = UUID.randomUUID();
        log.debug("Arrange: userId={}", userId);

        log.debug("Act: calling jwtTokenProvider.generateRefreshToken");
        String token = jwtTokenProvider.generateRefreshToken(userId);

        log.trace("Assert: refresh token length={}", token.length());
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("getUserIdFromToken returns correct user ID from refresh token")
    void should_extractUserId_when_refreshTokenParsed() {
        var userId = UUID.randomUUID();
        log.debug("Arrange: expected userId={}", userId);

        log.debug("Act: generating refresh token and extracting userId");
        String token = jwtTokenProvider.generateRefreshToken(userId);
        UUID extracted = jwtTokenProvider.getUserIdFromToken(token);

        log.trace("Assert: extracted userId={}", extracted);
        assertThat(extracted).isEqualTo(userId);
    }

    @Test
    @DisplayName("validateToken returns true for a well-formed token")
    void should_validateToken_when_tokenIsValid() {
        log.debug("Arrange: generating a fresh valid access token");
        String token = jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), "ok@test.com", Role.CLIENT);

        log.debug("Act: calling jwtTokenProvider.validateToken");
        boolean valid = jwtTokenProvider.validateToken(token);

        log.trace("Assert: validateToken returned {}", valid);
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("validateToken throws JwtException for an expired token")
    void should_throwJwtException_when_tokenIsExpired() {
        var expiredConfig = new JwtConfig(SECRET, -1L, REFRESH_EXPIRY_MS);
        var expiredProvider = new JwtTokenProvider(expiredConfig);
        String token = expiredProvider.generateAccessToken(
                UUID.randomUUID(), "exp@test.com", Role.CLIENT);
        log.debug("Arrange: generated a token with negative expiry");

        log.debug("Act: validating expired token");
        assertThatThrownBy(() -> jwtTokenProvider.validateToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("validateToken throws JwtException for a tampered token")
    void should_throwJwtException_when_tokenIsTampered() {
        String token = jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), "t@test.com", Role.CLIENT);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        log.debug("Arrange: tampered last 4 chars of a valid token");

        log.debug("Act: validating tampered token");
        assertThatThrownBy(() -> jwtTokenProvider.validateToken(tampered))
                .isInstanceOf(JwtException.class);
    }
}
