package com.beautica.user.phone;

import com.beautica.auth.PhoneRegistrationService;
import com.beautica.auth.Role;
import com.beautica.auth.phoneotp.PhoneOtpPurpose;
import com.beautica.auth.phoneotp.PhoneOtpService;
import com.beautica.booking.enums.AttachReason;
import com.beautica.client.service.ClientPhoneAttachService;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.VerificationException;
import com.beautica.common.util.UaMobilePhone;
import com.beautica.user.CurrentPasswordRow;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.beautica.user.security.ContactChangeNotifier;
import com.beautica.user.security.CurrentPasswordCheck;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * B516 (AUTH-44, AUTH-D13, AUTH-D15, AUTH-D19, D3) — a signed-in user adds or changes the phone: an SMS code goes to
 * the NEW number, and verify swaps number + {@code phone_verified_at} in one write; until then the old verified number
 * stays. REUSE-FIRST: code lifecycle, SMS text, per-phone / per-account caps and 503 compensation are
 * {@link PhoneOtpService}'s with a USER-BOUND purpose ({@link PhoneOtpPurpose#PHONE_ADD} when the account has no
 * verified phone yet, {@link PhoneOtpPurpose#PHONE_CHANGE} otherwise), so only the account that requested a code can
 * redeem it.
 *
 * <p>Like registration (D3 there), send never answers 409 {@code PHONE_ALREADY_REGISTERED}: whether a number belongs
 * to another account is told only at verify, after the code proved possession of the number (no registration oracle,
 * and every send pays the caps).
 *
 * <p>AUTH-D19: a phone CHANGE verify also needs the current password, checked before the code; a FIRST add needs only
 * the code. After a change the account gets the security notice ({@link ContactChangeNotifier}). Wrong passwords count
 * against the account's lockout shared with the email path ({@link CurrentPasswordCheck}); the BCrypt check runs on a
 * non-locking pre-read with NO transaction open (it must not hold a pooled connection for 80–150 ms), and only the
 * locked swap runs in its own transaction ({@link TransactionTemplate} — a self-call would bypass {@code @Transactional}).
 */
@Service
public class UserPhoneService {

    private final PhoneOtpService phoneOtpService;
    private final UserRepository userRepository;
    private final UserProfileCacheEvictor userProfileCacheEvictor;
    private final CurrentPasswordCheck currentPasswordCheck;
    private final ContactChangeNotifier contactChangeNotifier;
    private final ClientPhoneAttachService clientPhoneAttachService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public UserPhoneService(PhoneOtpService phoneOtpService,
                            UserRepository userRepository,
                            UserProfileCacheEvictor userProfileCacheEvictor,
                            CurrentPasswordCheck currentPasswordCheck,
                            ContactChangeNotifier contactChangeNotifier,
                            ClientPhoneAttachService clientPhoneAttachService,
                            PlatformTransactionManager transactionManager,
                            Clock clock) {
        this.phoneOtpService = phoneOtpService;
        this.userRepository = userRepository;
        this.userProfileCacheEvictor = userProfileCacheEvictor;
        this.currentPasswordCheck = currentPasswordCheck;
        this.contactChangeNotifier = contactChangeNotifier;
        this.clientPhoneAttachService = clientPhoneAttachService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * {@code NOT_SUPPORTED}: the SMS provider call must not hold a DB connection (see {@link PhoneOtpService#sendCode}).
     *
     * @throws IdentityException 400 {@code PHONE_NOT_UA_MOBILE}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendCode(UUID userId, String rawPhone, String smsFormat) {
        String phone = UaMobilePhone.require(rawPhone);
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        phoneOtpService.sendCode(phone, smsFormat, purposeFor(user), userId);
    }

    /**
     * Redeems the caller's own code and swaps the phone atomically. {@code NOT_SUPPORTED}: the pre-read and BCrypt
     * run with no transaction (no pooled connection held); the locked part then runs in one new transaction.
     *
     * @param currentPassword required for a phone CHANGE (AUTH-D19), ignored for a first add
     * @throws VerificationException 400 {@code CURRENT_PASSWORD_INVALID} on a change with a wrong / missing password
     *                               while a live code exists (counted as a failed try on it); 400 {@code INVALID_CODE}
     *                               for a wrong, expired, exhausted, missing or other-account code
     * @throws IdentityException     409 {@code PHONE_ALREADY_REGISTERED} when another account holds the verified number
     *                               (pre-check, or the unique index at flush — never a 500); the code is not burnt
     *                               (rollback)
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void verifyCode(UUID userId, String rawPhone, String code, String currentPassword) {
        String phone = UaMobilePhone.require(rawPhone);
        CurrentPasswordRow preRead = userRepository.findCurrentPasswordRow(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        // BCrypt (80–150 ms) on the non-locking pre-read, outside any transaction and never under the row lock.
        Boolean passwordOk = preRead.phoneVerified()
                ? currentPasswordCheck.verify(userId, preRead.passwordHash(), currentPassword) : null;
        transactionTemplate.executeWithoutResult(status -> verifyLocked(userId, phone, code, currentPassword, passwordOk));
    }

    /** The row-locked part, inside the transaction {@link #verifyCode} opens; any exception rolls it back. */
    private void verifyLocked(UUID userId, String phone, String code, String currentPassword, Boolean passwordOk) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        boolean change = user.isPhoneVerified();
        PhoneOtpPurpose purpose = purposeFor(user);
        if (change && !passwordAccepted(passwordOk, user, currentPassword)) {
            throw wrongPassword(phone, userId);
        }
        if (!phoneOtpService.consumeCode(phone, code, purpose, userId)) {
            throw new VerificationException(VerificationException.Code.INVALID_CODE);
        }
        if (userRepository.existsByVerifiedPhoneOnOtherUser(phone, userId)) {
            throw new IdentityException(IdentityException.Code.PHONE_ALREADY_REGISTERED);
        }
        user.markPhoneVerified(phone, clock.instant());
        PhoneRegistrationService.saveClaimingPhone(userRepository, user);
        if (user.getRole() == Role.CLIENT) {
            // B527 (CLI-62): add AND change attach — rows already attached under the old phone stay (D4); only the new
            // phone's guest / walk-in bookings attach after this commit, in their own transaction (audit LOW).
            clientPhoneAttachService.attachAfterCommit(user, AttachReason.PHONE_VERIFIED);
        }
        userProfileCacheEvictor.evictAfterCommit(userId);
        if (change) {
            contactChangeNotifier.phoneChanged(user);
        }
    }

    /**
     * The pre-read result; only when a concurrent first add verified the phone between the pre-read and the lock
     * (the pre-read saw "add", the locked row says "change") is the password checked here, under the lock — rare,
     * and failing closed beats skipping the AUTH-D19 check.
     */
    private boolean passwordAccepted(Boolean preChecked, User lockedUser, String currentPassword) {
        return preChecked != null
                ? preChecked
                : currentPasswordCheck.verify(lockedUser.getId(), lockedUser.getPasswordHash(), currentPassword);
    }

    /**
     * AUTH-D19: the wrong password burns one try of the live code (max 5, then the code is gone). With no live code
     * there is nothing to guess against, so the answer is the code error — the password is never an unbounded oracle.
     */
    private VerificationException wrongPassword(String phone, UUID userId) {
        boolean counted = phoneOtpService.recordFailedAttempt(phone, PhoneOtpPurpose.PHONE_CHANGE, userId);
        return new VerificationException(counted
                ? VerificationException.Code.CURRENT_PASSWORD_INVALID
                : VerificationException.Code.INVALID_CODE);
    }

    private static PhoneOtpPurpose purposeFor(User user) {
        return user.isPhoneVerified() ? PhoneOtpPurpose.PHONE_CHANGE : PhoneOtpPurpose.PHONE_ADD;
    }
}
