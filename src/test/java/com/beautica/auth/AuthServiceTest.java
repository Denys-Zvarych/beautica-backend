package com.beautica.auth;

import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.JwtConfig;
import com.beautica.user.RefreshToken;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET =
            "test-secret-that-is-long-enough-for-hs256-ok-padding-here";
    private static final long ACCESS_MS = 900_000L;
    private static final long REFRESH_MS = 2_592_000_000L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private JwtConfig jwtConfig;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        jwtConfig = new JwtConfig(SECRET, ACCESS_MS, REFRESH_MS);
        jwtTokenProvider = new JwtTokenProvider(jwtConfig);
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                jwtTokenProvider,
                passwordEncoder,
                jwtConfig
        );
    }

    @Test
    void should_returnAuthResponse_when_registerSucceeds() {
        var request = new RegisterRequest(
                "new@example.com", "password123",
                "John", "Doe", null);

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = authService.register(request);

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.role()).isEqualTo(Role.CLIENT);
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void should_throwBusinessException_when_emailAlreadyRegistered() {
        var request = new RegisterRequest(
                "taken@example.com", "password123",
                null, null, null);

        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered");

        verify(userRepository, never()).save(any());
    }

    @Test
    void should_returnAuthResponse_when_loginSucceeds() {
        var rawPassword = "correctPass1";
        var hashed = passwordEncoder.encode(rawPassword);
        var userId = UUID.randomUUID();
        var user = buildUser(userId, "login@example.com", hashed, Role.SALON_OWNER);

        when(userRepository.findByEmail("login@example.com")).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = authService.login(new LoginRequest("login@example.com", rawPassword));

        assertThat(response.email()).isEqualTo("login@example.com");
        assertThat(response.role()).isEqualTo(Role.SALON_OWNER);
        assertThat(response.accessToken()).isNotBlank();
    }

    @Test
    void should_throwBusinessException_when_loginWithWrongPassword() {
        var hashed = passwordEncoder.encode("correctPass1");
        var user = buildUser(UUID.randomUUID(), "u@example.com", hashed, Role.CLIENT);

        when(userRepository.findByEmail("u@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("u@example.com", "wrongPass")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void should_throwBusinessException_when_loginWithUnknownEmail() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("no@example.com", "pass")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void should_returnNewTokenPair_when_refreshSucceeds() {
        var userId = UUID.randomUUID();
        var rawToken = jwtTokenProvider.generateRefreshToken(userId);
        var storedToken = new RefreshToken(sha256Hex(rawToken), userId, Instant.now().plusSeconds(3600));
        var user = buildUser(userId, "ref@example.com",
                passwordEncoder.encode("pass"), Role.CLIENT);

        when(refreshTokenRepository.findByToken(sha256Hex(rawToken))).thenReturn(Optional.of(storedToken));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var response = authService.refresh(new RefreshRequest(rawToken));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(storedToken.isRevoked()).isTrue();
    }

    @Test
    void should_throwBusinessException_when_refreshTokenIsRevoked() {
        var userId = UUID.randomUUID();
        var rawToken = jwtTokenProvider.generateRefreshToken(userId);
        var storedToken = new RefreshToken(sha256Hex(rawToken), userId, Instant.now().plusSeconds(3600));
        storedToken.revoke();

        when(refreshTokenRepository.findByToken(sha256Hex(rawToken))).thenReturn(Optional.of(storedToken));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void should_throwBusinessException_when_refreshTokenNotFound() {
        when(refreshTokenRepository.findByToken(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("nonexistent")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void should_deleteAllUserTokens_when_logoutCalled() {
        var userId = UUID.randomUUID();

        authService.logout(userId);

        verify(refreshTokenRepository).deleteByUserId(userId);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private User buildUser(UUID id, String email, String passwordHash, Role role) {
        var user = new User(email, passwordHash, role, null, null, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
