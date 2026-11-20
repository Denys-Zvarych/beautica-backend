package com.beautica.auth;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.beautica.notification.inapp.push.PushTestSupport;
import com.beautica.notification.service.ContactChangeKind;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Phone-first hardening AUTH-D26 (a) / B14 — a completed password reset leaves the account owner a
 * {@code SECURITY_PASSWORD_RESET} notice end to end: the feed row (V256/V257 accept the type), an {@code INAPP_PUSH}
 * outbox row for an active device, the contact-changed email to a confirmed address — and never an SMS beyond the code
 * itself. {@code FIREBASE_ENABLED=true}, so this class is its own cached context.
 */
@TestPropertySource(properties = "FIREBASE_ENABLED=true")
@Import(PushTestSupport.class)
@DisplayName("Password reset notice — feed + push + email, never SMS (AUTH-D26 a)")
class PasswordResetNoticeIT extends AbstractPhoneIdentityIT {

    private static final String FORGOT_URL = "/api/v1/auth/forgot-password";
    private static final String VERIFY_OTP_URL = "/api/v1/auth/verify-password-reset-otp";
    private static final String RESET_URL = "/api/v1/auth/reset-password";
    private static final String NEW_PASSWORD = "N3w!Passw0rd#x";

    private ResponseEntity<String> resetWithCode(String phone, String code) {
        ResponseEntity<String> verified = post(VERIFY_OTP_URL, Map.of("identifier", phone, "code", code));
        assertThat(verified.getStatusCode()).as("verify body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        String ticket = json(verified).at("/data/resetTicket").asText();
        return post(RESET_URL, Map.of("resetTicket", ticket, "newPassword", NEW_PASSWORD));
    }

    private int resetNoticesFor(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM in_app_notification WHERE recipient_user_id = ? "
                + "AND type = 'SECURITY_PASSWORD_RESET' AND subject_user_id = ? AND masked_contact IS NULL",
                Integer.class, userId, userId);
    }

    private int pushRowsFor(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_outbox o JOIN in_app_notification n "
                + "ON n.id = o.aggregate_id WHERE o.event_type = 'INAPP_PUSH' AND n.recipient_user_id = ? "
                + "AND n.type = 'SECURITY_PASSWORD_RESET'", Integer.class, userId);
    }

    @Test
    @DisplayName("phone-only account resets by SMS code → one feed notice + one push row; no notice email, no 2nd SMS")
    void should_notifyFeedAndPushOnly_when_phoneOnlyAccountResets() {
        String phone = newPhone();
        UUID userId = seedUser("CLIENT", null, false, phone, null);
        PushTestSupport.registerAndroidDevice(jdbcTemplate, userId, "fcm-" + UUID.randomUUID());
        assertThat(post(FORGOT_URL, Map.of("identifier", phone)).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> reset = resetWithCode(phone, lastCodeSentTo(phone));

        assertThat(reset.getStatusCode()).as("reset body: %s", reset.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(resetNoticesFor(userId)).as("feed notice, no contact value").isOne();
        assertThat(pushRowsFor(userId)).as("push queued for the active device").isOne();
        assertThat(smsCountTo(phone)).as("only the reset code itself was texted — the notice is never an SMS")
                .isOne();
        verify(emailNotificationService, never()).sendContactChangedEmail(anyString(), any(), any());
    }

    @Test
    @DisplayName("account with a confirmed email resets → feed notice + PASSWORD contact-changed email; no SMS at all")
    void should_notifyFeedAndEmail_when_accountWithConfirmedEmailResets() {
        String phone = newPhone();
        String email = newEmail();
        UUID userId = seedUser("CLIENT", email, true, phone, null);
        assertThat(post(FORGOT_URL, Map.of("identifier", phone)).getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationService, timeout(5_000)).sendPasswordResetOtpEmail(any(User.class), code.capture());

        ResponseEntity<String> reset = resetWithCode(phone, code.getValue());

        assertThat(reset.getStatusCode()).as("reset body: %s", reset.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(resetNoticesFor(userId)).isOne();
        verify(emailNotificationService, timeout(5_000))
                .sendContactChangedEmail(eq(email), eq(ContactChangeKind.PASSWORD), isNull());
        assertThat(smsCountTo(phone)).as("no SMS on the email channel, and none for the notice").isZero();
    }

    @Test
    @DisplayName("a replayed (already used) reset ticket is 400 and adds no second notice")
    void should_notNotifyAgain_when_resetTicketReplayed() {
        String phone = newPhone();
        UUID userId = seedUser("CLIENT", null, false, phone, null);
        post(FORGOT_URL, Map.of("identifier", phone));
        ResponseEntity<String> verified = post(VERIFY_OTP_URL, Map.of("identifier", phone, "code",
                lastCodeSentTo(phone)));
        String ticket = json(verified).at("/data/resetTicket").asText();
        assertThat(post(RESET_URL, Map.of("resetTicket", ticket, "newPassword", NEW_PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> replay = post(RESET_URL, Map.of("resetTicket", ticket, "newPassword", "An0ther!Pass#1"));

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resetNoticesFor(userId)).isOne();
    }
}
