package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.RegistrationResponse;
import com.beautica.auth.dto.ResendVerificationRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.auth.dto.VerifyEmailRequest;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.EmailNotVerifiedException;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.common.exception.VerificationException;
import com.beautica.master.service.MasterService;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.RefreshToken;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.Clock;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — unit")
class AuthServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceTest.class);

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private TokenGenerator tokenGenerator;

    @Mock
    private MasterService masterService;

    @Mock
    private AuthResponseBuilder authResponseBuilder;

    @Mock
    private EmailNotificationService emailNotificationService;

    @Mock
    private Clock clock;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    // Fixed reference instant used as "now" across all time-sensitive tests.
    private static final Instant FIXED_NOW = Instant.parse("2025-06-01T12:00:00Z");

    // Mirrors AuthService.OTP_TTL — used to construct expiresAt in stubs.
    private static final Duration OTP_TTL = Duration.ofMinutes(15);

    private static final String TEST_EMAIL = "test@example.com";

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        // Inline synchronous TaskExecutor: runs the Runnable on the calling thread so
        // verify(emailNotificationService) assertions remain deterministic without CountDownLatch.
        TaskExecutor syncExecutor = Runnable::run;
        lenient().when(clock.instant()).thenReturn(FIXED_NOW);
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                tokenGenerator,
                masterService,
                authResponseBuilder,
                clock,
                emailNotificationService,
                syncExecutor
        );
    }

    @Test
    @DisplayName("register — returns RegistrationResponse without tokens when CLIENT registers")
    void should_returnRegistrationResponse_when_clientRegisters() {
        var request = new RegisterRequest(
                "new@example.com", "password123",
                SelfRegistrationRole.CLIENT, "John", "Doe", null, null);
        log.debug("Arrange: seeding register request for email={}", request.email());

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(tokenGenerator.generateOtp()).thenReturn("123456");
        when(tokenGenerator.hash("123456")).thenReturn("a".repeat(64));

        log.debug("Act: register CLIENT with valid email new@example.com");
        var response = authService.register(request);

        assertThat(response).isInstanceOf(RegistrationResponse.class);
        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.message()).contains("verification code");
        // In unit tests no active transaction exists, so scheduleVerificationEmail falls
        // through to the direct call path. In production (real transaction) it fires afterCommit.
        verify(emailNotificationService).sendVerificationEmail("new@example.com", "123456");
    }

    @Test
    @DisplayName("register returns RegistrationResponse (200) when email is already registered — enumeration suppressed")
    void should_return200WithRegistrationResponse_when_emailAlreadyRegistered() {
        var request = new RegisterRequest(
                "taken@example.com", "password123",
                SelfRegistrationRole.CLIENT, null, null, null, null);
        log.debug("Arrange: existing email={} already registered", request.email());

        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        log.debug("Act: register with already-registered email — expects silent 200 RegistrationResponse, no exception");
        var response = authService.register(request);

        assertThat(response).isInstanceOf(RegistrationResponse.class);
        assertThat(response.email()).isEqualTo("taken@example.com");
        // No new user persisted and no OTP email sent for duplicate registration
        verify(userRepository, never()).save(any());
        verify(emailNotificationService, never()).sendVerificationEmail(any(), any());
    }

    @Test
    @DisplayName("registerIndependentMaster — returns RegistrationResponse without tokens")
    void should_returnRegistrationResponseWithoutTokens_when_independentMasterRegisters() {
        var request = new RegisterIndependentMasterRequest(
                "master@example.com", "password123",
                "Oksana", "Kovalenko", "+380671234567");
        log.debug("Arrange: seeding independent master request for email={}", request.email());

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(tokenGenerator.generateOtp()).thenReturn("654321");
        when(tokenGenerator.hash("654321")).thenReturn("b".repeat(64));

        log.debug("Act: register independent master with valid email master@example.com");
        var response = authService.registerIndependentMaster(request);

        assertThat(response).isInstanceOf(RegistrationResponse.class);
        assertThat(response.email()).isEqualTo("master@example.com");
        verify(masterService).createMasterForIndependentUser(any(UUID.class));
        // In unit tests no active transaction exists, so scheduleVerificationEmail falls
        // through to the direct call path. In production (real transaction) it fires afterCommit.
        verify(emailNotificationService).sendVerificationEmail("master@example.com", "654321");
    }

    @Test
    @DisplayName("registerIndependentMaster returns RegistrationResponse (200) when email already registered — enumeration suppressed")
    void should_return200WithRegistrationResponse_when_independentMasterEmailAlreadyRegistered() {
        var request = new RegisterIndependentMasterRequest(
                "taken@example.com", "password123",
                null, null, null);
        log.debug("Arrange: email={} already exists", request.email());

        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        log.debug("Act: register independent master with already-registered email — expects silent 200 RegistrationResponse");
        var response = authService.registerIndependentMaster(request);

        assertThat(response).isInstanceOf(RegistrationResponse.class);
        assertThat(response.email()).isEqualTo("taken@example.com");
        // No new user persisted, no master created, no OTP email sent for duplicate registration
        verify(userRepository, never()).save(any());
        verify(masterService, never()).createMasterForIndependentUser(any());
        verify(emailNotificationService, never()).sendVerificationEmail(any(), any());
    }

    @Test
    @DisplayName("login returns auth response on valid credentials")
    void should_returnAuthResponse_when_loginSucceeds() {
        var rawPassword = "correctPass1";
        var hashed = passwordEncoder.encode(rawPassword);
        var userId = UUID.randomUUID();
        var user = buildUser(userId, "login@example.com", hashed, Role.SALON_OWNER);
        ReflectionTestUtils.setField(user, "emailVerified", true);
        log.debug("Arrange: seeding user email=login@example.com role={}", Role.SALON_OWNER);

        var stubResponse = AuthResponse.of("access-tok", "refresh-tok",
                userId, "login@example.com", Role.SALON_OWNER);
        when(userRepository.findByEmail("login@example.com")).thenReturn(Optional.of(user));
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(stubResponse);

        log.debug("Act: login with correct credentials for SALON_OWNER email=login@example.com");
        var response = authService.login(new LoginRequest("login@example.com", rawPassword));

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

        log.debug("Act: login with wrong password for existing email u@example.com");
        assertThatThrownBy(() -> authService.login(new LoginRequest("u@example.com", "wrongPass")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    @DisplayName("login throws BusinessException on unknown email")
    void should_throwBusinessException_when_loginWithUnknownEmail() {
        log.debug("Arrange: userRepository returns empty for any email");
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        log.debug("Act: login with email no@example.com that has no matching user");
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

        log.debug("Act: login with correct credentials for inactive account inactive@example.com");
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
        // A user who holds a refresh token must have completed email verification first.
        ReflectionTestUtils.setField(user, "emailVerified", true);
        log.debug("Arrange: generated refresh token for userId={}", userId);

        var stubResponse = AuthResponse.of("new-access-tok", "new-refresh-tok",
                userId, "ref@example.com", Role.CLIENT);
        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(refreshTokenRepository.findByToken(hashedToken)).thenReturn(Optional.of(storedToken));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(stubResponse);

        log.debug("Act: refresh token rotation with valid non-expired token for userId={}", userId);
        var response = authService.refresh(new RefreshRequest(rawToken));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(storedToken.isRevoked())
                .as("old refresh token must be revoked after rotation, userId=%s", userId)
                .isTrue();
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

        log.debug("Act: refresh with a token that has already been revoked for userId={}", userId);
        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("refresh throws BusinessException when refresh token is expired")
    void should_throwBusinessException_when_refreshTokenIsExpired() {
        var userId = UUID.randomUUID();
        var rawToken = "raw-expired";
        var hashedToken = "hashed-expired";
        // expiresAt is 1 second before FIXED_NOW — expired relative to the mocked clock
        var storedToken = new RefreshToken(hashedToken, userId, FIXED_NOW.minusSeconds(1));
        log.debug("Arrange: stored token for userId={} has expired expiresAt", userId);

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(refreshTokenRepository.findByToken(hashedToken)).thenReturn(Optional.of(storedToken));

        log.debug("Act: refresh with an expired token for userId={}", userId);
        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("refresh throws BusinessException when token is not found")
    void should_throwBusinessException_when_refreshTokenNotFound() {
        var rawToken = "nonexistent";
        log.debug("Arrange: refreshTokenRepository returns empty for any token lookup");

        when(tokenGenerator.hash(rawToken)).thenReturn("some-hash");
        when(refreshTokenRepository.findByToken(anyString())).thenReturn(Optional.empty());

        log.debug("Act: refresh with token 'nonexistent' which has no DB record");
        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("logout deletes all refresh tokens for the user")
    void should_deleteAllUserTokens_when_logoutCalled() {
        var userId = UUID.randomUUID();
        log.debug("Arrange: userId={}", userId);

        log.debug("Act: logout for userId={} — expects all refresh tokens to be deleted", userId);
        authService.logout(userId);

        verify(refreshTokenRepository).deleteByUserId(userId);
    }

    @Test
    @DisplayName("register stores businessName when SALON_OWNER registers with valid businessName")
    void should_storeBusinessName_when_salonOwnerRegisters() {
        var request = new RegisterRequest(
                "owner@example.com", "password123",
                SelfRegistrationRole.SALON_OWNER, "Olena", "Koval", null, "Beauty Studio Lviv");
        log.debug("Arrange: SALON_OWNER request with businessName={}", request.businessName());

        when(userRepository.existsByEmail("owner@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(tokenGenerator.generateOtp()).thenReturn("111111");
        when(tokenGenerator.hash("111111")).thenReturn("e".repeat(64));

        log.debug("Act: register SALON_OWNER with businessName='Beauty Studio Lviv'");
        authService.register(request);

        // Single save — OTP fields and businessName both present on the one flush
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u ->
                "Beauty Studio Lviv".equals(u.getBusinessName())));
    }

    @Test
    @DisplayName("register throws BusinessException when SALON_OWNER registers without businessName")
    void should_throwBusinessException_when_salonOwnerRegistersWithoutBusinessName() {
        var request = new RegisterRequest(
                "owner@example.com", "password123",
                SelfRegistrationRole.SALON_OWNER, "Olena", "Koval", null, null);
        log.debug("Arrange: SALON_OWNER request with null businessName");

        when(userRepository.existsByEmail("owner@example.com")).thenReturn(false);

        log.debug("Act: register SALON_OWNER with null businessName — expects validation failure");
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("businessName is required for SALON_OWNER");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("register throws BusinessException when SALON_OWNER registers with blank businessName")
    void should_throwBusinessException_when_salonOwnerRegistersWithBlankBusinessName() {
        var request = new RegisterRequest(
                "owner@example.com", "password123",
                SelfRegistrationRole.SALON_OWNER, "Olena", "Koval", null, "   ");
        log.debug("Arrange: SALON_OWNER request with whitespace-only businessName");

        when(userRepository.existsByEmail("owner@example.com")).thenReturn(false);

        log.debug("Act: register SALON_OWNER with whitespace-only businessName — expects validation failure");
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("businessName is required for SALON_OWNER");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("register does not require businessName when CLIENT registers")
    void should_notRequireBusinessName_when_clientRegisters() {
        var request = new RegisterRequest(
                "client@example.com", "password123",
                SelfRegistrationRole.CLIENT, "Ivan", "Petrenko", null, null);
        log.debug("Arrange: CLIENT request without businessName");

        when(userRepository.existsByEmail("client@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(tokenGenerator.generateOtp()).thenReturn("222222");
        when(tokenGenerator.hash("222222")).thenReturn("f".repeat(64));

        log.debug("Act: register CLIENT without businessName — should succeed");
        var response = authService.register(request);

        assertThat(response).isInstanceOf(RegistrationResponse.class);
        verify(userRepository).save(any(User.class));
    }

    @ParameterizedTest
    @EnumSource(SelfRegistrationRole.class)
    @DisplayName("register succeeds for every permitted self-registration role when prerequisites are met")
    void should_register_when_permittedRole(SelfRegistrationRole role) {
        boolean isSalonOwner = role == SelfRegistrationRole.SALON_OWNER;
        String businessName = isSalonOwner ? "Test Salon" : null;
        var request = new RegisterRequest(
                "valid@example.com", "password123",
                role, "Test", "User", null, businessName);
        log.debug("Arrange: registering with permitted role={}", role);

        when(userRepository.existsByEmail("valid@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(tokenGenerator.generateOtp()).thenReturn("333333");
        when(tokenGenerator.hash("333333")).thenReturn("9".repeat(64));

        log.debug("Act: register with permitted self-registration role={}", role);
        var response = authService.register(request);

        assertThat(response).isInstanceOf(RegistrationResponse.class);
        assertThat(response.email())
                .as("email in response must match registration email for role=%s", role)
                .isEqualTo("valid@example.com");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("register — persists OTP hash and expiry on the single save call")
    void should_persistCodeAndExpiry_when_clientRegisters() {
        var request = new RegisterRequest(
                "otp@example.com", "password123",
                SelfRegistrationRole.CLIENT, "Anna", "Koval", null, null);

        when(userRepository.existsByEmail("otp@example.com")).thenReturn(false);
        when(tokenGenerator.generateOtp()).thenReturn("042873");
        when(tokenGenerator.hash("042873")).thenReturn("c".repeat(64));
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        authService.register(request);

        // OTP fields are set before the single save — verify exactly one save with the hash present
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getVerificationCodeHash())
                .as("verificationCodeHash must be set on the single save call")
                .isEqualTo("c".repeat(64));
        assertThat(saved.getVerificationCodeExpiresAt())
                .as("verificationCodeExpiresAt must be set on the single save call")
                .isNotNull();
        // In unit tests no active transaction exists, so scheduleVerificationEmail falls
        // through to the direct call path.
        verify(emailNotificationService).sendVerificationEmail("otp@example.com", "042873");
    }

    @Test
    @DisplayName("register — sendVerificationEmail NOT called synchronously (dispatched via afterCommit)")
    void should_notCallSendVerificationEmailSynchronously_when_clientRegisters() {
        var request = new RegisterRequest(
                "verify@example.com", "password123",
                SelfRegistrationRole.CLIENT, "Daria", "Melnyk", null, null);

        when(userRepository.existsByEmail("verify@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(tokenGenerator.generateOtp()).thenReturn("708142");
        when(tokenGenerator.hash("708142")).thenReturn("d".repeat(64));

        authService.register(request);

        // In unit tests no active transaction exists, so scheduleVerificationEmail falls
        // through to the direct call path — verify the correct args are passed.
        verify(emailNotificationService).sendVerificationEmail("verify@example.com", "708142");
    }

    @Test
    @DisplayName("register — response is RegistrationResponse (no accessToken / refreshToken) when CLIENT registers")
    void should_returnRegistrationResponseWithoutTokens_when_clientRegisters() {
        var request = new RegisterRequest(
                "notoken@example.com", "password123",
                SelfRegistrationRole.CLIENT, "Maksym", "Bondar", null, null);

        when(userRepository.existsByEmail("notoken@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(tokenGenerator.generateOtp()).thenReturn("512034");
        when(tokenGenerator.hash("512034")).thenReturn("a".repeat(64));

        var response = authService.register(request);

        // RegistrationResponse is a record with exactly {message, email} — no token fields exist
        assertThat(response).isInstanceOf(RegistrationResponse.class);
        assertThat(response.email()).isEqualTo("notoken@example.com");
        assertThat(response.message()).isNotBlank();
        assertThat(response).isNotInstanceOf(AuthResponse.class);
    }

    @Test
    @DisplayName("registerIndependentMaster — master profile created and response defers token issuance")
    void should_deferTokenIssuance_when_independentMasterRegisters() {
        var request = new RegisterIndependentMasterRequest(
                "imdefer@example.com", "password123",
                "Sofiia", "Hrytsenko", "+380501112233");

        when(userRepository.existsByEmail("imdefer@example.com")).thenReturn(false);
        UUID savedId = UUID.randomUUID();
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", savedId);
            return u;
        });
        when(tokenGenerator.generateOtp()).thenReturn("019283");
        when(tokenGenerator.hash("019283")).thenReturn("f".repeat(64));

        var response = authService.registerIndependentMaster(request);

        // master profile must be created before OTP block
        verify(masterService).createMasterForIndependentUser(savedId);
        assertThat(response).isInstanceOf(RegistrationResponse.class);
        assertThat(response.email()).isEqualTo("imdefer@example.com");
        assertThat(response).isNotInstanceOf(AuthResponse.class);
    }

    // ─── verifyEmail ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("verifyEmail — returns AuthResponse and marks account verified when valid code provided")
    void should_verifyAndReturnTokens_when_validCodeProvided() {
        var userId = UUID.randomUUID();
        var email = "verify@example.com";
        var rawCode = "123456";
        var codeHash = "a".repeat(64);
        var user = buildUser(userId, email, passwordEncoder.encode("pass"), Role.CLIENT);
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "verificationCodeHash", codeHash);
        ReflectionTestUtils.setField(user, "verificationCodeExpiresAt", FIXED_NOW.plusSeconds(900));
        ReflectionTestUtils.setField(user, "verificationAttempts", (short) 0);
        log.debug("Arrange: user with valid non-expired code, emailVerified=false");

        var stubResponse = AuthResponse.of("access-tok", "refresh-tok", userId, email, Role.CLIENT);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hash(rawCode)).thenReturn(codeHash);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(stubResponse);

        log.debug("Act: verifyEmail with correct code for email={}", email);
        var response = authService.verifyEmail(new VerifyEmailRequest(email, rawCode));

        assertThat(response).isNotNull();
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getVerificationCodeHash()).isNull();
        assertThat(user.getVerificationCodeExpiresAt()).isNull();
        assertThat(user.getVerificationAttempts())
                .as("verificationAttempts must be reset to 0 on successful verification")
                .isEqualTo((short) 0);
        // Two saves: one for attempt increment, one for the verified state.
        // Spring batches them in the same transaction flush.
        verify(userRepository, org.mockito.Mockito.times(2)).save(user);
    }

    @Test
    @DisplayName("verifyEmail — throws INVALID_CODE and does NOT clear the code when wrong code provided")
    void should_throwInvalidCode_when_wrongCodeProvided() {
        var userId = UUID.randomUUID();
        var email = "verify@example.com";
        var storedHash = "a".repeat(64);
        var user = buildUser(userId, email, passwordEncoder.encode("pass"), Role.CLIENT);
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "verificationCodeHash", storedHash);
        ReflectionTestUtils.setField(user, "verificationCodeExpiresAt", FIXED_NOW.plusSeconds(900));
        ReflectionTestUtils.setField(user, "verificationAttempts", (short) 0);
        log.debug("Arrange: user with valid non-expired code, wrong code will be submitted");

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hash("000000")).thenReturn("b".repeat(64));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: verifyEmail with wrong code '000000' for email={}", email);
        assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequest(email, "000000")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.INVALID_CODE);

        // Wrong code must NOT clear the stored hash — the resend endpoint (Phase 1.6) handles renewal.
        assertThat(user.getVerificationCodeHash()).isEqualTo(storedHash);
        // Exactly one save for the attempt increment — no additional save on wrong-code path.
        verify(userRepository).save(user);
        assertThat(user.getVerificationAttempts())
                .as("verificationAttempts must be incremented on wrong code")
                .isEqualTo((short) 1);
    }

    @Test
    @DisplayName("verifyEmail — throws CODE_EXPIRED when max verification attempts exceeded")
    void should_throwCodeExpired_when_maxAttemptsExceeded() {
        var userId = UUID.randomUUID();
        var email = "locked@example.com";
        var storedHash = "a".repeat(64);
        var user = buildUser(userId, email, passwordEncoder.encode("pass"), Role.CLIENT);
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "verificationCodeHash", storedHash);
        ReflectionTestUtils.setField(user, "verificationCodeExpiresAt", FIXED_NOW.plusSeconds(900));
        ReflectionTestUtils.setField(user, "verificationAttempts", (short) 5);
        log.debug("Arrange: user with verificationAttempts=5 (at limit), valid non-expired code");

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        log.debug("Act: verifyEmail with attempts already at max — expects CODE_EXPIRED");
        assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.CODE_EXPIRED);

        // Lockout path exits before the attempt-increment save — no additional persistence.
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("verifyEmail — succeeds when verificationAttempts is exactly MAX minus one (4)")
    void should_verifyAndReturnTokens_when_attemptsAtMaxMinusOne() {
        var userId = UUID.randomUUID();
        var email = "boundary@example.com";
        var rawCode = "123456";
        var codeHash = "a".repeat(64);
        var user = buildUser(userId, email, passwordEncoder.encode("pass"), Role.CLIENT);
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "verificationCodeHash", codeHash);
        // Code is still valid — not expired
        ReflectionTestUtils.setField(user, "verificationCodeExpiresAt", FIXED_NOW.plusSeconds(900));
        // Attempt 4 = MAX - 1: still within the allowed window, must not be rejected
        ReflectionTestUtils.setField(user, "verificationAttempts", (short) 4);
        log.debug("Arrange: user at verificationAttempts=4 (boundary, MAX-1), valid non-expired code");

        var stubResponse = AuthResponse.of("access-tok", "refresh-tok", userId, email, Role.CLIENT);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hash(rawCode)).thenReturn(codeHash);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(stubResponse);

        log.debug("Act: verifyEmail at the boundary attempt (4 of 5 max) for email={}", email);
        var result = authService.verifyEmail(new VerifyEmailRequest(email, rawCode));

        assertThat(result).isNotNull();
        // Verification succeeds at the boundary: account is marked verified and counter reset
        assertThat(user.isEmailVerified())
                .as("emailVerified must be true after successful verification at attempt boundary")
                .isTrue();
        assertThat(user.getVerificationAttempts())
                .as("verificationAttempts must be reset to 0 on successful verification")
                .isEqualTo((short) 0);
    }

    @Test
    @DisplayName("verifyEmail — throws CODE_EXPIRED and clears stored code when code has expired (Phase 1.8)")
    void should_throwCodeExpired_when_codeExpired() {
        var userId = UUID.randomUUID();
        var email = "expired@example.com";
        var storedHash = "c".repeat(64);
        var user = buildUser(userId, email, passwordEncoder.encode("pass"), Role.CLIENT);
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "verificationCodeHash", storedHash);
        // Expiry 1 second before FIXED_NOW
        ReflectionTestUtils.setField(user, "verificationCodeExpiresAt", FIXED_NOW.minusSeconds(1));
        log.debug("Arrange: user with expired code (expiresAt = FIXED_NOW - 1s)");

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: verifyEmail with any code for expired-OTP email={}", email);
        assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.CODE_EXPIRED);

        // Phase 1.8: expired code is cleared on the user record (defence-in-depth).
        // The resend endpoint issues a fresh OTP that overwrites the fields; clearing
        // here prevents any window where a stale hash survives alongside a fresh one.
        assertThat(user.getVerificationCodeHash())
                .as("verificationCodeHash must be null after expiry — Phase 1.8 clearing")
                .isNull();
        assertThat(user.getVerificationCodeExpiresAt())
                .as("verificationCodeExpiresAt must be null after expiry — Phase 1.8 clearing")
                .isNull();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("verifyEmail — throws INVALID_CODE (not ALREADY_VERIFIED) when user is already verified — anti-enumeration")
    void should_throwInvalidCode_when_userAlreadyVerified() {
        var userId = UUID.randomUUID();
        var email = "done@example.com";
        var user = buildUser(userId, email, passwordEncoder.encode("pass"), Role.CLIENT);
        ReflectionTestUtils.setField(user, "emailVerified", true);
        log.debug("Arrange: user with emailVerified=true");

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        log.debug("Act: verifyEmail on already-verified account email={} — expects INVALID_CODE (anti-enumeration)", email);
        assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.INVALID_CODE);

        // Already-verified path exits before the attempt-increment save — no persistence expected.
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("verifyEmail — throws INVALID_CODE (not 404) when email is unknown — anti-enumeration")
    void should_throwInvalidCode_when_emailUnknown() {
        log.debug("Arrange: userRepository returns empty for unknown email");
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        log.debug("Act: verifyEmail with unknown email — expects INVALID_CODE, not a 404");
        assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequest("ghost@example.com", "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.INVALID_CODE);
    }

    @Test
    @DisplayName("verifyEmail — throws CODE_EXPIRED when verificationCodeHash is null (never-issued or already consumed)")
    void should_throwCodeExpired_when_verificationCodeIsNull() {
        var userId = UUID.randomUUID();
        var email = "nocode@example.com";
        var user = buildUser(userId, email, passwordEncoder.encode("pass"), Role.CLIENT);
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "verificationCodeHash", null);
        ReflectionTestUtils.setField(user, "verificationCodeExpiresAt", null);
        log.debug("Arrange: user with null verificationCodeHash and null expiresAt");

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        log.debug("Act: verifyEmail for user with no stored code email={}", email);
        assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.CODE_EXPIRED);

        verify(userRepository, never()).save(any());
    }

    // ─── resendVerification ───────────────────────────────────────────────────

    @Test
    @DisplayName("resendVerification — resends new code and returns RegistrationResponse when called after cooldown")
    void should_resendNewCode_when_requestedAfterCooldown() {
        // issuedAt is 61 seconds before FIXED_NOW → cooldown (60s) has elapsed
        Instant issuedAt = FIXED_NOW.minusSeconds(61);
        User user = buildUnverifiedUser();
        user.setVerificationCodeHash("oldhash");
        user.setVerificationCodeExpiresAt(issuedAt.plus(OTP_TTL));
        when(userRepository.findByEmailForUpdate(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(tokenGenerator.generateOtp()).thenReturn("654321");
        when(tokenGenerator.hash("654321")).thenReturn("newhash");

        RegistrationResponse resp = authService.resendVerification(new ResendVerificationRequest(TEST_EMAIL));

        assertThat(resp.email()).isEqualTo(TEST_EMAIL);
        assertThat(user.getVerificationCodeHash()).isEqualTo("newhash");
        assertThat(user.getVerificationCodeExpiresAt()).isEqualTo(FIXED_NOW.plus(OTP_TTL));
        assertThat(user.getVerificationAttempts()).isZero();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("resendVerification — throws ResendThrottledException when resend called within 60 seconds")
    void should_throw429_when_resendCalledWithin60Seconds() {
        // issuedAt is only 30 seconds ago → cooldown window has not elapsed
        Instant issuedAt = FIXED_NOW.minusSeconds(30);
        User user = buildUnverifiedUser();
        user.setVerificationCodeHash("somehash");
        user.setVerificationCodeExpiresAt(issuedAt.plus(OTP_TTL));
        when(userRepository.findByEmailForUpdate(TEST_EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.resendVerification(new ResendVerificationRequest(TEST_EMAIL)))
                .isInstanceOf(ResendThrottledException.class)
                .satisfies(ex -> assertThat(((ResendThrottledException) ex).getRetryAfterSeconds()).isGreaterThan(0));

        verify(emailNotificationService, never()).sendVerificationEmail(any(), any());
    }

    @Test
    @DisplayName("resendVerification — returns generic response for unknown email (anti-enumeration)")
    void should_returnGenericResponse_when_emailUnknown() {
        when(userRepository.findByEmailForUpdate(TEST_EMAIL)).thenReturn(Optional.empty());

        RegistrationResponse resp = authService.resendVerification(new ResendVerificationRequest(TEST_EMAIL));

        assertThat(resp.email()).isEqualTo(TEST_EMAIL);
        verify(emailNotificationService, never()).sendVerificationEmail(any(), any());
    }

    @Test
    @DisplayName("resendVerification — returns generic response for already-verified user (anti-enumeration)")
    void should_returnGenericResponse_when_alreadyVerified() {
        User user = buildUnverifiedUser();
        user.setEmailVerified(true);
        when(userRepository.findByEmailForUpdate(TEST_EMAIL)).thenReturn(Optional.of(user));

        RegistrationResponse resp = authService.resendVerification(new ResendVerificationRequest(TEST_EMAIL));

        assertThat(resp.email()).isEqualTo(TEST_EMAIL);
        verify(emailNotificationService, never()).sendVerificationEmail(any(), any());
    }

    @Test
    @DisplayName("resendVerification — extends expiry and resets attempts when resend succeeds")
    void should_extendExpiryAndResetAttempts_when_resendSucceeds() {
        Instant issuedAt = FIXED_NOW.minusSeconds(61);
        User user = buildUnverifiedUser();
        user.setVerificationCodeExpiresAt(issuedAt.plus(OTP_TTL));
        user.setVerificationAttempts((short) 3);
        when(userRepository.findByEmailForUpdate(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(tokenGenerator.generateOtp()).thenReturn("999999");
        when(tokenGenerator.hash("999999")).thenReturn("hash999");

        authService.resendVerification(new ResendVerificationRequest(TEST_EMAIL));

        assertThat(user.getVerificationCodeExpiresAt()).isEqualTo(FIXED_NOW.plus(OTP_TTL));
        assertThat(user.getVerificationAttempts()).isZero();
    }

    @Test
    @DisplayName("resendVerification — resends without throttle when user has no existing code (verificationCodeExpiresAt is null)")
    void should_resendWithoutThrottle_when_noExistingCode() {
        User user = buildUnverifiedUser();
        user.setVerificationCodeHash(null);
        user.setVerificationCodeExpiresAt(null);
        when(userRepository.findByEmailForUpdate(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(tokenGenerator.generateOtp()).thenReturn("111111");
        when(tokenGenerator.hash("111111")).thenReturn("hash111");

        RegistrationResponse resp = authService.resendVerification(new ResendVerificationRequest(TEST_EMAIL));

        assertThat(resp.email()).isEqualTo(TEST_EMAIL);
        verify(userRepository).save(user);
    }

    // ─── login gate (Phase 1.7) ───────────────────────────────────────────────

    @Test
    @DisplayName("should throw EmailNotVerifiedException when unverified user logs in with correct password")
    void should_throwEmailNotVerified_when_unverifiedUserLogsInWithCorrectPassword() {
        User user = new User(TEST_EMAIL, new BCryptPasswordEncoder(4).encode("correctpassword"),
                Role.CLIENT, null, null, null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "isActive", true);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest(TEST_EMAIL, "correctpassword")))
                .isInstanceOf(EmailNotVerifiedException.class)
                .satisfies(ex -> assertThat(((EmailNotVerifiedException) ex).getEmail()).isEqualTo(TEST_EMAIL));
    }

    @Test
    @DisplayName("should return generic 401 when unverified user logs in with wrong password (gate not reached)")
    void should_return401_when_unverifiedUserLogsInWithWrongPassword() {
        User user = new User(TEST_EMAIL, new BCryptPasswordEncoder(4).encode("correctpassword"),
                Role.CLIENT, null, null, null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "isActive", true);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest(TEST_EMAIL, "wrongpassword")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("should return AuthResponse when verified user logs in")
    void should_returnAuthResponse_when_verifiedUserLogsIn() {
        UUID userId = UUID.randomUUID();
        User user = new User(TEST_EMAIL, new BCryptPasswordEncoder(4).encode("correctpassword"),
                Role.CLIENT, null, null, null);
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "emailVerified", true);
        ReflectionTestUtils.setField(user, "isActive", true);
        var stubResponse = AuthResponse.of("access-token", "refresh-token", userId, TEST_EMAIL, Role.CLIENT);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(stubResponse);

        AuthResponse response = authService.login(new LoginRequest(TEST_EMAIL, "correctpassword"));

        assertThat(response.accessToken()).isEqualTo("access-token");
    }

    @Test
    @DisplayName("should return generic 401 for inactive account regardless of emailVerified (active check precedes gate)")
    void should_return401_when_inactiveAccountRegardlessOfVerification() {
        User user = new User(TEST_EMAIL, new BCryptPasswordEncoder(4).encode("correctpassword"),
                Role.CLIENT, null, null, null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "isActive", false);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest(TEST_EMAIL, "correctpassword")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("should echo the login email in EmailNotVerifiedException")
    void should_echoLoginEmail_when_emailNotVerified() {
        User user = new User(TEST_EMAIL, new BCryptPasswordEncoder(4).encode("pass"),
                Role.CLIENT, null, null, null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "isActive", true);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest(TEST_EMAIL, "pass")))
                .isInstanceOf(EmailNotVerifiedException.class)
                .satisfies(ex -> {
                    assertThat(((EmailNotVerifiedException) ex).getEmail()).isEqualTo(TEST_EMAIL);
                    assertThat(((EmailNotVerifiedException) ex).getMessage()).isEqualTo("Email not verified");
                });
    }

    @Test
    @DisplayName("should return 401 when refresh token belongs to unverified user")
    void should_return401_when_refreshTokenBelongsToUnverifiedUser() {
        // arrange: unverified user with a valid non-expired, non-revoked refresh token
        UUID userId = UUID.randomUUID();
        User user = new User(TEST_EMAIL, new BCryptPasswordEncoder(4).encode("pass"),
                Role.CLIENT, null, null, null);
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "isActive", true);

        String rawToken = "raw-refresh-token-unverified";
        String hashedToken = "hashed-token-unverified";
        RefreshToken refreshToken = new RefreshToken(hashedToken, userId, FIXED_NOW.plusSeconds(3600));

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(refreshTokenRepository.findByToken(hashedToken)).thenReturn(Optional.of(refreshToken));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(clock.instant()).thenReturn(FIXED_NOW);

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED));
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private User buildUnverifiedUser() {
        User user = new User(TEST_EMAIL, passwordEncoder.encode("test-password"), Role.CLIENT, null, null, null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(user, "emailVerified", false);
        ReflectionTestUtils.setField(user, "verificationAttempts", (short) 0);
        return user;
    }

    private User buildUser(UUID id, String email, String passwordHash, Role role) {
        var user = new User(email, passwordHash, role, null, null, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
