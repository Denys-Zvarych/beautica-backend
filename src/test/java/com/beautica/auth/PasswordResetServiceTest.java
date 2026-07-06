package com.beautica.auth;

import com.beautica.auth.dto.ForgotPasswordRequest;
import com.beautica.auth.dto.ResetPasswordRequest;
import com.beautica.auth.dto.VerifyPasswordResetOtpRequest;
import com.beautica.auth.dto.VerifyPasswordResetOtpResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.config.PasswordResetOtpPolicyConfig;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.PasswordResetTicket;
import com.beautica.user.PasswordResetTicketRepository;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetService — unit")
class PasswordResetServiceTest {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetServiceTest.class);

    private static final Instant FIXED_NOW = Instant.parse("2025-06-01T12:00:00Z");

    private static final String RAW_TICKET = "raw-ticket-abc123";
    private static final String HASHED_TICKET = "hashed-ticket-hex64-" + "a".repeat(44);

    private static final String TEST_EMAIL = "user@example.com";
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private PasswordResetTicketRepository passwordResetTicketRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private TokenGenerator tokenGenerator;

    @Mock
    private EmailNotificationService emailNotificationService;

    @Mock
    private TokensValidAfterCache tokensValidAfterCache;

    @Mock
    private PasswordResetOtpProcessor passwordResetOtpProcessor;

    private PasswordEncoder passwordEncoder;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        TaskExecutor syncExecutor = Runnable::run;
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        var policy = new PasswordResetOtpPolicyConfig(
                Duration.ofMinutes(15), 5, 10, Duration.ofMinutes(15),
                Duration.ofSeconds(60), Duration.ofMinutes(10));

        service = new PasswordResetService(
                passwordResetTicketRepository,
                userRepository,
                refreshTokenRepository,
                tokenGenerator,
                passwordEncoder,
                emailNotificationService,
                syncExecutor,
                tokensValidAfterCache,
                passwordResetOtpProcessor,
                policy,
                fixedClock
        );
    }

    // =========================================================================
    // requestReset — happy path (mints + sends OTP)
    // =========================================================================

    @Test
    @DisplayName("requestReset — mints and sends OTP for active+verified user")
    void should_mintAndSendOtp_when_userIsActiveAndVerified() {
        User preRead = activeVerifiedUser();
        User locked = activeVerifiedUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(preRead));
        when(userRepository.findByEmailForUpdate(TEST_EMAIL)).thenReturn(Optional.of(locked));
        when(passwordResetOtpProcessor.isLocked(locked)).thenReturn(false);
        when(tokenGenerator.generateOtp()).thenReturn("123456");
        when(tokenGenerator.hashOtp("123456")).thenReturn("hashedOtp");

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        assertThat(locked.getPasswordResetCodeHash()).isEqualTo("hashedOtp");
        assertThat(locked.getPasswordResetCodeExpiresAt()).isEqualTo(FIXED_NOW.plusSeconds(900));
        assertThat(locked.getPasswordResetAttempts()).isZero();
        verify(emailNotificationService).sendPasswordResetOtpEmail(eq(locked), eq("123456"));
    }

    @Test
    @DisplayName("requestReset — silent no-op when email is unknown (enumeration protection; decoy work still runs)")
    void should_returnSilently_when_emailUnknown() {
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());
        when(tokenGenerator.generateOtp()).thenReturn("000000");

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        // Same decoy-work timing defense as the unverified/inactive cases: performDecoyWork
        // calls the NON-locking findByEmail (never findByEmailForUpdate) as a read-only,
        // discarded-result timing mirror even when the email doesn't exist at all — see
        // PasswordResetService#performDecoyWork. Called twice: once for the initial pre-read,
        // once inside the decoy work.
        verify(userRepository, times(2)).findByEmail(TEST_EMAIL);
        verify(userRepository, never()).findByEmailForUpdate(any());
        verifyNoInteractions(emailNotificationService);
        verify(passwordResetOtpProcessor, never()).isLocked(any());
    }

    @Test
    @DisplayName("requestReset — silent no-op when user's email is unverified (decoy work still runs, timing defense)")
    void should_returnSilently_when_userUnverified() {
        User user = unverifiedUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(tokenGenerator.generateOtp()).thenReturn("000000");

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        // performDecoyWork deliberately mirrors the eligible path's lock-escalation lookup
        // for timing-attack defense using the NON-locking findByEmail — it's a read-only,
        // discarded-result call, not a real OTP write, so it's expected here (see
        // PasswordResetService#performDecoyWork). Called twice: pre-read + decoy work.
        verify(userRepository, times(2)).findByEmail(TEST_EMAIL);
        verify(userRepository, never()).findByEmailForUpdate(any());
        verifyNoInteractions(emailNotificationService);
    }

    @Test
    @DisplayName("requestReset — silent no-op when user account is inactive (decoy work still runs, timing defense)")
    void should_returnSilently_when_userInactive() {
        User user = inactiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(tokenGenerator.generateOtp()).thenReturn("000000");

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        // Same decoy-work timing defense as the unverified case above.
        verify(userRepository, times(2)).findByEmail(TEST_EMAIL);
        verify(userRepository, never()).findByEmailForUpdate(any());
        verifyNoInteractions(emailNotificationService);
    }

    @Test
    @DisplayName("requestReset — performs decoy crypto + DB work on unknown-email no-op (timing defense)")
    void should_performDecoyWork_when_emailUnknown() {
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());
        when(tokenGenerator.generateOtp()).thenReturn("000000");

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        verify(tokenGenerator).generateOtp();
        verify(tokenGenerator).hashOtp("000000");
        // Decoy DB round-trip mirroring the eligible path's lock-escalation lookup — via the
        // NON-locking findByEmail, never findByEmailForUpdate (see F1: an ineligible-but-known
        // account must not take a real row lock on the decoy path). Called twice: pre-read +
        // decoy work.
        verify(userRepository, times(2)).findByEmail(TEST_EMAIL);
        verify(userRepository, never()).findByEmailForUpdate(any());
        verifyNoInteractions(emailNotificationService);
    }

    @Test
    @DisplayName("requestReset — silent no-op when a locked account is (re)requested (anti-brute-force)")
    void should_returnSilently_when_accountLocked() {
        User preRead = activeVerifiedUser();
        User locked = activeVerifiedUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(preRead));
        when(userRepository.findByEmailForUpdate(TEST_EMAIL)).thenReturn(Optional.of(locked));
        when(passwordResetOtpProcessor.isLocked(locked)).thenReturn(true);

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        verifyNoInteractions(emailNotificationService);
        assertThat(locked.getPasswordResetCodeHash())
                .as("a locked account must never be issued a fresh OTP")
                .isNull();
    }

    @Test
    @DisplayName("requestReset — return200NotThrottled: unauthenticated caller repeating forgot-password within "
            + "cooldown gets a silent no-op, NOT a 429 (anti-enumeration: a 429 here would leak "
            + "account existence via response status alone)")
    void should_return200NotThrottled_when_unauthenticatedCallerRepeatsForgotPasswordWithinCooldown() {
        User preRead = activeVerifiedUser();
        User locked = activeVerifiedUser();
        // Code was issued 10s ago (otpTtl=15m, so expiresAt = now + 15m - 10s); cooldown 60s
        // means nextAllowed = issuedAt + 60s = (now - 10s) + 60s = now + 50s (still in the future).
        locked.setPasswordResetCodeExpiresAt(FIXED_NOW.plusSeconds(890));
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(preRead));
        when(userRepository.findByEmailForUpdate(TEST_EMAIL)).thenReturn(Optional.of(locked));
        when(passwordResetOtpProcessor.isLocked(locked)).thenReturn(false);

        service.requestReset(new ForgotPasswordRequest(TEST_EMAIL));

        // No exception at all — requestReset must return normally (200) exactly like every
        // other no-op branch (unknown/inactive/unverified/locked email), so a repeated call
        // within the cooldown cannot be distinguished from those by HTTP status.
        verifyNoInteractions(emailNotificationService);
        // The already-issued, still-live code is left untouched — no OTP is reissued or
        // cleared while inside the cooldown.
        assertThat(locked.getPasswordResetCodeExpiresAt()).isEqualTo(FIXED_NOW.plusSeconds(890));
    }

    // =========================================================================
    // requestResetForUserId — authenticated change-password-from-settings entry point
    // =========================================================================

    @Test
    @DisplayName("requestResetForUserId — mints and sends OTP for the authenticated caller (no anti-enumeration needed)")
    void should_mintAndSendOtp_when_requestedByAuthenticatedUserId() {
        User user = activeVerifiedUser();
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(passwordResetOtpProcessor.isLocked(user)).thenReturn(false);
        when(tokenGenerator.generateOtp()).thenReturn("654321");
        when(tokenGenerator.hashOtp("654321")).thenReturn("hashedOtp2");

        service.requestResetForUserId(USER_ID);

        assertThat(user.getPasswordResetCodeHash()).isEqualTo("hashedOtp2");
        verify(emailNotificationService).sendPasswordResetOtpEmail(eq(user), eq("654321"));
        // No email-lookup at all — the caller is identified purely by userId.
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    @DisplayName("requestResetForUserId — throws NotFoundException when the user row is missing (defensive)")
    void should_throwNotFound_when_userIdMissing() {
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestResetForUserId(USER_ID))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(emailNotificationService);
    }

    @Test
    @DisplayName("requestResetForUserId — zero duplication: shares mintAndSendOtp cooldown/lock logic with requestReset")
    void should_throwResendThrottled_when_authenticatedCallerWithinCooldown() {
        User user = activeVerifiedUser();
        user.setPasswordResetCodeExpiresAt(FIXED_NOW.plusSeconds(890));
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(passwordResetOtpProcessor.isLocked(user)).thenReturn(false);

        assertThatThrownBy(() -> service.requestResetForUserId(USER_ID))
                .isInstanceOf(ResendThrottledException.class);

        verifyNoInteractions(emailNotificationService);
    }

    // =========================================================================
    // verifyOtp — thin delegate to PasswordResetOtpProcessor
    // =========================================================================

    @Test
    @DisplayName("verifyOtp — delegates to PasswordResetOtpProcessor and wraps the raw ticket")
    void should_delegateToProcessor_when_verifyOtpCalled() {
        var request = new VerifyPasswordResetOtpRequest(TEST_EMAIL, "123456");
        when(passwordResetOtpProcessor.verifyAndIssueTicket(request)).thenReturn(RAW_TICKET);

        VerifyPasswordResetOtpResponse response = service.verifyOtp(request);

        assertThat(response.resetTicket()).isEqualTo(RAW_TICKET);
        verify(passwordResetOtpProcessor).verifyAndIssueTicket(request);
    }

    // =========================================================================
    // resetPassword — happy path
    // =========================================================================

    @Test
    @DisplayName("resetPassword — updates password hash and marks ticket used on valid resetTicket")
    void should_resetPassword_when_validTicketAndPassword() {
        PasswordResetTicket ticket = validTicket();
        User user = activeVerifiedUser();
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);
        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.of(ticket));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!"));

        assertThat(passwordEncoder.matches("NewValidPass1!", user.getPasswordHash())).isTrue();
        assertThat(ticket.isUsed()).isTrue();
    }

    @Test
    @DisplayName("resetPassword — revokes all refresh tokens (global logout) on success")
    void should_revokeAllRefreshTokens_when_resetSucceeds() {
        PasswordResetTicket ticket = validTicket();
        User user = activeVerifiedUser();
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);
        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.of(ticket));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!"));

        verify(refreshTokenRepository).deleteByUserId(USER_ID);
    }

    @Test
    @DisplayName("resetPassword — stamps tokensValidAfter and evicts the cache entry (revokes outstanding access tokens)")
    void should_stampTokensValidAfterAndEvictCache_when_resetSucceeds() {
        PasswordResetTicket ticket = validTicket();
        User user = activeVerifiedUser();
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);
        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.of(ticket));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!"));

        assertThat(user.getTokensValidAfter()).isEqualTo(FIXED_NOW);
        verify(tokensValidAfterCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("resetPassword — defers tokensValidAfter cache eviction to afterCommit, never fires it synchronously inline")
    void should_deferCacheEviction_toAfterCommit_when_transactionSynchronizationActive() {
        PasswordResetTicket ticket = validTicket();
        User user = activeVerifiedUser();
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);
        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.of(ticket));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!"));

            verify(tokensValidAfterCache, never()).invalidate(any());

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();

            syncs.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(tokensValidAfterCache).invalidate(USER_ID);
    }

    @Test
    @DisplayName("resetPassword — invalidates all other outstanding reset tickets after success (defence in depth)")
    void should_supersedeOtherResetTickets_when_resetSucceeds() {
        PasswordResetTicket ticket = validTicket();
        User user = activeVerifiedUser();
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);
        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.of(ticket));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!"));

        verify(passwordResetTicketRepository).markAllUsedByUserId(USER_ID);
    }

    @Test
    @DisplayName("resetPassword — issues no new session and revokes existing ones (no auto-login)")
    void should_notReturnTokens_when_resetSucceeds() {
        PasswordResetTicket ticket = validTicket();
        User user = activeVerifiedUser();
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);
        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.of(ticket));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!"));

        verify(refreshTokenRepository).deleteByUserId(USER_ID);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("resetPassword — ticket whose expiresAt EQUALS now is still valid (boundary: expiry is strict isBefore)")
    void should_resetPassword_when_ticketExpiresExactlyAtNow() {
        PasswordResetTicket boundaryTicket = new PasswordResetTicket(HASHED_TICKET, USER_ID, FIXED_NOW);
        User user = activeVerifiedUser();
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);
        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.of(boundaryTicket));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!"));

        assertThat(passwordEncoder.matches("NewValidPass1!", user.getPasswordHash())).isTrue();
        assertThat(boundaryTicket.isUsed()).isTrue();
    }

    // =========================================================================
    // resetPassword — oracle protection (identical 400 for all failure modes)
    // =========================================================================

    @Test
    @DisplayName("resetPassword — throws generic 400 when ticket is unknown (not in DB)")
    void should_throwGeneric400_when_ticketUnknown() {
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);
        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getMessage()).isEqualTo("Invalid or expired reset token");
                });
    }

    @Test
    @DisplayName("resetPassword — throws generic 400 when ticket has already been used")
    void should_throwGeneric400_when_ticketAlreadyUsed() {
        PasswordResetTicket usedTicket = usedTicket();
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);
        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.of(usedTicket));

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("resetPassword — throws generic 400 when ticket is expired")
    void should_throwGeneric400_when_ticketExpired() {
        PasswordResetTicket expiredTicket = expiredTicket();
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);
        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.of(expiredTicket));

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("resetPassword — 400 message is byte-identical for used, expired, and unknown ticket (no oracle)")
    void should_returnIdenticalMessage_for_usedExpiredAndUnknownTicket() {
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);
        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.of(usedTicket()));
        String usedMsg = captureExceptionMessage(() ->
                service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!")));

        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.of(expiredTicket()));
        String expiredMsg = captureExceptionMessage(() ->
                service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!")));

        when(passwordResetTicketRepository.findByTicketHashForUpdate(HASHED_TICKET)).thenReturn(Optional.empty());
        String unknownMsg = captureExceptionMessage(() ->
                service.resetPassword(new ResetPasswordRequest(RAW_TICKET, "NewValidPass1!")));

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

    private PasswordResetTicket validTicket() {
        return new PasswordResetTicket(HASHED_TICKET, USER_ID, FIXED_NOW.plusSeconds(600));
    }

    private PasswordResetTicket usedTicket() {
        var t = new PasswordResetTicket(HASHED_TICKET, USER_ID, FIXED_NOW.plusSeconds(600));
        t.markUsed();
        return t;
    }

    private PasswordResetTicket expiredTicket() {
        return new PasswordResetTicket(HASHED_TICKET, USER_ID, FIXED_NOW.minusSeconds(1));
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
