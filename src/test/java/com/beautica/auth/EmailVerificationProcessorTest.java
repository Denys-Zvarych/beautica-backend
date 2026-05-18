package com.beautica.auth;

import com.beautica.auth.dto.VerifyEmailRequest;
import com.beautica.common.exception.VerificationException;
import com.beautica.config.VerificationPolicyConfig;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the locked critical section of email verification.
 *
 * <p>These were relocated from {@code AuthServiceTest} when the section was
 * extracted into its own bean (a {@code this.}-call to a {@code @Transactional}
 * method bypasses the proxy and would hold the {@code PESSIMISTIC_WRITE} lock
 * across token issuance — Anti-Bug Playbook §F3). They additionally cover the
 * resend-surviving cumulative-lockout bound (QA HIGH).
 *
 * <p>Time is pinned via a fixed {@link Clock}; no real DB, no transaction
 * manager (the {@code noRollbackFor} semantics are verified at the IT layer in
 * {@code EmailVerificationIT}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailVerificationProcessor — unit")
class EmailVerificationProcessorTest {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationProcessorTest.class);

    private static final Instant FIXED_NOW = Instant.parse("2025-06-01T12:00:00Z");
    private static final int CUMULATIVE_THRESHOLD = 10;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenGenerator tokenGenerator;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private EmailVerificationProcessor processor;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        var policy = new VerificationPolicyConfig(
                CUMULATIVE_THRESHOLD, LOCKOUT, Duration.ofHours(24));
        processor = new EmailVerificationProcessor(userRepository, tokenGenerator, clock, policy);
    }

    // ─── happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnUserIdAndClearState_when_validCodeProvided")
    void should_returnUserIdAndClearState_when_validCodeProvided() {
        var userId = UUID.randomUUID();
        var email = "verify@example.com";
        var rawCode = "123456";
        var codeHash = "a".repeat(64);
        var user = buildUnverified(userId, email);
        user.setVerificationCodeHash(codeHash);
        user.setVerificationCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        user.setVerificationAttempts((short) 0);
        user.setVerificationFailedTotal((short) 3);

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp(rawCode)).thenReturn(codeHash);

        log.debug("Act: verify with the correct code");
        UUID result = processor.verifyAndReturnUserId(new VerifyEmailRequest(email, rawCode));

        assertThat(result).isEqualTo(userId);
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getVerificationCodeHash()).isNull();
        assertThat(user.getVerificationCodeExpiresAt()).isNull();
        assertThat(user.getVerificationAttempts()).isZero();
        assertThat(user.getVerificationFailedTotal())
                .as("a successful verify clears the lifetime failure counter")
                .isZero();
        assertThat(user.getVerificationLockedUntil()).isNull();
    }

    @Test
    @DisplayName("should_verify_when_attemptsAtMaxMinusOne")
    void should_verify_when_attemptsAtMaxMinusOne() {
        var userId = UUID.randomUUID();
        var email = "boundary@example.com";
        var rawCode = "123456";
        var codeHash = "a".repeat(64);
        var user = buildUnverified(userId, email);
        user.setVerificationCodeHash(codeHash);
        user.setVerificationCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        user.setVerificationAttempts((short) 4);

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp(rawCode)).thenReturn(codeHash);

        UUID result = processor.verifyAndReturnUserId(new VerifyEmailRequest(email, rawCode));

        assertThat(result).isEqualTo(userId);
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getVerificationAttempts()).isZero();
    }

    // ─── anti-enumeration / generic failure shapes ────────────────────────────

    @Test
    @DisplayName("should_throwInvalidCodeWithDecoyCompare_when_emailUnknown")
    void should_throwInvalidCodeWithDecoyCompare_when_emailUnknown() {
        when(userRepository.findByEmailForUpdate("ghost@example.com")).thenReturn(Optional.empty());
        // The unknown-email branch MUST still perform a hashOtp call (decoy) so
        // it is time-equivalent to a wrong-code attempt on a real user.
        when(tokenGenerator.hashOtp("000000")).thenReturn("0".repeat(64));

        assertThatThrownBy(() -> processor.verifyAndReturnUserId(
                new VerifyEmailRequest("ghost@example.com", "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.INVALID_CODE);

        // Structural timing-equivalence guard (QA MEDIUM): the decoy compare
        // ran — proven by the decoy hashOtp("000000") stub being consumed.
        org.mockito.Mockito.verify(tokenGenerator).hashOtp("000000");
    }

    @Test
    @DisplayName("should_throwInvalidCode_when_userAlreadyVerified")
    void should_throwInvalidCode_when_userAlreadyVerified() {
        var email = "done@example.com";
        var user = buildUnverified(UUID.randomUUID(), email);
        user.setEmailVerified(true);
        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> processor.verifyAndReturnUserId(new VerifyEmailRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.INVALID_CODE);
    }

    @Test
    @DisplayName("should_throwInvalidCodeAndIncrement_when_wrongCodeProvided")
    void should_throwInvalidCodeAndIncrement_when_wrongCodeProvided() {
        var email = "verify@example.com";
        var storedHash = "a".repeat(64);
        var user = buildUnverified(UUID.randomUUID(), email);
        user.setVerificationCodeHash(storedHash);
        user.setVerificationCodeExpiresAt(FIXED_NOW.plusSeconds(900));

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp("000000")).thenReturn("b".repeat(64));

        assertThatThrownBy(() -> processor.verifyAndReturnUserId(new VerifyEmailRequest(email, "000000")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.INVALID_CODE);

        assertThat(user.getVerificationCodeHash())
                .as("wrong code must NOT clear the stored hash")
                .isEqualTo(storedHash);
        assertThat(user.getVerificationAttempts())
                .as("attempt counter is incremented on wrong code (noRollbackFor commits it)")
                .isEqualTo((short) 1);
        assertThat(user.getVerificationFailedTotal())
                .as("lifetime counter incremented on wrong code")
                .isEqualTo((short) 1);
    }

    @Test
    @DisplayName("should_throwCodeExpired_when_maxAttemptsExceeded")
    void should_throwCodeExpired_when_maxAttemptsExceeded() {
        var email = "locked@example.com";
        var user = buildUnverified(UUID.randomUUID(), email);
        user.setVerificationCodeHash("a".repeat(64));
        user.setVerificationCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        user.setVerificationAttempts((short) 5);
        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> processor.verifyAndReturnUserId(new VerifyEmailRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.CODE_EXPIRED);
    }

    @Test
    @DisplayName("should_throwCodeExpiredAndClear_when_codeExpired")
    void should_throwCodeExpiredAndClear_when_codeExpired() {
        var email = "expired@example.com";
        var user = buildUnverified(UUID.randomUUID(), email);
        user.setVerificationCodeHash("c".repeat(64));
        user.setVerificationCodeExpiresAt(FIXED_NOW.minusSeconds(1));
        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> processor.verifyAndReturnUserId(new VerifyEmailRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.CODE_EXPIRED);

        assertThat(user.getVerificationCodeHash()).isNull();
        assertThat(user.getVerificationCodeExpiresAt()).isNull();
    }

    @Test
    @DisplayName("should_throwCodeExpired_when_verificationCodeIsNull")
    void should_throwCodeExpired_when_verificationCodeIsNull() {
        var email = "nocode@example.com";
        var user = buildUnverified(UUID.randomUUID(), email);
        user.setVerificationCodeHash(null);
        user.setVerificationCodeExpiresAt(null);
        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> processor.verifyAndReturnUserId(new VerifyEmailRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.CODE_EXPIRED);
    }

    // ─── QA HIGH: resend-surviving cumulative lockout ─────────────────────────

    @Test
    @DisplayName("should_tripLockout_when_cumulativeFailuresReachThreshold")
    void should_tripLockout_when_cumulativeFailuresReachThreshold() {
        var email = "abuser@example.com";
        var user = buildUnverified(UUID.randomUUID(), email);
        user.setVerificationCodeHash("a".repeat(64));
        user.setVerificationCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        // One short of the threshold; the per-OTP window is fresh (simulating
        // the post-resend state where attempts were reset but failedTotal was NOT).
        user.setVerificationFailedTotal((short) (CUMULATIVE_THRESHOLD - 1));
        user.setVerificationAttempts((short) 0);

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp("000000")).thenReturn("b".repeat(64));

        log.debug("Act: the failure that crosses the cumulative threshold");
        assertThatThrownBy(() -> processor.verifyAndReturnUserId(new VerifyEmailRequest(email, "000000")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                .isEqualTo(VerificationException.Code.INVALID_CODE);

        assertThat(user.getVerificationFailedTotal()).isEqualTo((short) CUMULATIVE_THRESHOLD);
        assertThat(user.getVerificationLockedUntil())
                .as("lockout must be set once cumulative failures reach the threshold")
                .isEqualTo(FIXED_NOW.plus(LOCKOUT));
    }

    @Test
    @DisplayName("should_notLock_when_lowFailureCountThenSuccess (honest user is never locked)")
    void should_notLock_when_lowFailureCountThenSuccess() {
        var email = "honest@example.com";
        var user = buildUnverified(UUID.randomUUID(), email);
        user.setVerificationCodeHash("a".repeat(64));
        user.setVerificationCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        // A couple of honest mistakes well below the threshold.
        user.setVerificationFailedTotal((short) 2);
        user.setVerificationAttempts((short) 0);

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp("123456")).thenReturn("a".repeat(64));

        processor.verifyAndReturnUserId(new VerifyEmailRequest(email, "123456"));

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getVerificationLockedUntil())
                .as("a low-failure honest user must never be locked")
                .isNull();
    }

    @Test
    @DisplayName("should_rejectWireIdentically_when_accountLocked (lock does not leak via response shape)")
    void should_rejectWireIdentically_when_accountLocked() {
        var email = "locked@example.com";
        var user = buildUnverified(UUID.randomUUID(), email);
        user.setVerificationCodeHash("a".repeat(64));
        user.setVerificationCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        user.setVerificationLockedUntil(FIXED_NOW.plusSeconds(60)); // still locked
        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> processor.verifyAndReturnUserId(new VerifyEmailRequest(email, "123456")))
                .isInstanceOf(VerificationException.class)
                .extracting(ex -> ((VerificationException) ex).getCode())
                // Identical generic INVALID_CODE — no distinct "locked" code/status.
                .isEqualTo(VerificationException.Code.INVALID_CODE);
    }

    @Test
    @DisplayName("should_allowVerify_when_lockoutWindowExpired")
    void should_allowVerify_when_lockoutWindowExpired() {
        var email = "waslocked@example.com";
        var user = buildUnverified(UUID.randomUUID(), email);
        user.setVerificationCodeHash("a".repeat(64));
        user.setVerificationCodeExpiresAt(FIXED_NOW.plusSeconds(900));
        // Lock window already elapsed (1s before now) — must not block.
        user.setVerificationLockedUntil(FIXED_NOW.minusSeconds(1));
        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(tokenGenerator.hashOtp("123456")).thenReturn("a".repeat(64));

        UUID result = processor.verifyAndReturnUserId(new VerifyEmailRequest(email, "123456"));

        assertThat(result).isEqualTo(user.getId());
        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("isLocked — true only inside an active lock window")
    void should_reportLockState_per_clock() {
        var user = buildUnverified(UUID.randomUUID(), "x@example.com");

        user.setVerificationLockedUntil(null);
        assertThat(processor.isLocked(user)).isFalse();

        user.setVerificationLockedUntil(FIXED_NOW.plusSeconds(1));
        assertThat(processor.isLocked(user)).isTrue();

        user.setVerificationLockedUntil(FIXED_NOW.minusSeconds(1));
        assertThat(processor.isLocked(user)).isFalse();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private User buildUnverified(UUID id, String email) {
        var user = new User(email, passwordEncoder.encode("test-password"), Role.CLIENT, null, null, null);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "emailVerified", false);
        return user;
    }
}
