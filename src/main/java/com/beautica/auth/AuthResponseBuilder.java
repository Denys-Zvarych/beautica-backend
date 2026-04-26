package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.JwtConfig;
import com.beautica.user.RefreshToken;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Single-responsibility component that turns a fully-persisted {@link User}
 * into a signed {@link AuthResponse}.  Extracted from {@link AuthService} and
 * {@link InviteService} to eliminate duplication (DRY) and give each service
 * a single reason to change (SRP).
 *
 * <p>Callers are responsible for persisting the user before calling this
 * builder; this class only generates tokens and saves the refresh-token row.
 */
@Component
@RequiredArgsConstructor
public class AuthResponseBuilder {

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;
    private final TokenGenerator tokenGenerator;
    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Builds a complete {@link AuthResponse} for {@code user}.
     *
     * <p>Guards against inactive accounts so callers (AuthService, InviteService)
     * are both protected consistently regardless of which code path created the user.
     *
     * @param user a fully-persisted {@link User} with a non-null {@code id}
     * @return a signed access token, a fresh refresh token, and identity fields
     * @throws BusinessException (401) if the account is not active
     */
    public AuthResponse buildAuthResponse(User user) {
        if (!user.isActive()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole());

        String rawRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        Instant expiresAt = Instant.now().plusMillis(jwtConfig.refreshTokenExpiration());
        var refreshToken = new RefreshToken(
                tokenGenerator.hash(rawRefreshToken), user.getId(), expiresAt);
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.of(
                accessToken,
                rawRefreshToken,
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getSalonId()
        );
    }
}
