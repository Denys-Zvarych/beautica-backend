package com.beautica.user.security;

import com.beautica.auth.Role;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.ContactChangeKind;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContactChangeNotifier — security notice after a phone / email change (B516, AUTH-D19)")
class ContactChangeNotifierTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final UUID USER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String NEW_PHONE = "+380502222222";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    @Mock private InAppNotificationService inAppNotificationService;
    @Mock private EmailNotificationService emailNotificationService;

    private ContactChangeNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new ContactChangeNotifier(inAppNotificationService, emailNotificationService, Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static User phoneUser(String confirmedEmail) {
        User user = User.registerWithVerifiedPhone(NEW_PHONE, PASSWORD_HASH, Role.CLIENT, "Olena", null, null, NOW);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        if (confirmedEmail != null) {
            user.confirmEmail(confirmedEmail);
        }
        return user;
    }

    @Test
    @DisplayName("a phone change writes the feed notice with the masked new number and emails it to the confirmed address")
    void should_notifyFeedAndConfirmedEmail_when_phoneChanged() {
        notifier.phoneChanged(phoneUser("olena@beautica.test"));

        verify(inAppNotificationService).notifySecurityNotice(
                InAppNotificationType.SECURITY_PHONE_CHANGED, USER_ID, "+380*****2222", NOW);
        verify(emailNotificationService).sendContactChangedEmail(
                "olena@beautica.test", ContactChangeKind.PHONE, "+380*****2222");
    }

    @Test
    @DisplayName("a phone change on an account without a confirmed email gets the feed notice only (NTF-D15)")
    void should_notifyFeedOnly_when_phoneChangedWithoutConfirmedEmail() {
        notifier.phoneChanged(phoneUser(null));

        verify(inAppNotificationService).notifySecurityNotice(
                InAppNotificationType.SECURITY_PHONE_CHANGED, USER_ID, "+380*****2222", NOW);
        verifyNoInteractions(emailNotificationService);
    }

    @Test
    @DisplayName("an email change stores the masked new email on the feed notice and mails it to the PREVIOUS confirmed address")
    void should_mailPreviousAddress_when_emailChanged() {
        notifier.emailChanged(USER_ID, "old@beautica.test", "new@beautica.test");

        verify(inAppNotificationService).notifySecurityNotice(
                InAppNotificationType.SECURITY_EMAIL_CHANGED, USER_ID, "n***@beautica.test", NOW);
        verify(emailNotificationService).sendContactChangedEmail(
                "old@beautica.test", ContactChangeKind.EMAIL, "n***@beautica.test");
    }

    @Test
    @DisplayName("a first email add has no previous address: feed notice only")
    void should_notifyFeedOnly_when_emailAdded() {
        notifier.emailChanged(USER_ID, null, "new@beautica.test");

        verify(inAppNotificationService).notifySecurityNotice(
                InAppNotificationType.SECURITY_EMAIL_CHANGED, USER_ID, "n***@beautica.test", NOW);
        verify(emailNotificationService, never()).sendContactChangedEmail(any(), any(), any());
    }

    @Test
    @DisplayName("a saturated email executor never fails the committed change and the feed notice is still written")
    void should_notThrowAndStillWriteFeedNotice_when_emailExecutorRejects() {
        var saturated = new ContactChangeNotifier(inAppNotificationService, emailNotificationService, task -> {
            throw new TaskRejectedException("queue full");
        }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatCode(() -> saturated.emailChanged(USER_ID, "old@beautica.test", "new@beautica.test"))
                .doesNotThrowAnyException();
        verify(inAppNotificationService).notifySecurityNotice(
                InAppNotificationType.SECURITY_EMAIL_CHANGED, USER_ID, "n***@beautica.test", NOW);
        verifyNoInteractions(emailNotificationService);
    }

    @Test
    @DisplayName("password reset with a confirmed email — feed notice plus contact-changed email")
    void should_notifyFeedAndConfirmedEmail_when_passwordReset() {
        notifier.passwordReset(phoneUser("olena@beautica.test"));

        verify(inAppNotificationService).notifySecurityNotice(
                InAppNotificationType.SECURITY_PASSWORD_RESET, USER_ID, null, NOW);
        verify(emailNotificationService).sendContactChangedEmail(
                "olena@beautica.test", ContactChangeKind.PASSWORD, null);
    }

    @Test
    @DisplayName("password reset without a confirmed email — feed notice only, no email")
    void should_notifyFeedOnly_when_passwordResetWithoutConfirmedEmail() {
        notifier.passwordReset(phoneUser(null));

        verify(inAppNotificationService).notifySecurityNotice(
                InAppNotificationType.SECURITY_PASSWORD_RESET, USER_ID, null, NOW);
        verifyNoInteractions(emailNotificationService);
    }
}
