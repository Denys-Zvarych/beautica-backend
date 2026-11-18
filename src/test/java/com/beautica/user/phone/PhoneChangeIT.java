package com.beautica.user.phone;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.beautica.common.util.ContactMask;
import com.beautica.notification.service.ContactChangeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * B516 (AUTH-D13, AUTH-D19, D3) — a phone change keeps the old verified number (login, uniqueness) until the code sent
 * to the NEW number is confirmed together with the current password, then swaps both columns in one write and leaves
 * the account a security notice (feed row + email to the confirmed address, never an SMS).
 */
@DisplayName("Change phone: password + code, old number kept until confirmed, security notice after (B516)")
class PhoneChangeIT extends AbstractPhoneIdentityIT {

    private static final long MAIL_WAIT_MS = 5_000;
    private static final String EMAIL_REQUEST_URL = "/api/v1/users/me/email/request-code";
    private static final String EMAIL_VERIFY_URL = "/api/v1/users/me/email/verify";

    private String phoneOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT phone_number FROM users WHERE id = ?", String.class, id);
    }

    private int securityNotices(UUID id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM in_app_notification WHERE recipient_user_id = ? "
                + "AND type = 'SECURITY_PHONE_CHANGED' AND subject_user_id = ?", Integer.class, id, id);
    }

    /** Two changes inside one second share the {@code TYPE:<user>:<epoch s>} dedup key; move the first one aside. */
    private void backdateNoticeDedupKeys(UUID id) {
        jdbcTemplate.update("UPDATE in_app_notification SET dedup_key = type || ':' || recipient_user_id || ':1' "
                + "WHERE recipient_user_id = ?", id);
    }

    private ResponseEntity<String> verifyChange(String token, String phone, String code, String password) {
        var body = new java.util.HashMap<String, Object>(Map.of("phone", phone, "code", code));
        if (password != null) {
            body.put("currentPassword", password);
        }
        return postAs(token, UserPhoneOtpIT.PHONE_VERIFY_URL, body);
    }

    private static String wrongCodeFor(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    @Test
    @DisplayName("the old number keeps working until the new one is confirmed with code + password, then they swap")
    void should_keepOldNumberUntilVerify_then_swapToNewNumber() {
        String oldPhone = newPhone();
        String newPhone = newPhone();
        UUID id = seedUser("INDEPENDENT_MASTER", null, false, oldPhone, null);
        String token = accessToken(oldPhone);

        assertThat(postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", newPhone)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(phoneOf(id)).as("sent, not verified: the old number stays").isEqualTo(oldPhone);
        assertThat(accessToken(oldPhone)).as("old number still logs in").isNotBlank();
        assertThat(jdbcTemplate.queryForObject("SELECT purpose FROM phone_otps WHERE phone = ?", String.class,
                newPhone)).isEqualTo("PHONE_CHANGE");

        ResponseEntity<String> verified = verifyChange(token, newPhone, lastCodeSentTo(newPhone), PASSWORD);

        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(phoneOf(id)).isEqualTo(newPhone);
        assertThat(accessToken(newPhone)).isNotBlank();
        assertThat(post(LOGIN_URL, Map.of("identifier", oldPhone, "password", PASSWORD)).getStatusCode())
                .as("the old number is no longer an identifier").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a wrong code keeps the old number")
    void should_keepOldNumber_when_wrongCodeSubmitted() {
        String oldPhone = newPhone();
        String newPhone = newPhone();
        UUID id = seedUser("CLIENT", null, false, oldPhone, null);
        String token = accessToken(oldPhone);
        postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", newPhone));

        ResponseEntity<String> response = verifyChange(token, newPhone, wrongCodeFor(lastCodeSentTo(newPhone)), PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVALID_CODE");
        assertThat(phoneOf(id)).isEqualTo(oldPhone);
    }

    @Test
    @DisplayName("the right code with a wrong password is 400 CURRENT_PASSWORD_INVALID (not 401), burns one try and changes nothing")
    void should_return400AndCountTry_when_wrongPasswordWithRightCode() {
        String oldPhone = newPhone();
        String newPhone = newPhone();
        UUID id = seedUser("CLIENT", null, false, oldPhone, null);
        String token = accessToken(oldPhone);
        postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", newPhone));

        ResponseEntity<String> response = verifyChange(token, newPhone, lastCodeSentTo(newPhone), "Wr0ng!Passw0rd#");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("CURRENT_PASSWORD_INVALID");
        assertThat(jdbcTemplate.queryForObject("SELECT attempts FROM phone_otps WHERE phone = ?", Integer.class,
                newPhone)).as("one failed try on that code").isEqualTo(1);
        assertThat(phoneOf(id)).isEqualTo(oldPhone);
        assertThat(securityNotices(id)).isZero();
    }

    @Test
    @DisplayName("after five wrong passwords the code is gone: even code + right password is refused")
    void should_refuseRightPasswordAndCode_when_fiveWrongPasswordsBurntTheCode() {
        String oldPhone = newPhone();
        String newPhone = newPhone();
        UUID id = seedUser("CLIENT", null, false, oldPhone, null);
        String token = accessToken(oldPhone);
        postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", newPhone));
        String code = lastCodeSentTo(newPhone);
        for (int i = 0; i < 5; i++) {
            verifyChange(token, newPhone, code, null);
        }

        ResponseEntity<String> response = verifyChange(token, newPhone, code, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVALID_CODE");
        assertThat(phoneOf(id)).isEqualTo(oldPhone);
    }

    @Test
    @DisplayName("a confirmed change leaves a feed notice and emails the confirmed address the masked new number — no SMS to the old one")
    void should_notifyFeedAndConfirmedEmail_when_phoneChanged() {
        String oldPhone = newPhone();
        String newPhone = newPhone();
        String email = newEmail();
        UUID id = seedUser("SALON_OWNER", email, true, oldPhone, null);
        String token = accessToken(oldPhone);
        postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", newPhone));

        ResponseEntity<String> verified = verifyChange(token, newPhone, lastCodeSentTo(newPhone), PASSWORD);

        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(securityNotices(id)).isOne();
        var feed = json(exchangeAs(accessToken(newPhone), org.springframework.http.HttpMethod.GET,
                "/api/v1/notifications", null));
        assertThat(feed.findValuesAsText("maskedContact")).as("feed: %s", feed)
                .containsExactly(ContactMask.phone(newPhone));
        verify(emailNotificationService, timeout(MAIL_WAIT_MS))
                .sendContactChangedEmail(email, ContactChangeKind.PHONE, ContactMask.phone(newPhone));
        assertThat(smsCountTo(oldPhone)).as("NTF-D14: never an SMS notice to the old number").isZero();
    }

    @Test
    @DisplayName("a change on an account without a confirmed email leaves the feed notice only")
    void should_notifyFeedOnly_when_noConfirmedEmail() {
        String oldPhone = newPhone();
        String newPhone = newPhone();
        UUID id = seedUser("CLIENT", null, false, oldPhone, null);
        String token = accessToken(oldPhone);
        postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", newPhone));

        ResponseEntity<String> verified = verifyChange(token, newPhone, lastCodeSentTo(newPhone), PASSWORD);

        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(securityNotices(id)).isOne();
        verify(emailNotificationService, never()).sendContactChangedEmail(any(), any(), any());
    }

    @Test
    @DisplayName("with no live code a wrong password answers the same INVALID_CODE as a right one: the password is no free oracle")
    void should_answerInvalidCodeForAnyPassword_when_noLiveCode() {
        String oldPhone = newPhone();
        String newPhone = newPhone();
        UUID id = seedUser("CLIENT", null, false, oldPhone, null);
        String token = accessToken(oldPhone);

        ResponseEntity<String> wrongPassword = verifyChange(token, newPhone, "123456", "Wr0ng!Passw0rd#");
        ResponseEntity<String> rightPassword = verifyChange(token, newPhone, "123456", PASSWORD);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(wrongPassword).at("/data/code").asText()).isEqualTo("INVALID_CODE");
        assertThat(json(wrongPassword).at("/data")).as("indistinguishable from the right-password answer")
                .isEqualTo(json(rightPassword).at("/data"));
        assertThat(json(wrongPassword).at("/message")).isEqualTo(json(rightPassword).at("/message"));
        assertThat(phoneOf(id)).isEqualTo(oldPhone);
    }

    @Test
    @DisplayName("10 wrong passwords across fresh phone and email codes lock the change: a fresh code with the right password is then refused")
    void should_refuseRightPasswordAndCode_when_lockedAfterWrongPasswordsAcrossFreshCodes() {
        // Phone + email paths share ONE counter; mixing them also keeps this under the 10 / 15 min per-IP phone-verify
        // bucket (6 phone verifies here).
        String oldPhone = newPhone();
        String newPhone = newPhone();
        String email = newEmail();
        UUID id = seedUser("CLIENT", null, false, oldPhone, null);
        String token = accessToken(oldPhone);
        postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", newPhone));
        String firstCode = lastCodeSentTo(newPhone);
        for (int i = 0; i < 5; i++) {
            verifyChange(token, newPhone, firstCode, "Wr0ng!Passw0rd#");
        }
        postAs(token, EMAIL_REQUEST_URL, Map.of("email", email));
        for (int i = 0; i < 5; i++) {
            postAs(token, EMAIL_VERIFY_URL, Map.of("email", email, "code", "123456", "currentPassword", "Wr0ng!Passw0rd#"));
        }
        postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", newPhone));

        ResponseEntity<String> response = verifyChange(token, newPhone, lastCodeSentTo(newPhone), PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("CURRENT_PASSWORD_INVALID");
        assertThat(phoneOf(id)).isEqualTo(oldPhone);
        assertThat(securityNotices(id)).isZero();
    }

    @Test
    @DisplayName("a phone notice keeps the number of that change after the phone is changed again")
    void should_keepMaskedNumberOfThatChange_when_phoneChangedAgain() {
        String oldPhone = newPhone();
        String secondPhone = newPhone();
        String thirdPhone = newPhone();
        UUID id = seedUser("CLIENT", null, false, oldPhone, null);
        String token = accessToken(oldPhone);
        postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", secondPhone));
        verifyChange(token, secondPhone, lastCodeSentTo(secondPhone), PASSWORD);
        backdateNoticeDedupKeys(id);
        postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", thirdPhone));

        ResponseEntity<String> verified = verifyChange(token, thirdPhone, lastCodeSentTo(thirdPhone), PASSWORD);

        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        var feed = json(exchangeAs(accessToken(thirdPhone), org.springframework.http.HttpMethod.GET,
                "/api/v1/notifications", null));
        assertThat(feed.findValuesAsText("maskedContact")).as("feed: %s", feed)
                .containsExactlyInAnyOrder(ContactMask.phone(secondPhone), ContactMask.phone(thirdPhone));
    }
}
