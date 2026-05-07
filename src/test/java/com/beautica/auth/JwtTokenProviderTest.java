package com.beautica.auth;

import com.beautica.config.JwtConfig;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
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
    @DisplayName("getUserIdFromToken returns correct user ID from access token")
    void should_extractUserId_when_accessTokenParsed() {
        var userId = UUID.randomUUID();
        log.debug("Arrange: expected userId={}", userId);

        log.debug("Act: generateAccessToken then getUserIdFromToken — expect userId={}", userId);
        String token = jwtTokenProvider.generateAccessToken(userId, "test@example.com", Role.CLIENT);
        UUID extracted = jwtTokenProvider.getUserIdFromToken(token);

        assertThat(extracted)
                .as("extracted userId must match the one embedded in the token, expected=%s", userId)
                .isEqualTo(userId);
    }

    @Test
    @DisplayName("getEmailFromToken returns correct email from access token")
    void should_extractEmail_when_accessTokenParsed() {
        var email = "user@beautica.com";
        log.debug("Arrange: expected email={}", email);

        log.debug("Act: generateAccessToken then getEmailFromToken — expect email={}", email);
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), email, Role.SALON_OWNER);
        String extracted = jwtTokenProvider.getEmailFromToken(token);

        assertThat(extracted)
                .as("extracted email must match the one embedded in the token, expected=%s", email)
                .isEqualTo(email);
    }

    @Test
    @DisplayName("getRoleFromToken returns correct role from access token")
    void should_extractRole_when_accessTokenParsed() {
        var role = Role.INDEPENDENT_MASTER;
        log.debug("Arrange: expected role={}", role);

        log.debug("Act: generateAccessToken then getRoleFromToken — expect role={}", role);
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "m@example.com", role);
        Role extracted = jwtTokenProvider.getRoleFromToken(token);

        assertThat(extracted)
                .as("extracted role must match the one embedded in the token, expected=%s", role)
                .isEqualTo(role);
    }

    @Test
    @DisplayName("generateRefreshToken returns a non-blank token")
    void should_generateRefreshToken_when_validUserIdProvided() {
        var userId = UUID.randomUUID();
        log.debug("Arrange: userId={}", userId);

        log.debug("Act: generateRefreshToken for userId={}", userId);
        String token = jwtTokenProvider.generateRefreshToken(userId);

        assertThat(token)
                .as("generated refresh token must not be blank")
                .isNotBlank();
    }

    @Test
    @DisplayName("getUserIdFromToken returns correct user ID from refresh token")
    void should_extractUserId_when_refreshTokenParsed() {
        var userId = UUID.randomUUID();
        log.debug("Arrange: expected userId={}", userId);

        log.debug("Act: generateRefreshToken then getUserIdFromToken — expect userId={}", userId);
        String token = jwtTokenProvider.generateRefreshToken(userId);
        UUID extracted = jwtTokenProvider.getUserIdFromToken(token);

        assertThat(extracted)
                .as("extracted userId from refresh token must match the one embedded, expected=%s", userId)
                .isEqualTo(userId);
    }

    @Test
    @DisplayName("getRoleFromToken throws JwtException when role claim is an unknown value")
    void should_throwJwtException_when_roleClaimIsUnknown() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String tokenWithBogusRole = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", "SUPER_ADMIN")
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_EXPIRY_MS))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> jwtTokenProvider.getRoleFromToken(tokenWithBogusRole))
                .as("getRoleFromToken must throw MalformedJwtException for an unrecognised role claim")
                .isInstanceOf(MalformedJwtException.class)
                .hasMessageContaining("Unknown role claim");
    }

    @Test
    @DisplayName("getUserIdFromToken throws MalformedJwtException when sub claim is null")
    void should_throwMalformedJwtException_when_subjectClaimIsNull() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String tokenWithoutSubject = Jwts.builder()
                .claim("role", Role.CLIENT.name())
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_EXPIRY_MS))
                .signWith(key)
                .compact();
        var claims = jwtTokenProvider.parseAllClaims(tokenWithoutSubject);

        assertThatThrownBy(() -> jwtTokenProvider.getUserIdFromToken(claims))
                .as("getUserIdFromToken must throw MalformedJwtException, not NullPointerException, when sub claim is absent")
                .isInstanceOf(MalformedJwtException.class)
                .hasMessage("Missing subject claim");
    }

    @Test
    @DisplayName("getUserIdFromToken throws MalformedJwtException when sub is not a UUID")
    void should_throwMalformedJwtException_when_subjectClaimIsNotUuid() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String tokenWithBogusSubject = Jwts.builder()
                .subject("not-a-uuid")
                .claim("role", Role.CLIENT.name())
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_EXPIRY_MS))
                .signWith(key)
                .compact();
        var claims = jwtTokenProvider.parseAllClaims(tokenWithBogusSubject);

        assertThatThrownBy(() -> jwtTokenProvider.getUserIdFromToken(claims))
                .as("getUserIdFromToken must throw MalformedJwtException, not IllegalArgumentException, when sub is not a UUID")
                .isInstanceOf(MalformedJwtException.class)
                .hasMessageContaining("Invalid subject claim, expected UUID");
    }

    @Test
    @DisplayName("getRoleFromToken throws MalformedJwtException when role claim is absent")
    void should_throwMalformedJwtException_when_roleClaimIsAbsent() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String tokenWithoutRole = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_EXPIRY_MS))
                .signWith(key)
                .compact();
        var claims = jwtTokenProvider.parseAllClaims(tokenWithoutRole);

        assertThatThrownBy(() -> jwtTokenProvider.getRoleFromToken(claims))
                .as("getRoleFromToken must throw MalformedJwtException, not NullPointerException, when role claim is absent")
                .isInstanceOf(MalformedJwtException.class);
    }

    @Test
    @DisplayName("parseAllClaims returns Claims from which all getters extract correct values")
    void should_extractAllClaimsCorrectly_when_parseAllClaimsCalledOnce() {
        var userId = UUID.randomUUID();
        var email = "claims@beautica.com";
        var role = Role.SALON_OWNER;
        log.debug("Arrange: userId={}, email={}, role={}", userId, email, role);

        String token = jwtTokenProvider.generateAccessToken(userId, email, role);

        log.debug("Act: parseAllClaims once, then use Claims overloads for all getters");
        var claims = jwtTokenProvider.parseAllClaims(token);

        assertThat(jwtTokenProvider.getUserIdFromToken(claims))
                .as("userId extracted from pre-parsed Claims must match original")
                .isEqualTo(userId);
        assertThat(jwtTokenProvider.getEmailFromToken(claims))
                .as("email extracted from pre-parsed Claims must match original")
                .isEqualTo(email);
        assertThat(jwtTokenProvider.getRoleFromToken(claims))
                .as("role extracted from pre-parsed Claims must match original")
                .isEqualTo(role);
        assertThat(jwtTokenProvider.isAccessToken(claims))
                .as("isAccessToken from pre-parsed Claims must return true for access token")
                .isTrue();
    }
}
