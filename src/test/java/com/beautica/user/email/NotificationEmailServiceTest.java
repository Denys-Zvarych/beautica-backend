package com.beautica.user.email;

import com.beautica.auth.LoginFailureLimiter;
import com.beautica.auth.Role;
import com.beautica.auth.TokenGenerator;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.EmailAlreadyRegisteredException;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.common.exception.VerificationException;
import com.beautica.config.VerificationPolicyConfig;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.CurrentPasswordRow;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.beautica.user.security.ContactChangeNotifier;
import com.beautica.user.security.CurrentPasswordCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationEmailService — optional email stored only after its code (B516, AUTH-50, NTF-56)")
class NotificationEmailServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String EMAIL = "olena@beautica.test";
    private static final String CODE = "654321";
    private static final String CODE_HASH = "c".repeat(64);
    private static final String PASSWORD = "test-password";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final String PASSWORD_HASH = ENCODER.encode(PASSWORD);

    @Mock private UserRepository userRepository;
    @Mock private PendingEmailRepository pendingEmailRepository;
    @Mock private TokenGenerator tokenGenerator;
    @Mock private EmailNotificationService emailNotificationService;
    @Mock private UserProfileCacheEvictor userProfileCacheEvictor;
    @Mock private ContactChangeNotifier contactChangeNotifier;

    private NotificationEmailService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = serviceWith(Runnable::run);
        user = User.registerWithVerifiedPhone("+380671234567", PASSWORD_HASH, Role.SALON_OWNER, "Olena", null, "Salon",
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        // verify-only pre-read (request-code never reads it)
        lenient().when(userRepository.findCurrentPasswordRow(USER_ID))
                .thenReturn(Optional.of(new CurrentPasswordRow(PASSWORD_HASH, true)));
    }

    /** The ONE per-account lockout, as shared with the phone-change path (a single Spring bean in the app). */
    private final PasswordEncoder encoder = spy(ENCODER);
    private final CurrentPasswordCheck sharedPasswordCheck =
            new CurrentPasswordCheck(encoder, new LoginFailureLimiter(Clock.fixed(NOW, ZoneOffset.UTC)));

    private NotificationEmailService serviceWith(TaskExecutor emailExecutor) {
        var policy = new VerificationPolicyConfig(10, Duration.ofMinutes(15), Duration.ofHours(24),
                Duration.ofSeconds(60));
        return new NotificationEmailService(userRepository, pendingEmailRepository, tokenGenerator,
                emailNotificationService, emailExecutor, policy, userProfileCacheEvictor,
                sharedPasswordCheck, contactChangeNotifier,
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PendingEmail pending(Instant sentAt) {
        PendingEmail row = PendingEmail.forUser(USER_ID);
        row.issue(EMAIL, CODE_HASH, sentAt, sentAt.plus(NotificationEmailService.CODE_TTL));
        return row;
    }

    @Test
    @DisplayName("a code request stores the pending address and mails the code, never touching users.email")
    void should_storePendingAndMailCode_but_notTouchUsersEmail_when_codeRequested() {
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(tokenGenerator.generateOtp()).thenReturn(CODE);
        when(tokenGenerator.hashOtp(CODE)).thenReturn(CODE_HASH);

        service.requestCode(USER_ID, "  Olena@Beautica.TEST ");

        ArgumentCaptor<PendingEmail> saved = ArgumentCaptor.forClass(PendingEmail.class);
        verify(pendingEmailRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        verify(emailNotificationService).sendVerificationEmail(EMAIL, CODE);
        assertThat(user.getEmail()).as("NTF-56: never written before verify").isNull();
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("a code request for an address held by another account is no 409: the code is mailed, the 409 waits for verify")
    void should_mailCode_when_anotherAccountHoldsTheEmail() {
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(tokenGenerator.generateOtp()).thenReturn(CODE);
        when(tokenGenerator.hashOtp(CODE)).thenReturn(CODE_HASH);

        assertThatCode(() -> service.requestCode(USER_ID, EMAIL)).doesNotThrowAnyException();

        verify(userRepository, never()).existsByEmailOnOtherUser(anyString(), any());
        verify(emailNotificationService).sendVerificationEmail(EMAIL, CODE);
    }

    @Test
    @DisplayName("the 60 s cooldown applies before anything says whether the address is taken")
    void should_reject429BeforeOwnershipCheck_when_withinCooldown() {
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(pending(NOW.minusSeconds(10))));

        assertThatThrownBy(() -> service.requestCode(USER_ID, "taken@beautica.test"))
                .isInstanceOf(ResendThrottledException.class);

        verify(userRepository, never()).existsByEmailOnOtherUser(anyString(), any());
    }

    @Test
    @DisplayName("a saturated email executor after commit is logged and dropped, never a 500")
    void should_notThrow_when_emailExecutorRejects() {
        NotificationEmailService saturated = serviceWith(task -> {
            throw new TaskRejectedException("queue full");
        });
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(tokenGenerator.generateOtp()).thenReturn(CODE);
        when(tokenGenerator.hashOtp(CODE)).thenReturn(CODE_HASH);

        assertThatCode(() -> saturated.requestCode(USER_ID, EMAIL)).doesNotThrowAnyException();

        verify(pendingEmailRepository).save(any());
    }

    @Test
    @DisplayName("verify of an address another account holds is 409 only after the code proved possession")
    void should_reject409AtVerify_when_anotherAccountHoldsTheEmail() {
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(pending(NOW.minusSeconds(30))));
        when(tokenGenerator.hashOtp(CODE)).thenReturn(CODE_HASH);
        when(userRepository.existsByEmailOnOtherUser(EMAIL, USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.verifyCode(USER_ID, EMAIL, CODE, PASSWORD))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        assertThat(user.getEmail()).isNull();
    }

    @Test
    @DisplayName("a second request inside the 60 s cooldown is 429 with the remaining seconds")
    void should_reject429_when_requestedWithinCooldown() {
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(pending(NOW.minusSeconds(10))));

        assertThatThrownBy(() -> service.requestCode(USER_ID, EMAIL))
                .isInstanceOfSatisfying(ResendThrottledException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(51));

        verify(emailNotificationService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("the right code and current password store the address as confirmed and send the security notice")
    void should_storeConfirmedEmail_when_codeMatches() {
        PendingEmail row = pending(NOW.minusSeconds(30));
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(row));
        when(tokenGenerator.hashOtp(CODE)).thenReturn(CODE_HASH);

        service.verifyCode(USER_ID, EMAIL, CODE, PASSWORD);

        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.isEmailVerified()).isTrue();
        verify(userRepository).saveAndFlush(user);
        verify(pendingEmailRepository).delete(row);
        verify(userProfileCacheEvictor).evictAfterCommit(USER_ID);
        verify(contactChangeNotifier).emailChanged(USER_ID, null, EMAIL);
    }

    @Test
    @DisplayName("an email CHANGE tells the security notice the previous confirmed address")
    void should_passPreviousEmail_when_emailChanged() {
        user.confirmEmail("old@beautica.test");
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(pending(NOW.minusSeconds(30))));
        when(tokenGenerator.hashOtp(CODE)).thenReturn(CODE_HASH);

        service.verifyCode(USER_ID, EMAIL, CODE, PASSWORD);

        verify(contactChangeNotifier).emailChanged(USER_ID, "old@beautica.test", EMAIL);
    }

    @Test
    @DisplayName("a wrong current password is 400 CURRENT_PASSWORD_INVALID, counts one failed try and never compares the code")
    void should_rejectWrongPassword_when_verifying() {
        PendingEmail row = pending(NOW.minusSeconds(30));
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.verifyCode(USER_ID, EMAIL, CODE, "wrong-password"))
                .isInstanceOfSatisfying(VerificationException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VerificationException.Code.CURRENT_PASSWORD_INVALID));

        assertThat(row.getAttempts()).isEqualTo((short) 1);
        verify(tokenGenerator, never()).hashOtp(CODE);
        assertThat(user.getEmail()).isNull();
        verifyNoInteractions(contactChangeNotifier);
    }

    @Test
    @DisplayName("after 5 failed tries (wrong passwords included) the code is CODE_EXPIRED even with the right password")
    void should_answerCodeExpired_when_fiveWrongPasswords() {
        PendingEmail row = pending(NOW.minusSeconds(30));
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(row));
        for (int i = 0; i < NotificationEmailService.MAX_ATTEMPTS_PER_CODE; i++) {
            assertThatThrownBy(() -> service.verifyCode(USER_ID, EMAIL, CODE, null))
                    .isInstanceOf(VerificationException.class);
        }

        assertThatThrownBy(() -> service.verifyCode(USER_ID, EMAIL, CODE, PASSWORD))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VerificationException.Code.CODE_EXPIRED));
    }

    @Test
    @DisplayName("a wrong code counts one failed try and keeps the email unset")
    void should_countFailureAndKeepEmailUnset_when_codeWrong() {
        PendingEmail row = pending(NOW.minusSeconds(30));
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(row));
        when(tokenGenerator.hashOtp("000000")).thenReturn("d".repeat(64));

        assertThatThrownBy(() -> service.verifyCode(USER_ID, EMAIL, "000000", PASSWORD))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VerificationException.Code.INVALID_CODE));

        assertThat(row.getAttempts()).isEqualTo((short) 1);
        assertThat(user.getEmail()).isNull();
    }

    @Test
    @DisplayName("verifying an address other than the requested one is INVALID_CODE")
    void should_answerInvalidCode_when_verifyNamesAnotherAddressThanRequested() {
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(pending(NOW.minusSeconds(30))));

        assertThatThrownBy(() -> service.verifyCode(USER_ID, "other@beautica.test", CODE, PASSWORD))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VerificationException.Code.INVALID_CODE));

        assertThat(user.getEmail()).isNull();
    }

    @Test
    @DisplayName("a code older than 15 minutes is CODE_EXPIRED")
    void should_answerCodeExpired_when_codeOlderThan15Minutes() {
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(pending(NOW.minusSeconds(16 * 60))));

        assertThatThrownBy(() -> service.verifyCode(USER_ID, EMAIL, CODE, PASSWORD))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VerificationException.Code.CODE_EXPIRED));

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an account locked by wrong passwords on the phone path is refused here too, even with the right password and code")
    void should_refuseCorrectPassword_when_accountLockedBySharedCounter() {
        for (int i = 0; i < LoginFailureLimiter.MAX_FAILURES; i++) {
            sharedPasswordCheck.verify(USER_ID, PASSWORD_HASH, "wrong-password"); // as UserPhoneService would
        }
        PendingEmail row = pending(NOW.minusSeconds(30));
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.verifyCode(USER_ID, EMAIL, CODE, PASSWORD))
                .isInstanceOfSatisfying(VerificationException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VerificationException.Code.CURRENT_PASSWORD_INVALID));

        assertThat(user.getEmail()).isNull();
        verifyNoInteractions(contactChangeNotifier);
    }

    @Test
    @DisplayName("wrong passwords on the email path count toward the shared lockout across fresh codes")
    void should_lockAccount_when_wrongPasswordsAcrossFreshEmailCodes() {
        for (int i = 0; i < LoginFailureLimiter.MAX_FAILURES; i++) {
            when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(pending(NOW.minusSeconds(30))));
            assertThatThrownBy(() -> service.verifyCode(USER_ID, EMAIL, CODE, "wrong-password"))
                    .isInstanceOf(VerificationException.class);
        }

        boolean phonePathAccepts = sharedPasswordCheck.verify(USER_ID, PASSWORD_HASH, PASSWORD);

        assertThat(phonePathAccepts).as("the phone path shares the lock").isFalse();
    }

    @Test
    @DisplayName("the current password is checked on the non-locking pre-read, before the row lock is taken")
    void should_checkPasswordBeforeRowLock_when_verifying() {
        when(pendingEmailRepository.findById(USER_ID)).thenReturn(Optional.of(pending(NOW.minusSeconds(30))));
        when(tokenGenerator.hashOtp(CODE)).thenReturn(CODE_HASH);

        service.verifyCode(USER_ID, EMAIL, CODE, PASSWORD);

        InOrder order = inOrder(userRepository, encoder);
        order.verify(userRepository).findCurrentPasswordRow(USER_ID);
        order.verify(encoder).matches(PASSWORD, PASSWORD_HASH);
        order.verify(userRepository).findByIdForUpdate(USER_ID);
    }
}
