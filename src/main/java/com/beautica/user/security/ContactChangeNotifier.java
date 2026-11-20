package com.beautica.user.security;

import com.beautica.common.util.ContactMask;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.ContactChangeKind;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.AfterCommitHandOff;
import com.beautica.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * B516 (AUTH-D19) — the security notice after a phone CHANGE or a notifications email ADD / CHANGE: detection for a
 * hijacked session (the current password is the control). REUSE-FIRST: the in-app feed row (+ push via its outbox
 * twin when push is on) is {@link InAppNotificationService#notifySecurityNotice}; the email is
 * {@link EmailNotificationService#sendContactChangedEmail} on {@code emailExecutor} after commit. Never an SMS
 * (NTF-D14), never a code; the new value is shown masked only, and the feed row stores that masked value as it was
 * at the time of the change.
 *
 * <p>Email goes only to a CONFIRMED address (NTF-D15): the confirmed email on a phone change, the PREVIOUS confirmed
 * email on an email change; an email ADD (no previous address) gets the feed + push only.
 */
@Component
public class ContactChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(ContactChangeNotifier.class);

    private final InAppNotificationService inAppNotificationService;
    private final EmailNotificationService emailNotificationService;
    private final TaskExecutor emailExecutor;
    private final Clock clock;

    public ContactChangeNotifier(InAppNotificationService inAppNotificationService,
                                 EmailNotificationService emailNotificationService,
                                 @Qualifier("emailExecutor") TaskExecutor emailExecutor,
                                 Clock clock) {
        this.inAppNotificationService = inAppNotificationService;
        this.emailNotificationService = emailNotificationService;
        this.emailExecutor = emailExecutor;
        this.clock = clock;
    }

    /** Call after the new phone is written on {@code user}, inside the changing transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void phoneChanged(User user) {
        String maskedPhone = ContactMask.phone(user.getPhoneNumber());
        inAppNotificationService.notifySecurityNotice(
                InAppNotificationType.SECURITY_PHONE_CHANGED, user.getId(), maskedPhone, clock.instant());
        if (user.hasConfirmedEmail()) {
            mailAfterCommit(user.getEmail(), ContactChangeKind.PHONE, maskedPhone);
        }
    }

    /**
     * Call after the new email is confirmed, inside the changing transaction.
     *
     * @param previousConfirmedEmail the address confirmed BEFORE this change, {@code null} on a first add
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void emailChanged(UUID userId, String previousConfirmedEmail, String newEmail) {
        String maskedEmail = ContactMask.email(newEmail);
        inAppNotificationService.notifySecurityNotice(
                InAppNotificationType.SECURITY_EMAIL_CHANGED, userId, maskedEmail, clock.instant());
        if (previousConfirmedEmail != null) {
            mailAfterCommit(previousConfirmedEmail, ContactChangeKind.EMAIL, maskedEmail);
        }
    }

    /**
     * Phone-first hardening (AUTH-D26 a) — after a successful password reset, inside the resetting transaction: the
     * feed row (+ push to the account's device tokens; a reset revokes refresh tokens only) always, the email after
     * commit to the confirmed email when there is one. Never an SMS, never a code, no contact value.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void passwordReset(User user) {
        inAppNotificationService.notifySecurityNotice(
                InAppNotificationType.SECURITY_PASSWORD_RESET, user.getId(), null, clock.instant());
        if (user.hasConfirmedEmail()) {
            mailAfterCommit(user.getEmail(), ContactChangeKind.PASSWORD, null);
        }
    }

    private void mailAfterCommit(String to, ContactChangeKind kind, String maskedNewValue) {
        AfterCommitHandOff.submit(emailExecutor,
                () -> emailNotificationService.sendContactChangedEmail(to, kind, maskedNewValue),
                log, "contact-changed email");
    }
}
