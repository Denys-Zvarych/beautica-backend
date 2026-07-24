package com.beautica.auth;

import com.beautica.auth.dto.VerifyPasswordResetOtpRequest;
import com.beautica.common.exception.VerificationException;
import com.beautica.config.PasswordResetOtpPolicyConfig;
import com.beautica.user.PasswordResetTicket;
import com.beautica.user.PasswordResetTicketRepository;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the locked critical section of password-reset OTP verification.
 *
 * <p>Mirrors {@link EmailVerificationProcessorTest} exactly in structure (happy path,
 * wrong-code, expired-code, attempt-cap, cumulative-lockout) plus the ticket-mint-on-success
 * contract unique to this processor. Time is pinned via a fixed {@link Clock}; no real DB, no
 * transaction manager (the {@code noRollbackFor} semantics are verified at the IT layer).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetOtpProcessor — unit")
class PasswordResetOtpProcessorTest {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetOtpProcessorTest.class);

    private static final Instant FIXED_NOW = Instant.parse("2025-06-01T12:00:00Z");
    private static final int MAX_ATTEMPTS = 5;
    private static final int CUMULATIVE_THRESHOLD = 10;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final Duration TICKET_TTL = Duration.ofMinutes(10);

    private static final String RAW_TICKET = "raw-ticket-abc123";
    private static final String HASHED_TICKET = "hashed-ticket-hex64-" + "a".repeat(44);

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetTicketRepository passwordResetTicketRepository;

    @Mock
    private TokenGenerator tokenGenerator;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private PasswordResetOtpProcessor processor;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        var policy = new PasswordResetOtpPolicyConfig(
                Duration.ofMinutes(15), MAX_ATTEMPTS, CUMULATIVE_THRESHOLD, LOCKOUT,
                Duration.ofSeconds(60), TICKET_TTL);
        processor = new PasswordResetOtpProcessor(
                userRepository, passwordResetTicketRepository, tokenGenerator, clock, policy);
    }

    // ─── happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnRawTicketAndClearState_when_validCodeProvided")
    void should_returnRawTicketAndClearState_when_validCodeProvided() {
        var userId = UUID.randomUUID();
        var email = "reset@example.com";
        var rawCode = "123456";
        var codeHash = "a".repeat(64);
        var user = buildUser(userId, email);
        user.setPasswordResetCodeHash(codeHash);
        user.setPasswordResetCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        user.setPasswordResetAttempts((short) 0);
        user.setPasswordResetFailedTotal((short) 3);

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp(rawCode)).thenReturn(codeHash);
        when(tokenGenerator.generateToken()).thenReturn(RAW_TICKET);
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);

        log.debug("Act: verify with the correct code");
        String result = processor.verifyAndIssueTicket(new VerifyPasswordResetOtpRequest(email, rawCode));

        assertThat(result).isEqualTo(RAW_TICKET);
        assertThat(user.getPasswordResetCodeHash()).isNull();
        assertThat(user.getPasswordResetCodeExpiresAt()).isNull();
        assertThat(user.getPasswordResetAttempts()).isZero();
        assertThat(user.getPasswordResetFailedTotal())
                .as("a successful verify clears the lifetime failure counter")
                .isZero();
        assertThat(user.getPasswordResetLockedUntil()).isNull();
    }

    @Test
    @DisplayName("should_mintTicket_when_success (ticket-mint-on-success contract)")
    void should_mintTicket_when_success() {
        var userId = UUID.randomUUID();
        var email = "mint@example.com";
        var rawCode = "123456";
        var codeHash = "a".repeat(64);
        var user = buildUser(userId, email);
        user.setPasswordResetCodeHash(codeHash);
        user.setPasswordResetCodeExpiresAt(FIXED_NOW.plusSeconds(900));

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp(rawCode)).thenReturn(codeHash);
        when(tokenGenerator.generateToken()).thenReturn(RAW_TICKET);
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);

        processor.verifyAndIssueTicket(new VerifyPasswordResetOtpRequest(email, rawCode));

        // Supersedes any outstanding ticket for the user BEFORE minting the new one.
        verify(passwordResetTicketRepository).markAllUsedByUserId(userId);
        ArgumentCaptor<PasswordResetTicket> captor = ArgumentCaptor.forClass(PasswordResetTicket.class);
        verify(passwordResetTicketRepository).save(captor.capture());
        PasswordResetTicket saved = captor.getValue();
        assertThat(saved.getTicketHash())
                .as("only the hash is ever persisted, never the raw ticket")
                .isEqualTo(HASHED_TICKET)
                .isNotEqualTo(RAW_TICKET);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getExpiresAt())
                .as("ticket TTL is drawn from PasswordResetOtpPolicyConfig.ticketTtl()")
                .isEqualTo(FIXED_NOW.plus(TICKET_TTL));
        assertThat(saved.isUsed()).isFalse();
    }

    @Test
    @DisplayName("should_verify_when_attemptsAtMaxMinusOne")
    void should_verify_when_attemptsAtMaxMinusOne() {
        var userId = UUID.randomUUID();
        var email = "boundary@example.com";
        var rawCode = "123456";
        var codeHash = "a".repeat(64);
        var user = buildUser(userId, email);
        user.setPasswordResetCodeHash(codeHash);
        user.setPasswordResetCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        user.setPasswordResetAttempts((short) (MAX_ATTEMPTS - 1));

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp(rawCode)).thenReturn(codeHash);
        when(tokenGenerator.generateToken()).thenReturn(RAW_TICKET);
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);

        String result = processor.verifyAndIssueTicket(new VerifyPasswordResetOtpRequest(email, rawCode));

        assertThat(result).isEqualTo(RAW_TICKET);
        assertThat(user.getPasswordResetAttempts()).isZero();
    }

    // ─── anti-enumeration / generic failure shapes ────────────────────────────

    @Test
    @DisplayName("should_throwInvalidCodeWithDecoyCompare_when_emailUnknown")
    void should_throwInvalidCodeWithDecoyCompare_when_emailUnknown() {
        when(userRepository.findByEmailForUpdate("ghost@example.com")).thenReturn(Optional.empty());
        when(tokenGenerator.hashOtp("000000")).thenReturn("0".repeat(64));

        assertThatThrownBy(() -> processor.verifyAndIssueTicket(
                new VerifyPasswordResetOtpRequest("ghost@example.com", "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.INVALID_CODE);

        verify(tokenGenerator).hashOtp("000000");
        verify(passwordResetTicketRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwInvalidCodeAndIncrement_when_wrongCodeProvided")
    void should_throwInvalidCodeAndIncrement_when_wrongCodeProvided() {
        var email = "reset@example.com";
        var storedHash = "a".repeat(64);
        var user = buildUser(UUID.randomUUID(), email);
        user.setPasswordResetCodeHash(storedHash);
        user.setPasswordResetCodeExpiresAt(FIXED_NOW.plusSeconds(900));

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp("000000")).thenReturn("b".repeat(64));

        assertThatThrownBy(() -> processor.verifyAndIssueTicket(new VerifyPasswordResetOtpRequest(email, "000000")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.INVALID_CODE);

        assertThat(user.getPasswordResetCodeHash())
                .as("wrong code must NOT clear the stored hash")
                .isEqualTo(storedHash);
        assertThat(user.getPasswordResetAttempts())
                .as("attempt counter is incremented on wrong code (noRollbackFor commits it)")
                .isEqualTo((short) 1);
        assertThat(user.getPasswordResetFailedTotal())
                .as("lifetime counter incremented on wrong code")
                .isEqualTo((short) 1);
        verify(passwordResetTicketRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwCodeExpired_when_maxAttemptsExceeded")
    void should_throwCodeExpired_when_maxAttemptsExceeded() {
        var email = "locked@example.com";
        var user = buildUser(UUID.randomUUID(), email);
        user.setPasswordResetCodeHash("a".repeat(64));
        user.setPasswordResetCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        user.setPasswordResetAttempts((short) MAX_ATTEMPTS);
        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp("000000")).thenReturn("0".repeat(64));

        assertThatThrownBy(() -> processor.verifyAndIssueTicket(new VerifyPasswordResetOtpRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.CODE_EXPIRED);
        // Timing-oracle defense (backend-security finding): exhausted-attempts must pay the
        // same decoy hashOtp+isEqual cost as unknown-email/wrong-code, or a fast CODE_EXPIRED
        // response would leak "this is a known account" via latency alone.
        verify(tokenGenerator).hashOtp("000000");
        verify(passwordResetTicketRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwCodeExpiredAndClear_when_codeExpired")
    void should_throwCodeExpiredAndClear_when_codeExpired() {
        var email = "expired@example.com";
        var user = buildUser(UUID.randomUUID(), email);
        user.setPasswordResetCodeHash("c".repeat(64));
        user.setPasswordResetCodeExpiresAt(FIXED_NOW.minusSeconds(1));
        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp("000000")).thenReturn("0".repeat(64));

        assertThatThrownBy(() -> processor.verifyAndIssueTicket(new VerifyPasswordResetOtpRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.CODE_EXPIRED);

        assertThat(user.getPasswordResetCodeHash()).isNull();
        assertThat(user.getPasswordResetCodeExpiresAt()).isNull();
        // Timing-oracle defense: same decoy work as the unknown-email/wrong-code branches.
        verify(tokenGenerator).hashOtp("000000");
        verify(passwordResetTicketRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwCodeExpired_when_noCodeEverIssued")
    void should_throwCodeExpired_when_noCodeEverIssued() {
        var email = "nocode@example.com";
        var user = buildUser(UUID.randomUUID(), email);
        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp("000000")).thenReturn("0".repeat(64));

        assertThatThrownBy(() -> processor.verifyAndIssueTicket(new VerifyPasswordResetOtpRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.CODE_EXPIRED);
        // Timing-oracle defense: same decoy work as the unknown-email/wrong-code branches —
        // this is the exact scenario the backend-security finding targeted (a known account
        // that never requested a code must not answer measurably faster than an unknown one).
        verify(tokenGenerator).hashOtp("000000");
    }

    @Test
    @DisplayName("should_returnSameLatencyProfile_when_emailUnknownVsCodeNeverRequested")
    void should_returnSameLatencyProfile_when_emailUnknownVsCodeNeverRequested() {
        // Unknown email branch.
        when(userRepository.findByEmailForUpdate("ghost2@example.com")).thenReturn(Optional.empty());
        when(tokenGenerator.hashOtp("000000")).thenReturn("0".repeat(64));

        assertThatThrownBy(() -> processor.verifyAndIssueTicket(
                new VerifyPasswordResetOtpRequest("ghost2@example.com", "123456")))
                .isInstanceOf(VerificationException.class);

        // Known account, code never requested branch — same account state a caller who
        // never triggered /forgot-password would encounter.
        var knownEmail = "known-nocode@example.com";
        var user = buildUser(UUID.randomUUID(), knownEmail);
        when(userRepository.findByEmailForUpdate(knownEmail)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> processor.verifyAndIssueTicket(
                new VerifyPasswordResetOtpRequest(knownEmail, "123456")))
                .isInstanceOf(VerificationException.class);

        // Both branches must invoke the decoy hashOtp exactly once each — identical cost
        // profile regardless of whether the email maps to a real account. (Response Code
        // legitimately differs — INVALID_CODE vs CODE_EXPIRED — only the WORK must match.)
        verify(tokenGenerator, times(2)).hashOtp("000000");
    }

    // ─── cumulative lockout ────────────────────────────────────────────────────

    @Test
    @DisplayName("should_tripLockout_when_cumulativeFailuresReachThreshold")
    void should_tripLockout_when_cumulativeFailuresReachThreshold() {
        var email = "abuser@example.com";
        var user = buildUser(UUID.randomUUID(), email);
        user.setPasswordResetCodeHash("a".repeat(64));
        user.setPasswordResetCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        user.setPasswordResetFailedTotal((short) (CUMULATIVE_THRESHOLD - 1));
        user.setPasswordResetAttempts((short) 0);

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp("000000")).thenReturn("b".repeat(64));

        assertThatThrownBy(() -> processor.verifyAndIssueTicket(new VerifyPasswordResetOtpRequest(email, "000000")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.INVALID_CODE);

        assertThat(user.getPasswordResetFailedTotal()).isEqualTo((short) CUMULATIVE_THRESHOLD);
        assertThat(user.getPasswordResetLockedUntil())
                .as("lockout must be set once cumulative failures reach the threshold")
                .isEqualTo(FIXED_NOW.plus(LOCKOUT));
    }

    @Test
    @DisplayName("should_rejectWireIdentically_when_accountLocked (lock does not leak via response shape)")
    void should_rejectWireIdentically_when_accountLocked() {
        var email = "locked@example.com";
        var user = buildUser(UUID.randomUUID(), email);
        user.setPasswordResetCodeHash("a".repeat(64));
        user.setPasswordResetCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        user.setPasswordResetLockedUntil(FIXED_NOW.plusSeconds(60));
        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp("000000")).thenReturn("0".repeat(64));

        assertThatThrownBy(() -> processor.verifyAndIssueTicket(new VerifyPasswordResetOtpRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.INVALID_CODE);

        verify(tokenGenerator).hashOtp("000000");
        verify(passwordResetTicketRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_allowVerify_when_lockoutWindowExpired")
    void should_allowVerify_when_lockoutWindowExpired() {
        var email = "waslocked@example.com";
        var user = buildUser(UUID.randomUUID(), email);
        user.setPasswordResetCodeHash("a".repeat(64));
        user.setPasswordResetCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        user.setPasswordResetLockedUntil(FIXED_NOW.minusSeconds(1));
        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp("123456")).thenReturn("a".repeat(64));
        when(tokenGenerator.generateToken()).thenReturn(RAW_TICKET);
        when(tokenGenerator.hash(RAW_TICKET)).thenReturn(HASHED_TICKET);

        String result = processor.verifyAndIssueTicket(new VerifyPasswordResetOtpRequest(email, "123456"));

        assertThat(result).isEqualTo(RAW_TICKET);
    }

    @Test
    @DisplayName("isLocked — true only inside an active lock window")
    void should_reportLockState_per_clock() {
        var user = buildUser(UUID.randomUUID(), "x@example.com");

        user.setPasswordResetLockedUntil(null);
        assertThat(processor.isLocked(user)).isFalse();

        user.setPasswordResetLockedUntil(FIXED_NOW.plusSeconds(1));
        assertThat(processor.isLocked(user)).isTrue();

        user.setPasswordResetLockedUntil(FIXED_NOW.minusSeconds(1));
        assertThat(processor.isLocked(user)).isFalse();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private User buildUser(UUID id, String email) {
        var user = new User(email, passwordEncoder.encode("test-password"), Role.CLIENT, null, null, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
