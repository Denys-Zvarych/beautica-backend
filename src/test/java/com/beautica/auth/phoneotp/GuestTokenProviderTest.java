package com.beautica.auth.phoneotp;

import com.beautica.config.JwtConfig;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link GuestTokenProvider}: mint/validate round-trip, the {@code GUEST}
 * type assertion, and rejection of regular user tokens and expired guest tokens. Expiry
 * is pinned via an injected {@link Clock} (Anti-Bug Playbook §G).
 */
@DisplayName("GuestTokenProvider — unit")
class GuestTokenProviderTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long-xx";
    private static final String PHONE = "+380671234567";
    private static final Instant FIXED_NOW = Instant.parse("2026-06-17T10:00:00Z");

    private final JwtConfig jwtConfig = new JwtConfig(SECRET, 900_000L, 1_209_600_000L);

    private GuestTokenProvider providerAt(Instant now) {
        return new GuestTokenProvider(jwtConfig, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should_returnPhone_when_validatingFreshlyMintedToken")
    void should_returnPhone_when_validatingFreshlyMintedToken() {
        GuestTokenProvider provider = providerAt(FIXED_NOW);

        String token = provider.generate(PHONE);
        String phone = provider.validate(token);

        assertThat(phone).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("should_setGuestTypeClaim_when_generatingToken")
    void should_setGuestTypeClaim_when_generatingToken() {
        GuestTokenProvider provider = providerAt(FIXED_NOW);
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        String token = provider.generate(PHONE);

        var claims = Jwts.parser().verifyWith(key)
                .clock(() -> Date.from(FIXED_NOW))
                .build().parseSignedClaims(token).getPayload();
        assertThat(claims.get(GuestTokenProvider.CLAIM_TYPE, String.class)).isEqualTo("GUEST");
        assertThat(claims.getSubject()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("should_expireWithin30Minutes_when_generatingToken")
    void should_expireWithin30Minutes_when_generatingToken() {
        GuestTokenProvider provider = providerAt(FIXED_NOW);
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        String token = provider.generate(PHONE);

        var claims = Jwts.parser().verifyWith(key)
                .clock(() -> Date.from(FIXED_NOW))
                .build().parseSignedClaims(token).getPayload();
        assertThat(claims.getExpiration().toInstant())
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("should_rejectRegularUserToken_when_typeIsNotGuest")
    void should_rejectRegularUserToken_when_typeIsNotGuest() {
        GuestTokenProvider provider = providerAt(FIXED_NOW);
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        // A regular access token: same signing key, but type = "access" (not GUEST).
        String accessToken = Jwts.builder()
                .subject("11111111-1111-1111-1111-111111111111")
                .claim("type", "access")
                .claim("role", "CLIENT")
                .issuedAt(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(Duration.ofMinutes(15))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> provider.validate(accessToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("should_rejectToken_when_typeClaimAbsent")
    void should_rejectToken_when_typeClaimAbsent() {
        GuestTokenProvider provider = providerAt(FIXED_NOW);
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String noTypeToken = Jwts.builder()
                .subject(PHONE)
                .issuedAt(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(Duration.ofMinutes(15))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> provider.validate(noTypeToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("should_rejectExpiredGuestToken_when_validatedAfterExpiry")
    void should_rejectExpiredGuestToken_when_validatedAfterExpiry() {
        // Mint at FIXED_NOW (30-min TTL), validate 31 minutes later.
        String token = providerAt(FIXED_NOW).generate(PHONE);
        GuestTokenProvider laterProvider = providerAt(FIXED_NOW.plus(Duration.ofMinutes(31)));

        assertThatThrownBy(() -> laterProvider.validate(token))
                .isInstanceOf(JwtException.class);
    }
}
