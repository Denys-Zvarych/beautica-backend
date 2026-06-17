package com.beautica.auth.phoneotp;

import com.beautica.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;

/**
 * Mints and validates short-lived <b>guest JWTs</b> for the phone-OTP booking flow
 * (Phase 13.2). A guest token authorises a single guest-booking creation
 * (Phase 13.3 consumes the {@code sub} phone claim) and expires in 30 minutes.
 *
 * <p>Reuses the existing {@code app.jwt.secret} (no new env var) and mirrors
 * {@link com.beautica.auth.JwtTokenProvider}: HMAC-SHA256 pinned explicitly, a single
 * {@link JwtParser} built once in the constructor (never per request), and the
 * expiry instant derived from the injected {@link Clock} so tests can pin it.
 *
 * <p>{@link #validate(String)} asserts {@code type == GUEST}: a regular user
 * access/refresh token (whose {@code type} is {@code access}/{@code refresh}) is
 * rejected, as is an expired guest token. JJWT raises a {@code JwtException} for
 * signature/expiry failures, which {@code GlobalExceptionHandler} maps to a 401.
 */
@Component
public class GuestTokenProvider {

    static final String CLAIM_TYPE = "type";
    static final String TYPE_GUEST = "GUEST";
    private static final Duration GUEST_TOKEN_TTL = Duration.ofMinutes(30);

    private final SecretKey signingKey;
    private final JwtParser jwtParser;
    private final Clock clock;

    public GuestTokenProvider(JwtConfig jwtConfig, Clock clock) {
        this.signingKey = Keys.hmacShaKeyFor(jwtConfig.secret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
        // Drive JJWT's expiry/not-before checks off the same injected Clock as minting, so
        // expiry is deterministic under a pinned test Clock (the parser otherwise reads the
        // system wall clock, which would never match a Clock.fixed() in tests — §G).
        this.jwtParser = Jwts.parser()
                .verifyWith(signingKey)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    /**
     * Mints a signed guest JWT for a verified phone.
     *
     * @param phone the OTP-verified phone in E.164 format — becomes the {@code sub} claim
     * @return compact serialized JWT with {@code type = GUEST} and a 30-minute expiry
     */
    public String generate(String phone) {
        var now = Date.from(clock.instant());
        var expiry = Date.from(clock.instant().plus(GUEST_TOKEN_TTL));

        return Jwts.builder()
                .subject(phone)
                .claim(CLAIM_TYPE, TYPE_GUEST)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Parses and verifies a guest token, asserting it is genuinely a GUEST token.
     *
     * @param token the compact serialized JWT (already stripped of any {@code Bearer } prefix)
     * @return the verified phone ({@code sub} claim)
     * @throws io.jsonwebtoken.JwtException if the signature is invalid, the token is
     *                                      expired, or {@code type != GUEST} (a regular
     *                                      user token, or one with no/absent type claim)
     */
    public String validate(String token) {
        Claims claims = jwtParser.parseSignedClaims(token).getPayload();
        if (!TYPE_GUEST.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new io.jsonwebtoken.MalformedJwtException("Not a guest token");
        }
        return claims.getSubject();
    }
}
