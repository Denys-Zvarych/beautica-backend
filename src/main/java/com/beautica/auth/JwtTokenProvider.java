package com.beautica.auth;

import com.beautica.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
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
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;

    public JwtTokenProvider(JwtConfig jwtConfig) {
        this.signingKey = Keys.hmacShaKeyFor(jwtConfig.secret().getBytes(StandardCharsets.UTF_8));
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

    public boolean validateToken(String token) {
        parseAllClaims(token);
        return true;
    }

    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isAccessToken(String token) {
        return isAccessToken(parseAllClaims(token));
    }

    public UUID getUserIdFromToken(Claims claims) {
        return UUID.fromString(claims.getSubject());
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
        return Role.valueOf(claims.get(CLAIM_ROLE, String.class));
    }

    public Role getRoleFromToken(String token) {
        return getRoleFromToken(parseAllClaims(token));
    }

    public Claims parseAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
