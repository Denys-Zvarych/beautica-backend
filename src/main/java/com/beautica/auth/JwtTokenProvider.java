package com.beautica.auth;

import com.beautica.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey signingKey;
    private final JwtParser jwtParser;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;

    public JwtTokenProvider(JwtConfig jwtConfig) {
        this.signingKey = Keys.hmacShaKeyFor(jwtConfig.secret().getBytes(StandardCharsets.UTF_8));
        this.jwtParser = Jwts.parser().verifyWith(signingKey).build();
        this.accessTokenExpirationMs = jwtConfig.accessTokenExpiration();
        this.refreshTokenExpirationMs = jwtConfig.refreshTokenExpiration();
    }

    public String generateAccessToken(UUID userId, String email, Role role) {
        var now = new Date();
        var expiry = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public String generateRefreshToken(UUID userId) {
        var now = new Date();
        var expiry = new Date(now.getTime() + refreshTokenExpirationMs);

        return Jwts.builder()
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isAccessToken(String token) {
        return isAccessToken(parseAllClaims(token));
    }

    public UUID getUserIdFromToken(Claims claims) {
        String subject = claims.getSubject();
        if (subject == null) {
            throw new MalformedJwtException("Missing subject claim");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException ex) {
            throw new MalformedJwtException(String.format("Invalid subject claim, expected UUID: %s", subject));
        }
    }

    public UUID getUserIdFromToken(String token) {
        return getUserIdFromToken(parseAllClaims(token));
    }

    public String getEmailFromToken(Claims claims) {
        return claims.get(CLAIM_EMAIL, String.class);
    }

    public String getEmailFromToken(String token) {
        return getEmailFromToken(parseAllClaims(token));
    }

    public Role getRoleFromToken(Claims claims) {
        String roleStr = claims.get(CLAIM_ROLE, String.class);
        if (roleStr == null) {
            throw new MalformedJwtException("Missing role claim");
        }
        try {
            return Role.valueOf(roleStr);
        } catch (IllegalArgumentException ex) {
            throw new MalformedJwtException(String.format("Unknown role claim: %s", roleStr));
        }
    }

    public Role getRoleFromToken(String token) {
        return getRoleFromToken(parseAllClaims(token));
    }

    public Claims parseAllClaims(String token) {
        return jwtParser.parseSignedClaims(token).getPayload();
    }
}
