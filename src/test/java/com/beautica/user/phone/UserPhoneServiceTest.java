package com.beautica.user.phone;

import com.beautica.auth.LoginFailureLimiter;
import com.beautica.auth.Role;
import com.beautica.auth.phoneotp.PhoneOtpPurpose;
import com.beautica.auth.phoneotp.PhoneOtpService;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.exception.VerificationException;
import com.beautica.user.CurrentPasswordRow;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.beautica.user.security.ContactChangeNotifier;
import com.beautica.user.security.CurrentPasswordCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserPhoneService — add / change phone by SMS code (B516, AUTH-44)")
class UserPhoneServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String OLD_PHONE = "+380671111111";
    private static final String NEW_PHONE = "+380502222222";
    private static final String PASSWORD = "test-password";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final String PASSWORD_HASH = ENCODER.encode(PASSWORD);

    @Mock private PhoneOtpService phoneOtpService;
    @Mock private com.beautica.client.service.ClientPhoneAttachService clientPhoneAttachService;
    @Mock private UserRepository userRepository;
    @Mock private UserProfileCacheEvictor userProfileCacheEvictor;
    @Mock private ContactChangeNotifier contactChangeNotifier;

    private UserPhoneService service;

    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        encoder = spy(ENCODER);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new UserPhoneService(phoneOtpService, userRepository, userProfileCacheEvictor,
                new CurrentPasswordCheck(encoder, new LoginFailureLimiter(clock)), contactChangeNotifier,
                clientPhoneAttachService,
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class), clock);
    }

    /** The non-locking pre-read and the locked read both see {@code user}. */
    private void stubAccount(User user) {
        when(userRepository.findCurrentPasswordRow(USER_ID))
                .thenReturn(Optional.of(new CurrentPasswordRow(user.getPasswordHash(), user.isPhoneVerified())));
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
    }

    private static User legacyUser() {
        User user = new User("legacy@beautica.test", PASSWORD_HASH, Role.CLIENT, "Olena", null, "0671111111");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private static User verifiedUser() {
        User user = User.registerWithVerifiedPhone(OLD_PHONE, PASSWORD_HASH, Role.CLIENT, "Olena", null, null,
                NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    @Test
    @DisplayName("send for an account without a verified phone uses PHONE_ADD bound to the caller")
    void should_sendPhoneAddBoundToCaller_when_accountHasNoVerifiedPhone() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(legacyUser()));

        service.sendCode(USER_ID, "050 222 22 22", "PLAIN");

        verify(phoneOtpService).sendCode(NEW_PHONE, "PLAIN", PhoneOtpPurpose.PHONE_ADD, USER_ID);
    }

    @Test
    @DisplayName("send for an account with a verified phone uses PHONE_CHANGE")
    void should_sendPhoneChange_when_accountAlreadyVerified() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(verifiedUser()));

        service.sendCode(USER_ID, NEW_PHONE, null);

        verify(phoneOtpService).sendCode(NEW_PHONE, null, PhoneOtpPurpose.PHONE_CHANGE, USER_ID);
    }

    @Test
    @DisplayName("send to a landline is 400 PHONE_NOT_UA_MOBILE before any lookup or SMS")
    void should_reject400_when_numberIsLandline() {
        assertThatThrownBy(() -> service.sendCode(USER_ID, "+380441234567", null))
                .isInstanceOfSatisfying(IdentityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IdentityException.Code.PHONE_NOT_UA_MOBILE));

        verifyNoInteractions(phoneOtpService, userRepository);
    }

    @Test
    @DisplayName("send to a number verified on another account still sends the code: the 409 waits for proof of possession at verify")
    void should_sendCode_when_numberTakenByAnotherAccount() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(legacyUser()));

        service.sendCode(USER_ID, NEW_PHONE, null);

        verify(phoneOtpService).sendCode(NEW_PHONE, null, PhoneOtpPurpose.PHONE_ADD, USER_ID);
        verify(userRepository, never()).existsByVerifiedPhoneOnOtherUser(anyString(), any());
    }

    @Test
    @DisplayName("verify with the caller's own code swaps number + verified-at and evicts the profile cache")
    void should_swapPhoneAtomically_when_codeMatches() {
        User user = verifiedUser();
        stubAccount(user);
        when(phoneOtpService.consumeCode(NEW_PHONE, "123456", PhoneOtpPurpose.PHONE_CHANGE, USER_ID))
                .thenReturn(true);
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        service.verifyCode(USER_ID, NEW_PHONE, "123456", PASSWORD);

        assertThat(user.getPhoneNumber()).isEqualTo(NEW_PHONE);
        assertThat(user.getPhoneVerifiedAt()).isEqualTo(NOW);
        verify(userProfileCacheEvictor).evictAfterCommit(USER_ID);
        verify(contactChangeNotifier).phoneChanged(user);
    }

    @Test
    @DisplayName("a phone change with a wrong current password is 400 CURRENT_PASSWORD_INVALID, burns one try and never checks the code")
    void should_rejectWrongPassword_when_phoneChange() {
        User user = verifiedUser();
        stubAccount(user);
        when(phoneOtpService.recordFailedAttempt(NEW_PHONE, PhoneOtpPurpose.PHONE_CHANGE, USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.verifyCode(USER_ID, NEW_PHONE, "123456", "wrong-password"))
                .isInstanceOfSatisfying(VerificationException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VerificationException.Code.CURRENT_PASSWORD_INVALID));

        verify(phoneOtpService, never()).consumeCode(anyString(), anyString(), any(), any());
        assertThat(user.getPhoneNumber()).isEqualTo(OLD_PHONE);
        verifyNoInteractions(contactChangeNotifier);
    }

    @Test
    @DisplayName("a phone change without the current password is answered like a wrong one")
    void should_rejectMissingPassword_when_phoneChange() {
        stubAccount(verifiedUser());
        when(phoneOtpService.recordFailedAttempt(NEW_PHONE, PhoneOtpPurpose.PHONE_CHANGE, USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.verifyCode(USER_ID, NEW_PHONE, "123456", null))
                .isInstanceOfSatisfying(VerificationException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VerificationException.Code.CURRENT_PASSWORD_INVALID));
    }

    @Test
    @DisplayName("a wrong password with no live code to burn is the code error, so the password is never a free oracle")
    void should_answerInvalidCode_when_wrongPasswordAndNoLiveCode() {
        stubAccount(verifiedUser());
        when(phoneOtpService.recordFailedAttempt(NEW_PHONE, PhoneOtpPurpose.PHONE_CHANGE, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.verifyCode(USER_ID, NEW_PHONE, "123456", "wrong-password"))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VerificationException.Code.INVALID_CODE));
    }

    @Test
    @DisplayName("a first phone add needs only the code and sends no security notice")
    void should_addPhoneWithoutPassword_when_noVerifiedPhoneYet() {
        User user = legacyUser();
        stubAccount(user);
        when(phoneOtpService.consumeCode(NEW_PHONE, "123456", PhoneOtpPurpose.PHONE_ADD, USER_ID)).thenReturn(true);
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        service.verifyCode(USER_ID, NEW_PHONE, "123456", null);

        assertThat(user.getPhoneNumber()).isEqualTo(NEW_PHONE);
        verify(phoneOtpService, never()).recordFailedAttempt(anyString(), any(), any());
        verifyNoInteractions(contactChangeNotifier);
    }

    @Test
    @DisplayName("B527: a CLIENT's verified add / change attaches the guest and walk-in bookings of the new number")
    void should_attachBookingsOfTheNewPhone_when_clientVerifies() {
        User user = verifiedUser();
        stubAccount(user);
        when(phoneOtpService.consumeCode(NEW_PHONE, "123456", PhoneOtpPurpose.PHONE_CHANGE, USER_ID))
                .thenReturn(true);
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        service.verifyCode(USER_ID, NEW_PHONE, "123456", PASSWORD);

        verify(clientPhoneAttachService).attachAfterCommit(org.mockito.ArgumentMatchers.any(com.beautica.user.User.class), org.mockito.ArgumentMatchers.eq(com.beautica.booking.enums.AttachReason.PHONE_VERIFIED));
    }

    @Test
    @DisplayName("B527: a provider account's verified phone attaches nothing (CLIENT only, D6)")
    void should_notAttachAnything_when_accountIsNotAClient() {
        User user = User.registerWithVerifiedPhone(OLD_PHONE, PASSWORD_HASH, Role.INDEPENDENT_MASTER, "Olena", null,
                null, NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(user, "id", USER_ID);
        stubAccount(user);
        when(phoneOtpService.consumeCode(NEW_PHONE, "123456", PhoneOtpPurpose.PHONE_CHANGE, USER_ID))
                .thenReturn(true);
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        service.verifyCode(USER_ID, NEW_PHONE, "123456", PASSWORD);

        verifyNoInteractions(clientPhoneAttachService);
    }

    @Test
    @DisplayName("B527: a wrong code attaches nothing")
    void should_notAttach_when_codeDoesNotMatch() {
        User user = verifiedUser();
        stubAccount(user);
        when(phoneOtpService.consumeCode(eq(NEW_PHONE), eq("000000"), eq(PhoneOtpPurpose.PHONE_CHANGE), eq(USER_ID)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.verifyCode(USER_ID, NEW_PHONE, "000000", PASSWORD))
                .isInstanceOf(VerificationException.class);

        verifyNoInteractions(clientPhoneAttachService);
    }

    @Test
    @DisplayName("verify with a wrong (or another account's) code is 400 INVALID_CODE and keeps the old number")
    void should_keepOldNumber_when_codeDoesNotMatch() {
        User user = verifiedUser();
        stubAccount(user);
        when(phoneOtpService.consumeCode(eq(NEW_PHONE), eq("000000"), eq(PhoneOtpPurpose.PHONE_CHANGE), eq(USER_ID)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.verifyCode(USER_ID, NEW_PHONE, "000000", PASSWORD))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VerificationException.Code.INVALID_CODE));

        assertThat(user.getPhoneNumber()).isEqualTo(OLD_PHONE);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("verify after another account claimed the number is 409 and changes nothing")
    void should_reject409_when_numberTakenBeforeVerify() {
        User user = legacyUser();
        stubAccount(user);
        when(phoneOtpService.consumeCode(NEW_PHONE, "123456", PhoneOtpPurpose.PHONE_ADD, USER_ID)).thenReturn(true);
        when(userRepository.existsByVerifiedPhoneOnOtherUser(NEW_PHONE, USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.verifyCode(USER_ID, NEW_PHONE, "123456", null))
                .isInstanceOf(IdentityException.class);

        assertThat(user.isPhoneVerified()).isFalse();
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("10 wrong passwords across several fresh codes lock the account: the correct password and code are then refused")
    void should_refuseCorrectPassword_when_lockedAfterWrongPasswordsAcrossFreshCodes() {
        User user = verifiedUser();
        stubAccount(user);
        when(phoneOtpService.recordFailedAttempt(NEW_PHONE, PhoneOtpPurpose.PHONE_CHANGE, USER_ID)).thenReturn(true);
        String[] freshCodes = {"111111", "222222", "333333"};
        for (int i = 0; i < LoginFailureLimiter.MAX_FAILURES; i++) {
            String code = freshCodes[i / 5];
            assertThatThrownBy(() -> service.verifyCode(USER_ID, NEW_PHONE, code, "wrong-password"))
                    .isInstanceOf(VerificationException.class);
        }

        assertThatThrownBy(() -> service.verifyCode(USER_ID, NEW_PHONE, "333333", PASSWORD))
                .isInstanceOfSatisfying(VerificationException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VerificationException.Code.CURRENT_PASSWORD_INVALID));

        verify(phoneOtpService, never()).consumeCode(anyString(), anyString(), any(), any());
        verify(encoder, times(LoginFailureLimiter.MAX_FAILURES)).matches(any(), any());
        assertThat(user.getPhoneNumber()).isEqualTo(OLD_PHONE);
    }

    @Test
    @DisplayName("the current password is checked on the non-locking pre-read, before the row lock is taken")
    void should_checkPasswordBeforeRowLock_when_phoneChange() {
        User user = verifiedUser();
        stubAccount(user);
        when(phoneOtpService.consumeCode(NEW_PHONE, "123456", PhoneOtpPurpose.PHONE_CHANGE, USER_ID)).thenReturn(true);
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        service.verifyCode(USER_ID, NEW_PHONE, "123456", PASSWORD);

        InOrder order = inOrder(userRepository, encoder);
        order.verify(userRepository).findCurrentPasswordRow(USER_ID);
        order.verify(encoder).matches(PASSWORD, PASSWORD_HASH);
        order.verify(userRepository).findByIdForUpdate(USER_ID);
    }

    @Test
    @DisplayName("when a concurrent first add verified the phone after the pre-read, the password is still required")
    void should_requirePassword_when_phoneVerifiedBetweenPreReadAndLock() {
        when(userRepository.findCurrentPasswordRow(USER_ID))
                .thenReturn(Optional.of(new CurrentPasswordRow(PASSWORD_HASH, false)));
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(verifiedUser()));
        when(phoneOtpService.recordFailedAttempt(NEW_PHONE, PhoneOtpPurpose.PHONE_CHANGE, USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.verifyCode(USER_ID, NEW_PHONE, "123456", null))
                .isInstanceOfSatisfying(VerificationException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VerificationException.Code.CURRENT_PASSWORD_INVALID));

        verify(phoneOtpService, never()).consumeCode(anyString(), anyString(), any(), any());
    }
}
