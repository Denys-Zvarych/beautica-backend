package com.beautica.notification.inapp.push;

import com.beautica.notification.inapp.dto.NotificationParams;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PushCopy — B516 account security notice (AUTH-D19)")
class SecurityNoticePushCopyTest {

    @Test
    @DisplayName("a phone change push reads «Номер телефону змінено» + the support line, never the number (NTF-D01)")
    void should_renderPhoneNotice_when_phoneChanged() {
        var params = NotificationParams.securityNotice("+380*****4567");

        assertThat(PushCopy.title(InAppNotificationType.SECURITY_PHONE_CHANGED, true))
                .isEqualTo(SecurityNoticeExpectedCopy.PUSH_TITLE_PHONE);
        assertThat(PushCopy.body(InAppNotificationType.SECURITY_PHONE_CHANGED, true, params))
                .isEqualTo(SecurityNoticeExpectedCopy.PUSH_BODY)
                .doesNotContain("4567");
    }

    @Test
    @DisplayName("an email change push reads «Email для сповіщень змінено» + the support line, never the address (NTF-D01)")
    void should_renderEmailNotice_when_emailChanged() {
        var params = NotificationParams.securityNotice("o***@gmail.com");

        assertThat(PushCopy.title(InAppNotificationType.SECURITY_EMAIL_CHANGED, false))
                .isEqualTo(SecurityNoticeExpectedCopy.PUSH_TITLE_EMAIL);
        assertThat(PushCopy.body(InAppNotificationType.SECURITY_EMAIL_CHANGED, false, params))
                .isEqualTo(SecurityNoticeExpectedCopy.PUSH_BODY)
                .doesNotContain("gmail");
    }
}
