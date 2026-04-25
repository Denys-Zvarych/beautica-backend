package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.JwtConfig;
import com.beautica.user.RefreshToken;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final JwtConfig jwtConfig;
    private final TokenGenerator tokenGenerator;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtTokenProvider jwtTokenProvider,
            PasswordEncoder passwordEncoder,
            JwtConfig jwtConfig,
            TokenGenerator tokenGenerator
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.jwtConfig = jwtConfig;
        this.tokenGenerator = tokenGenerator;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email is already registered");
        }

        var user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                Role.CLIENT,
                request.firstName(),
                request.lastName(),
                request.phoneNumber()
        );
        var savedUser = userRepository.save(user);

        return buildAuthResponse(savedUser);
    }

    @Transactional
    public AuthResponse registerIndependentMaster(RegisterIndependentMasterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email is already registered");
        }

        var user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                Role.INDEPENDENT_MASTER,
                request.firstName(),
                request.lastName(),
                request.phoneNumber()
        );
        var savedUser = userRepository.save(user);

        return buildAuthResponse(savedUser);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        var user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        if (!user.isActive()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        var storedToken = refreshTokenRepository.findByToken(tokenGenerator.hash(request.refreshToken()))
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "Refresh token not found"));

        if (storedToken.isRevoked()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Refresh token has been revoked");
        }

        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Refresh token has expired");
        }

        storedToken.revoke();
        refreshTokenRepository.save(storedToken);

        var user = userRepository.findById(storedToken.getUserId())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "User not found"));

        if (!user.isActive()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Refresh token not found");
        }

        return buildAuthResponse(user);
    }

    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole());

        String rawRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        var expiresAt = Instant.now().plusMillis(jwtConfig.refreshTokenExpiration());
        var refreshToken = new RefreshToken(tokenGenerator.hash(rawRefreshToken), user.getId(), expiresAt);
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.of(accessToken, rawRefreshToken, user.getId(), user.getEmail(), user.getRole());
    }
}
