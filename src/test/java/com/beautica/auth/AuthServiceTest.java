package com.beautica.auth;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
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
@DisplayName("AuthService — unit")
class AuthServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceTest.class);

    private static final String SECRET =
            "test-secret-that-is-long-enough-for-hs256-ok-padding-here";
    private static final long ACCESS_MS = 900_000L;
    private static final long REFRESH_MS = 604_800_000L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private TokenGenerator tokenGenerator;

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
                jwtConfig,
                tokenGenerator
        );
    }

    @Test
    @DisplayName("register returns auth response on valid input")
    void should_returnAuthResponse_when_registerSucceeds() {
        var request = new RegisterRequest(
                "new@example.com", "password123",
                "John", "Doe", null);
        log.debug("Arrange: seeding register request for email={}", request.email());

        when(tokenGenerator.hash(anyString())).thenReturn("hashed-token");
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: calling authService.register");
        var response = authService.register(request);

        log.trace("Assert: response email={}, role={}, tokenType={}",
                response.email(), response.role(), response.tokenType());
        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.role()).isEqualTo(Role.CLIENT);
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("register throws BusinessException when email is already taken")
    void should_throwBusinessException_when_emailAlreadyRegistered() {
        var request = new RegisterRequest(
                "taken@example.com", "password123",
                null, null, null);
        log.debug("Arrange: existing email={} already registered", request.email());

        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        log.debug("Act: calling authService.register expecting BusinessException");
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered");

        log.trace("Assert: userRepository.save was never invoked");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("registerIndependentMaster creates INDEPENDENT_MASTER user and returns tokens")
    void should_createIndependentMasterUserAndReturnTokens_when_validRegistration() {
        var request = new RegisterIndependentMasterRequest(
                "master@example.com", "password123",
                "Oksana", "Kovalenko", "+380671234567");
        log.debug("Arrange: seeding independent master request for email={}", request.email());

        when(tokenGenerator.hash(anyString())).thenReturn("hashed-token");
        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: calling authService.registerIndependentMaster");
        var response = authService.registerIndependentMaster(request);

        log.trace("Assert: role=INDEPENDENT_MASTER, tokens present");
        assertThat(response.role()).isEqualTo(Role.INDEPENDENT_MASTER);
        assertThat(response.email()).isEqualTo("master@example.com");
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("registerIndependentMaster throws BusinessException when email is already registered")
    void should_throw409_when_independentMasterEmailAlreadyRegistered() {
        var request = new RegisterIndependentMasterRequest(
                "taken@example.com", "password123",
                null, null, null);
        log.debug("Arrange: email={} already exists", request.email());

        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        log.debug("Act: calling authService.registerIndependentMaster expecting BusinessException");
        assertThatThrownBy(() -> authService.registerIndependentMaster(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered");

        log.trace("Assert: userRepository.save was never invoked");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("login returns auth response on valid credentials")
    void should_returnAuthResponse_when_loginSucceeds() {
        var rawPassword = "correctPass1";
        var hashed = passwordEncoder.encode(rawPassword);
        var userId = UUID.randomUUID();
        var user = buildUser(userId, "login@example.com", hashed, Role.SALON_OWNER);
        log.debug("Arrange: seeding user email=login@example.com role={}", Role.SALON_OWNER);

        when(tokenGenerator.hash(anyString())).thenReturn("hashed-token");
        when(userRepository.findByEmail("login@example.com")).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: calling authService.login");
        var response = authService.login(new LoginRequest("login@example.com", rawPassword));

        log.trace("Assert: login response email={}, role={}", response.email(), response.role());
        assertThat(response.email()).isEqualTo("login@example.com");
        assertThat(response.role()).isEqualTo(Role.SALON_OWNER);
        assertThat(response.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("login throws BusinessException on wrong password")
    void should_throwBusinessException_when_loginWithWrongPassword() {
        var hashed = passwordEncoder.encode("correctPass1");
        var user = buildUser(UUID.randomUUID(), "u@example.com", hashed, Role.CLIENT);
        log.debug("Arrange: user seeded with correct password hash");

        when(userRepository.findByEmail("u@example.com")).thenReturn(Optional.of(user));

        log.debug("Act: calling authService.login with a wrong password");
        assertThatThrownBy(() -> authService.login(new LoginRequest("u@example.com", "wrongPass")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    @DisplayName("login throws BusinessException on unknown email")
    void should_throwBusinessException_when_loginWithUnknownEmail() {
        log.debug("Arrange: userRepository returns empty for any email");
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        log.debug("Act: calling authService.login with unknown email");
        assertThatThrownBy(() -> authService.login(new LoginRequest("no@example.com", "pass")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    @DisplayName("login throws 401 when user account is inactive")
    void should_throw401_when_loginWithInactiveUser() {
        var rawPassword = "correctPass1";
        var hashed = passwordEncoder.encode(rawPassword);
        var userId = UUID.randomUUID();
        var user = buildUser(userId, "inactive@example.com", hashed, Role.CLIENT);
        ReflectionTestUtils.setField(user, "isActive", false);
        log.debug("Arrange: user email=inactive@example.com is inactive");

        when(userRepository.findByEmail("inactive@example.com")).thenReturn(Optional.of(user));

        log.debug("Act: calling authService.login with inactive user");
        assertThatThrownBy(() -> authService.login(new LoginRequest("inactive@example.com", rawPassword)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("refresh returns new token pair on valid refresh token")
    void should_returnNewTokenPair_when_refreshSucceeds() {
        var userId = UUID.randomUUID();
        var rawToken = "raw-refresh-token";
        var hashedToken = "hashed-refresh-token";
        var storedToken = new RefreshToken(hashedToken, userId, Instant.now().plusSeconds(3600));
        var user = buildUser(userId, "ref@example.com",
                passwordEncoder.encode("pass"), Role.CLIENT);
        log.debug("Arrange: generated refresh token for userId={}", userId);

        when(tokenGenerator.hash(anyString())).thenReturn("hashed-new-jwt-token");
        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(refreshTokenRepository.findByToken(hashedToken)).thenReturn(Optional.of(storedToken));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        log.debug("Act: calling authService.refresh");
        var response = authService.refresh(new RefreshRequest(rawToken));

        log.trace("Assert: new tokens issued, old token revoked={}", storedToken.isRevoked());
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(storedToken.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("refresh throws BusinessException when token has been revoked")
    void should_throwBusinessException_when_refreshTokenIsRevoked() {
        var userId = UUID.randomUUID();
        var rawToken = "raw-revoked-token";
        var hashedToken = "hashed-revoked-token";
        var storedToken = new RefreshToken(hashedToken, userId, Instant.now().plusSeconds(3600));
        storedToken.revoke();
        log.debug("Arrange: stored token for userId={} is revoked", userId);

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(refreshTokenRepository.findByToken(hashedToken)).thenReturn(Optional.of(storedToken));

        log.debug("Act: calling authService.refresh with revoked token");
        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("refresh throws BusinessException when token is not found")
    void should_throwBusinessException_when_refreshTokenNotFound() {
        var rawToken = "nonexistent";
        log.debug("Arrange: refreshTokenRepository returns empty for any token lookup");

        when(tokenGenerator.hash(rawToken)).thenReturn("some-hash");
        when(refreshTokenRepository.findByToken(anyString())).thenReturn(Optional.empty());

        log.debug("Act: calling authService.refresh with unknown token");
        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("logout deletes all refresh tokens for the user")
    void should_deleteAllUserTokens_when_logoutCalled() {
        var userId = UUID.randomUUID();
        log.debug("Arrange: userId={}", userId);

        log.debug("Act: calling authService.logout");
        authService.logout(userId);

        log.trace("Assert: deleteByUserId called with userId={}", userId);
        verify(refreshTokenRepository).deleteByUserId(userId);
    }

    private User buildUser(UUID id, String email, String passwordHash, Role role) {
        var user = new User(email, passwordHash, role, null, null, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
