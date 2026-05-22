package com.beautica.auth;

import com.beautica.auth.dto.ForgotPasswordRequest;
import com.beautica.auth.dto.ResetPasswordRequest;
import com.beautica.common.exception.BusinessException;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.PasswordResetToken;
import com.beautica.user.PasswordResetTokenRepository;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetService — unit")
class PasswordResetServiceTest {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetServiceTest.class);

    private static final Instant FIXED_NOW = Instant.parse("2025-06-01T12:00:00Z");
    private static final String FRONTEND_BASE_URL = "https://app.beautica.ua";
    private static final long TOKEN_EXPIRY_HOURS = 1L;

    private static final String RAW_TOKEN = "raw-token-abc123";
    private static final String HASHED_TOKEN = "hashed-token-hex64-" + "a".repeat(44);

    private static final String TEST_EMAIL = "user@example.com";
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private TokenGenerator tokenGenerator;

    @Mock
    private EmailNotificationService emailNotificationService;

    private PasswordEncoder passwordEncoder;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        // Synchronous inline executor so verify() calls are deterministic in unit tests.
        TaskExecutor syncExecutor = Runnable::run;
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

        service = new PasswordResetService(
                passwordResetTokenRepository,
                userRepository,
                refreshTokenRepository,
                tokenGenerator,
                passwordEncoder,
                emailNotificationService,
                syncExecutor,
                fixedClock,
                FRONTEND_BASE_URL,
                TOKEN_EXPIRY_HOURS
        );
    }

    // =========================================================================
    // requestReset — happy path
    // =========================================================================

    @Test
    @DisplayName("requestReset — persists hashed token and schedules email for active+verified user")
    void should_persistHashedTokenAndScheduleEmail_when_userIsActiveAndVerified() {
        log.debug("Arrange: active+verified user with email={}", TEST_EMAIL);
        User user = activeVerifiedUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);

        log.debug("Act: requestReset for valid user");
        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(tokenCaptor.capture());
        PasswordResetToken saved = tokenCaptor.getValue();
        assertThat(saved.getToken()).isEqualTo(HASHED_TOKEN);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.isUsed()).isFalse();
        assertThat(saved.getExpiresAt()).isEqualTo(FIXED_NOW.plusSeconds(3600));

        // Assert the FULL emitted link shape, not just that it contains the token:
        // rooted at FRONTEND_BASE_URL, https scheme, exact deep-link path + token query param.
        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationService).sendPasswordResetEmail(eq(TEST_EMAIL), linkCaptor.capture());
        assertThat(linkCaptor.getValue())
                .startsWith("https://")
                .startsWith(FRONTEND_BASE_URL + "/reset-password?token=")
                .endsWith(RAW_TOKEN);
    }

    @Test
    @DisplayName("requestReset — stores hashed token, never the raw token (hash-at-rest invariant)")
    void should_storeHashedToken_neverRawToken() {
        log.debug("Arrange: verify token hashing at rest");
        User user = activeVerifiedUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(captor.capture());

        assertThat(captor.getValue().getToken())
                .as("stored token must be the hash, never the raw value")
                .isEqualTo(HASHED_TOKEN)
                .isNotEqualTo(RAW_TOKEN);
    }

    @Test
    @DisplayName("requestReset — supersedes prior unused tokens before issuing a new one")
    void should_supersedePriorTokens_before_issuingNew() {
        log.debug("Arrange: user with outstanding reset token");
        User user = activeVerifiedUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        verify(passwordResetTokenRepository).markAllUsedByUserId(USER_ID);
    }

    // =========================================================================
    // requestReset — enumeration protection: no email, no token, no exception
    // =========================================================================

    @Test
    @DisplayName("requestReset — silent no-op when email is unknown (enumeration protection)")
    void should_returnSilently_when_emailUnknown() {
        log.debug("Arrange: unknown email — repository returns empty");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());

        log.debug("Act: requestReset for unknown email — must not throw");
        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        verify(passwordResetTokenRepository, never()).save(any());
        verify(emailNotificationService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("requestReset — silent no-op when user's email is unverified")
    void should_returnSilently_when_userUnverified() {
        log.debug("Arrange: user exists but email is not verified");
        User user = unverifiedUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        verify(passwordResetTokenRepository, never()).save(any());
        verify(emailNotificationService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("requestReset — silent no-op when user account is inactive")
    void should_returnSilently_when_userInactive() {
        log.debug("Arrange: user exists, verified, but inactive");
        User user = inactiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        verify(passwordResetTokenRepository, never()).save(any());
        verify(emailNotificationService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    // =========================================================================
    // requestReset — timing-channel decoy work on no-op branches
    //
    // performDecoyWork() is an anti-enumeration defense: it must run on EVERY
    // no-op branch so per-request crypto cost is symmetric with the real path.
    // These tests pin that behaviour so the decoy cannot be silently deleted
    // without a red test.
    // =========================================================================

    @Test
    @DisplayName("requestReset — performs decoy crypto work on unknown-email no-op (timing defense)")
    void should_performDecoyWork_when_emailUnknown() {
        log.debug("Arrange: unknown email — decoy crypto must still run");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        // Decoy work = generate a throwaway token then hash it.
        verify(tokenGenerator).generateToken();
        verify(tokenGenerator).hash(RAW_TOKEN);
        // ...but no real token persisted and no email sent.
        verify(passwordResetTokenRepository, never()).save(any());
        verify(emailNotificationService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("requestReset — performs decoy crypto work on unverified-user no-op (timing defense)")
    void should_performDecoyWork_when_userUnverified() {
        log.debug("Arrange: unverified user — decoy crypto must still run");
        User user = unverifiedUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        verify(tokenGenerator).generateToken();
        verify(tokenGenerator).hash(RAW_TOKEN);
        verify(passwordResetTokenRepository, never()).save(any());
        verify(emailNotificationService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    // =========================================================================
    // requestReset — buildResetLink scheme guard
    // =========================================================================

    @Test
    @DisplayName("requestReset — throws IllegalState when frontendBaseUrl scheme is unsafe (SchemeGuard)")
    void should_throwIllegalState_when_frontendBaseUrlSchemeUnsafe() {
        log.debug("Arrange: service built with an unsafe (non-https / non-localhost) base URL");
        PasswordResetService unsafeService = new PasswordResetService(
                passwordResetTokenRepository,
                userRepository,
                refreshTokenRepository,
                tokenGenerator,
                passwordEncoder,
                emailNotificationService,
                Runnable::run,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                "http://evil.example",
                TOKEN_EXPIRY_HOURS
        );
        User user = activeVerifiedUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);

        assertThatThrownBy(() -> unsafeService.requestReset(new ForgotPasswordRequest(TEST_EMAIL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.frontend.base-url");

        // The unsafe scheme is caught before any email is dispatched.
        verify(emailNotificationService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    // =========================================================================
    // resetPassword — happy path
    // =========================================================================

    @Test
    @DisplayName("resetPassword — updates password hash and marks token used on valid token")
    void should_resetPassword_when_validTokenAndPassword() {
        log.debug("Arrange: valid non-expired non-used token and active user");
        PasswordResetToken token = validToken();
        User user = activeVerifiedUser();
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(passwordResetTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        log.debug("Act: resetPassword with new valid password");
        service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewValidPass1!"));

        // Password hash must have changed and BCrypt must accept the new value.
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        String savedHash = userCaptor.getValue().getPasswordHash();
        assertThat(passwordEncoder.matches("NewValidPass1!", savedHash)).isTrue();

        // Token must be marked used.
        assertThat(token.isUsed()).isTrue();
        verify(passwordResetTokenRepository).save(token);
    }

    @Test
    @DisplayName("resetPassword — revokes all refresh tokens (global logout) on success")
    void should_revokeAllRefreshTokens_when_resetSucceeds() {
        log.debug("Arrange: valid token, user with existing sessions");
        PasswordResetToken token = validToken();
        User user = activeVerifiedUser();
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(passwordResetTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewValidPass1!"));

        verify(refreshTokenRepository).deleteByUserId(USER_ID);
    }

    @Test
    @DisplayName("resetPassword — invalidates all other outstanding reset tokens after success (defence in depth)")
    void should_supersedeOtherResetTokens_when_resetSucceeds() {
        log.debug("Arrange: user has multiple outstanding reset tokens");
        PasswordResetToken token = validToken();
        User user = activeVerifiedUser();
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(passwordResetTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewValidPass1!"));

        verify(passwordResetTokenRepository).markAllUsedByUserId(USER_ID);
    }

    @Test
    @DisplayName("resetPassword — issues no new session and revokes existing ones (no auto-login)")
    void should_notReturnTokens_when_resetSucceeds() {
        log.debug("Arrange: valid token");
        PasswordResetToken token = validToken();
        User user = activeVerifiedUser();
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(passwordResetTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        // resetPassword returns void — there is no AuthResponse to inspect. The
        // observable "no auto-login" guarantee is that the flow REVOKES sessions
        // (global logout) and never persists a NEW refresh token for the user.
        service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewValidPass1!"));

        // Existing sessions are revoked — the user is logged out, not logged in.
        verify(refreshTokenRepository).deleteByUserId(USER_ID);
        // No new session token is minted as a side-effect of the reset (no auto-login).
        verify(refreshTokenRepository, never()).save(any());
    }

    // =========================================================================
    // resetPassword — oracle protection (identical 400 for all failure modes)
    // =========================================================================

    @Test
    @DisplayName("resetPassword — throws generic 400 when token is unknown (not in DB)")
    void should_throwGeneric400_when_tokenUnknown() {
        log.debug("Arrange: unknown token — repository returns empty");
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(passwordResetTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewValidPass1!")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getMessage()).isEqualTo("Invalid or expired reset token");
                });
    }

    @Test
    @DisplayName("resetPassword — throws generic 400 when token has already been used")
    void should_throwGeneric400_when_tokenAlreadyUsed() {
        log.debug("Arrange: token exists but is_used=true");
        PasswordResetToken usedToken = usedToken();
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(passwordResetTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(usedToken));

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewValidPass1!")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getMessage()).isEqualTo("Invalid or expired reset token");
                });
    }

    @Test
    @DisplayName("resetPassword — throws generic 400 when token is expired")
    void should_throwGeneric400_when_tokenExpired() {
        log.debug("Arrange: token exists, is_used=false, but expires_at is in the past");
        PasswordResetToken expiredToken = expiredToken();
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(passwordResetTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewValidPass1!")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    // Message must be identical to the used-token and unknown-token cases (no oracle).
                    assertThat(be.getMessage()).isEqualTo("Invalid or expired reset token");
                });
    }

    @Test
    @DisplayName("resetPassword — 400 message is byte-identical for used, expired, and unknown token (no oracle)")
    void should_returnIdenticalMessage_for_usedExpiredAndUnknownToken() {
        // Used
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(passwordResetTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(usedToken()));
        String usedMsg = captureExceptionMessage(() ->
                service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewValidPass1!")));

        // Expired
        when(passwordResetTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(expiredToken()));
        String expiredMsg = captureExceptionMessage(() ->
                service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewValidPass1!")));

        // Unknown
        when(passwordResetTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.empty());
        String unknownMsg = captureExceptionMessage(() ->
                service.resetPassword(new ResetPasswordRequest(RAW_TOKEN, "NewValidPass1!")));

        assertThat(usedMsg).isEqualTo(expiredMsg).isEqualTo(unknownMsg);
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    private User activeVerifiedUser() {
        var user = new User(TEST_EMAIL, "oldHashedPw", com.beautica.auth.Role.CLIENT, "Anna", "Test", null);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "isActive", true);
        ReflectionTestUtils.setField(user, "emailVerified", true);
        return user;
    }

    private User unverifiedUser() {
        var user = new User(TEST_EMAIL, "hash", com.beautica.auth.Role.CLIENT, "Anna", "Test", null);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "isActive", true);
        ReflectionTestUtils.setField(user, "emailVerified", false);
        return user;
    }

    private User inactiveUser() {
        var user = new User(TEST_EMAIL, "hash", com.beautica.auth.Role.CLIENT, "Anna", "Test", null);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "isActive", false);
        ReflectionTestUtils.setField(user, "emailVerified", true);
        return user;
    }

    private PasswordResetToken validToken() {
        // expires 2 hours from FIXED_NOW — well within the 1 h TTL test window
        return new PasswordResetToken(HASHED_TOKEN, USER_ID, FIXED_NOW.plusSeconds(7200));
    }

    private PasswordResetToken usedToken() {
        var t = new PasswordResetToken(HASHED_TOKEN, USER_ID, FIXED_NOW.plusSeconds(3600));
        t.markUsed();
        return t;
    }

    private PasswordResetToken expiredToken() {
        // expires 1 second before FIXED_NOW
        return new PasswordResetToken(HASHED_TOKEN, USER_ID, FIXED_NOW.minusSeconds(1));
    }

    private String captureExceptionMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected BusinessException was not thrown");
        } catch (BusinessException ex) {
            return ex.getMessage();
        }
    }
}
