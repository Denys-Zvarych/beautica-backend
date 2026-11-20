package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.RegistrationResponse;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.EmailNotVerifiedException;
import com.beautica.config.SecurityConfig;
import com.beautica.config.VerificationPolicyConfig;
import com.beautica.master.service.MasterService;
import com.beautica.notification.service.EmailNotificationService;
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
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B514 — {@link AuthService#login} by identifier (AUTH-08 / AUTH-D09) and the registration-ticket branch of
 * {@link AuthService#register} / {@link AuthService#registerIndependentMaster} (AUTH-02).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — phone-first login and ticket registration (B514)")
class AuthServiceLoginIdentifierTest {

    private static final String PASSWORD = "Str0ng!Passw0rd#";
    private static final String PHONE = "+380501234567";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.9";

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
    private EmailVerificationProcessor emailVerificationProcessor;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private AccessTokenDenylist accessTokenDenylist;
    @Mock
    private PhoneRegistrationService phoneRegistrationService;

    private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
    private LoginFailureLimiter loginFailureLimiter;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        TaskExecutor syncExecutor = Runnable::run;
        loginFailureLimiter = new LoginFailureLimiter(Clock.systemUTC());
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, tokenGenerator,
                masterService, authResponseBuilder, Clock.systemUTC(), emailNotificationService, syncExecutor,
                emailVerificationProcessor,
                new VerificationPolicyConfig(10, Duration.ofMinutes(15), Duration.ofHours(24), Duration.ofSeconds(60)),
                jwtTokenProvider, accessTokenDenylist, phoneRegistrationService, loginFailureLimiter);
    }

    private User phoneUser() {
        return User.registerWithVerifiedPhone(PHONE, passwordEncoder.encode(PASSWORD), Role.CLIENT, "Olena", null,
                null, Instant.parse("2026-11-17T10:00:00Z"));
    }

    private static AuthResponse tokens() {
        return AuthResponse.of("access", "refresh", UUID.randomUUID(), null, Role.CLIENT);
    }

    @Test
    @DisplayName("should_signIn_when_identifierIsVerifiedPhoneInNationalForm")
    void should_signIn_when_identifierIsVerifiedPhoneInNationalForm() {
        User user = phoneUser();
        when(userRepository.findByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(authResponseBuilder.buildAuthResponse(user)).thenReturn(tokens());

        AuthResponse response = authService.login(new LoginRequest(null, PASSWORD, "050 123 45 67"), CLIENT_IP);

        assertThat(response.accessToken()).isEqualTo("access");
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("should_lookUpEmail_when_identifierContainsAt")
    void should_lookUpEmail_when_identifierContainsAt() {
        when(userRepository.findByEmail("olena@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest(null, PASSWORD, " Olena@Example.com "), CLIENT_IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(userRepository, never()).findByVerifiedPhone(anyString());
    }

    @Test
    @DisplayName("should_return401_when_phoneNotVerified (lookup matches verified phones only)")
    void should_return401_when_phoneNotVerified() {
        when(userRepository.findByVerifiedPhone(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest(null, PASSWORD, PHONE), CLIENT_IP))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getMessage()).isEqualTo("Invalid email or password");
                });
    }

    @Test
    @DisplayName("should_return401WithoutLookup_when_identifierIsNeitherEmailNorUaPhone")
    void should_return401WithoutLookup_when_identifierIsNeitherEmailNorUaPhone() {
        assertThatThrownBy(() -> authService.login(new LoginRequest(null, PASSWORD, "olena"), CLIENT_IP))
                .isInstanceOf(BusinessException.class);
        verify(userRepository, never()).findByVerifiedPhone(anyString());
        verify(userRepository, never()).findByEmail(anyString());
    }

    private void failTenTimesFrom(String clientIp) {
        for (int i = 0; i < LoginFailureLimiter.MAX_FAILURES; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(null, "wrong-password", PHONE), clientIp))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("a correct password is still refused from the IP that hit the failure cap")
    void should_return401EvenForCorrectPassword_when_identifierLockedFromSameIp() {
        User user = phoneUser();
        when(userRepository.findByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        failTenTimesFrom(CLIENT_IP);

        assertThatThrownBy(() -> authService.login(new LoginRequest(null, PASSWORD, PHONE), CLIENT_IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(authResponseBuilder, never()).buildAuthResponse(any());
    }

    @Test
    @DisplayName("a locked identifier + IP still pays one BCrypt comparison, so a fast 401 reveals no account")
    void should_runBcryptAgainstDummyHash_when_identifierLockedFromSameIp() {
        User user = phoneUser();
        when(userRepository.findByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        failTenTimesFrom(CLIENT_IP);
        clearInvocations(passwordEncoder);

        assertThatThrownBy(() -> authService.login(new LoginRequest(null, PASSWORD, PHONE), CLIENT_IP))
                .isInstanceOf(BusinessException.class);

        verify(passwordEncoder).matches(PASSWORD, AuthService.DUMMY_PASSWORD_HASH);
    }

    @Test
    @DisplayName("an attacker locking a known phone from one IP cannot lock its owner out from another IP")
    void should_signIn_when_identifierLockedFromAnotherIp() {
        User user = phoneUser();
        when(userRepository.findByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(authResponseBuilder.buildAuthResponse(user)).thenReturn(tokens());
        failTenTimesFrom(CLIENT_IP);

        AuthResponse response = authService.login(new LoginRequest(null, PASSWORD, PHONE), OTHER_IP);

        assertThat(response.accessToken()).isEqualTo("access");
    }

    @Test
    @DisplayName("an unknown identifier still pays one BCrypt comparison, so timing reveals no account")
    void should_runBcryptAgainstDummyHash_when_identifierUnknown() {
        when(userRepository.findByVerifiedPhone(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest(null, PASSWORD, PHONE), CLIENT_IP))
                .isInstanceOf(BusinessException.class);

        verify(passwordEncoder).matches(PASSWORD, AuthService.DUMMY_PASSWORD_HASH);
    }

    @Test
    @DisplayName("the dummy hash uses the production BCrypt cost factor, so both paths take equal time")
    void should_matchProductionCostFactor_when_dummyHashCompared() {
        String expectedPrefix = String.format("$2a$%02d$", SecurityConfig.BCRYPT_STRENGTH);

        assertThat(AuthService.DUMMY_PASSWORD_HASH).startsWith(expectedPrefix);
    }

    @Test
    @DisplayName("failed logins for identifiers that name no account are not tracked by the limiter")
    void should_notTrackFailure_when_identifierUnknown() {
        when(userRepository.findByVerifiedPhone(PHONE)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest(null, PASSWORD, PHONE), CLIENT_IP))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> authService.login(new LoginRequest(null, PASSWORD, "nobody@example.com"),
                CLIENT_IP)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> authService.login(new LoginRequest(null, PASSWORD, "garbage"), CLIENT_IP))
                .isInstanceOf(BusinessException.class);

        assertThat(loginFailureLimiter.trackedCount()).isZero();
    }

    @Test
    @DisplayName("should_throwEmailNotVerified_when_legacyAccountHasNoVerifiedIdentity")
    void should_throwEmailNotVerified_when_legacyAccountHasNoVerifiedIdentity() {
        User legacy = new User("legacy@example.com", passwordEncoder.encode(PASSWORD), Role.CLIENT, "A", "B",
                "+380501234567");
        when(userRepository.findByEmail("legacy@example.com")).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() -> authService.login(new LoginRequest("legacy@example.com", PASSWORD), CLIENT_IP))
                .isInstanceOf(EmailNotVerifiedException.class);
    }

    @Test
    @DisplayName("should_delegateToPhoneRegistrationAndReturnTokens_when_registerWithTicket")
    void should_delegateToPhoneRegistrationAndReturnTokens_when_registerWithTicket() {
        AuthResponse auth = tokens();
        when(phoneRegistrationService.register(any())).thenReturn(auth);
        RegisterRequest request = new RegisterRequest(null, PASSWORD, SelfRegistrationRole.SALON_OWNER, "Olena",
                null, null, "Studio", null, null, null, "ticket");

        RegistrationResponse response = authService.register(request);

        ArgumentCaptor<PhoneRegistrationService.PhoneAccount> account =
                ArgumentCaptor.forClass(PhoneRegistrationService.PhoneAccount.class);
        verify(phoneRegistrationService).register(account.capture());
        assertThat(response.auth()).isSameAs(auth);
        assertThat(response.email()).isNull();
        assertThat(account.getValue().role()).isEqualTo(Role.SALON_OWNER);
        assertThat(account.getValue().businessName()).isEqualTo("Studio");
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    @DisplayName("should_requireBusinessName_when_ownerRegistersWithTicket")
    void should_requireBusinessName_when_ownerRegistersWithTicket() {
        RegisterRequest request = new RegisterRequest(null, PASSWORD, SelfRegistrationRole.SALON_OWNER, "Olena",
                null, null, " ", null, null, null, "ticket");

        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(BusinessException.class);
        verify(phoneRegistrationService, never()).register(any());
    }

    @Test
    @DisplayName("should_registerIndependentMasterByPhone_when_ticketPresent")
    void should_registerIndependentMasterByPhone_when_ticketPresent() {
        when(phoneRegistrationService.register(any())).thenReturn(tokens());
        RegisterIndependentMasterRequest request = new RegisterIndependentMasterRequest(null, PASSWORD, "Olena",
                null, null, null, null, null, "ticket");

        RegistrationResponse response = authService.registerIndependentMaster(request);

        ArgumentCaptor<PhoneRegistrationService.PhoneAccount> account =
                ArgumentCaptor.forClass(PhoneRegistrationService.PhoneAccount.class);
        verify(phoneRegistrationService).register(account.capture());
        assertThat(response.auth()).isNotNull();
        assertThat(account.getValue().role()).isEqualTo(Role.INDEPENDENT_MASTER);
    }

    // ── Phone-first hardening — B11 lock-before-lookup, AUTH-D27 (e) /64 lockout, B12 hash before tx ──

    @Test
    @DisplayName("should_skipAccountLookup_when_identifierAndClientLocked")
    void should_skipAccountLookup_when_identifierAndClientLocked() {
        User user = phoneUser();
        when(userRepository.findByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        failTenTimesFrom(CLIENT_IP);
        clearInvocations(userRepository);

        assertThatThrownBy(() -> authService.login(new LoginRequest(null, PASSWORD, "050 123 45 67"), CLIENT_IP))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).findByVerifiedPhone(anyString());
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("should_shareLock_when_twoAddressesInSameIpv6Slash64")
    void should_shareLock_when_twoAddressesInSameIpv6Slash64() {
        User user = phoneUser();
        when(userRepository.findByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        failTenTimesFrom(com.beautica.auth.filter.ClientIpResolver.prefixKey("2001:db8:1:2::1"));

        String rotatedAddress = com.beautica.auth.filter.ClientIpResolver.prefixKey("2001:db8:1:2:abcd::ffff");

        assertThatThrownBy(() -> authService.login(new LoginRequest(null, PASSWORD, PHONE), rotatedAddress))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("should_signIn_when_lockedSlash64IsAnotherPrefix")
    void should_signIn_when_lockedSlash64IsAnotherPrefix() {
        User user = phoneUser();
        when(userRepository.findByVerifiedPhone(PHONE)).thenReturn(Optional.of(user));
        when(authResponseBuilder.buildAuthResponse(user)).thenReturn(tokens());
        failTenTimesFrom(com.beautica.auth.filter.ClientIpResolver.prefixKey("2001:db8:1:2::1"));

        AuthResponse response = authService.login(new LoginRequest(null, PASSWORD, PHONE),
                com.beautica.auth.filter.ClientIpResolver.prefixKey("2001:db8:1:3::1"));

        assertThat(response.accessToken()).isEqualTo("access");
    }

    @Test
    @DisplayName("should_handPhoneRegistrationTheHashNotThePassword_when_registerWithTicket")
    void should_handPhoneRegistrationTheHashNotThePassword_when_registerWithTicket() {
        when(phoneRegistrationService.register(any())).thenReturn(tokens());
        RegisterRequest request = new RegisterRequest(null, PASSWORD, SelfRegistrationRole.CLIENT, "Olena",
                null, null, null, null, null, null, "ticket");

        authService.register(request);

        ArgumentCaptor<PhoneRegistrationService.PhoneAccount> account =
                ArgumentCaptor.forClass(PhoneRegistrationService.PhoneAccount.class);
        verify(phoneRegistrationService).register(account.capture());
        assertThat(account.getValue().passwordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, account.getValue().passwordHash())).isTrue();
    }
}
