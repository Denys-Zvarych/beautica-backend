package com.beautica.auth;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B516 (AUTH-12, AUTH-15, AUTH-D18, D4, D6) — forgot-password takes a phone or an email; the channel comes from the
 * account (confirmed email first, else SMS to the verified phone, else nothing); the response is byte-identical in
 * every case. Change-password routes the same way. App Check is OFF here ({@code AppCheckGuardIT} covers ENFORCE).
 */
@DisplayName("Forgot / change password: email first, SMS only without an email (B516, AUTH-D18)")
class ForgotPasswordByPhoneIT extends AbstractPhoneIdentityIT {

    private static final String FORGOT_URL = "/api/v1/auth/forgot-password";
    private static final String VERIFY_OTP_URL = "/api/v1/auth/verify-password-reset-otp";
    private static final String RESET_URL = "/api/v1/auth/reset-password";
    private static final String CHANGE_OTP_URL = "/api/v1/users/me/change-password/request-otp";
    private static final String NEW_PASSWORD = "N3w!Passw0rd#x";

    private ResponseEntity<String> forgot(String identifier) {
        return post(FORGOT_URL, Map.of("identifier", identifier));
    }

    private String emailedResetCode() {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationService).sendPasswordResetOtpEmail(any(User.class), code.capture());
        return code.getValue();
    }

    @Test
    @DisplayName("an account with a verified phone and no email resets its password by SMS code")
    void should_smsCodeAndResetPassword_when_verifiedPhoneAndNoEmail() {
        String phone = newPhone();
        seedUser("CLIENT", null, false, phone, null);

        assertThat(forgot("0" + phone.substring(4)).getStatusCode()).isEqualTo(HttpStatus.OK);
        String code = lastCodeSentTo(phone);
        ResponseEntity<String> verified = post(VERIFY_OTP_URL, Map.of("identifier", phone, "code", code));
        String ticket = json(verified).at("/data/resetTicket").asText();
        ResponseEntity<String> reset = post(RESET_URL, Map.of("resetTicket", ticket, "newPassword", NEW_PASSWORD));

        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getStatusCode()).as("body: %s", reset.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(post(LOGIN_URL, Map.of("identifier", phone, "password", NEW_PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT purpose FROM phone_otps WHERE phone = ?", String.class, phone))
                .isEqualTo("PASSWORD_RESET");
        assertThat(jdbcTemplate.queryForObject("SELECT password_reset_sms_sent_at FROM users WHERE phone_number = ?",
                java.sql.Timestamp.class, phone)).as("cooldown stamped on the account row").isNotNull();
        verify(emailNotificationService, never()).sendPasswordResetOtpEmail(any(), anyString());
    }

    @Test
    @DisplayName("forgot by phone on an account with a confirmed email emails the code and sends no SMS")
    void should_emailCodeAndSendNoSms_when_accountHasConfirmedEmail_byPhoneOrByEmail() {
        String phone = newPhone();
        String email = newEmail();
        seedUser("CLIENT", email, true, phone, null);

        assertThat(forgot(phone).getStatusCode()).isEqualTo(HttpStatus.OK);
        String code = emailedResetCode();
        ResponseEntity<String> verified = post(VERIFY_OTP_URL, Map.of("identifier", phone, "code", code));

        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(smsCountTo(phone)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otps WHERE phone = ?", Integer.class,
                phone)).isZero();
    }

    @Test
    @DisplayName("forgot by a phone that is only an unverified legacy number sends nothing")
    void should_sendNothing_when_phoneUnverified() {
        String legacyPhone = newPhone();
        seedUser("CLIENT", newEmail(), false, null, legacyPhone);

        forgot(legacyPhone);

        verify(otpSmsSender, never()).send(anyString(), anyString());
        verify(emailNotificationService, never()).sendPasswordResetOtpEmail(any(), anyString());
    }

    @Test
    @DisplayName("forgot by an unknown phone sends nothing")
    void should_sendNothing_when_phoneUnknown() {
        forgot(newPhone());

        verify(otpSmsSender, never()).send(anyString(), anyString());
        verify(emailNotificationService, never()).sendPasswordResetOtpEmail(any(), anyString());
    }

    @Test
    @DisplayName("forgot by an unconfirmed email sends nothing")
    void should_sendNothing_when_emailUnconfirmed() {
        String unconfirmedEmail = newEmail();
        seedUser("CLIENT", unconfirmedEmail, false, null, newPhone());

        forgot(unconfirmedEmail);

        verify(otpSmsSender, never()).send(anyString(), anyString());
        verify(emailNotificationService, never()).sendPasswordResetOtpEmail(any(), anyString());
    }

    @Test
    @DisplayName("a wrong SMS reset code feeds the account's resend-surviving failure total")
    void should_countCumulativeFailure_when_smsResetCodeWrong() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        forgot(phone);
        String wrong = lastCodeSentTo(phone).equals("000000") ? "111111" : "000000";

        ResponseEntity<String> response = post(VERIFY_OTP_URL, Map.of("identifier", phone, "code", wrong));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT password_reset_failed_total FROM users WHERE id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("every forgot outcome answers a byte-identical 200 body")
    void should_answerByteIdenticalBodies_when_identifierIsSmsEmailUnknownOrLegacyField() {
        String smsPhone = newPhone();
        seedUser("CLIENT", null, false, smsPhone, null);
        String emailPhone = newPhone();
        String email = newEmail();
        seedUser("SALON_OWNER", email, true, emailPhone, null);

        List<ResponseEntity<String>> responses = List.of(
                forgot(smsPhone), forgot(emailPhone), forgot(email), forgot(newPhone()), forgot(newEmail()),
                forgot("not a phone"), post(FORGOT_URL, Map.of("email", email)));

        for (ResponseEntity<String> response : responses) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(responses.get(0).getBody());
        }
        assertThat(responses.get(0).getBody()).doesNotContainIgnoringCase("sms").doesNotContainIgnoringCase("email");
    }

    @Test
    @DisplayName("forgot with neither identifier nor email is 400")
    void should_return400_when_neitherIdentifierNorEmailSent() {
        assertThat(post(FORGOT_URL, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("change-password without an email goes by SMS, and a second request within 60 s is 429")
    void should_routeChangePasswordCodeBySms_when_accountHasNoEmail() {
        String phone = newPhone();
        seedUser("INDEPENDENT_MASTER", null, false, phone, null);
        String token = accessToken(phone);

        ResponseEntity<String> response = postAs(token, CHANGE_OTP_URL, Map.of());
        ResponseEntity<String> again = postAs(token, CHANGE_OTP_URL, Map.of());

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(smsCountTo(phone)).isEqualTo(1);
        assertThat(again.getStatusCode()).as("60 s cooldown").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(emailNotificationService, never()).sendPasswordResetOtpEmail(any(), anyString());
    }

    @Test
    @DisplayName("change-password with a confirmed email goes by email, never SMS")
    void should_routeChangePasswordCodeByEmail_when_accountHasConfirmedEmail() {
        String phone = newPhone();
        seedUser("CLIENT", newEmail(), true, phone, null);
        String token = accessToken(phone);

        ResponseEntity<String> response = postAs(token, CHANGE_OTP_URL, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(emailedResetCode()).matches("\\d{6}");
        assertThat(smsCountTo(phone)).isZero();
    }

    @Test
    @DisplayName("an unconfirmed email is not a channel: the verified phone gets the SMS")
    void should_smsVerifiedPhoneAndSendNoMail_when_accountEmailIsUnconfirmed() {
        String phone = newPhone();
        seedUser("CLIENT", newEmail(), false, phone, null);

        ResponseEntity<String> response = forgot(phone);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(smsCountTo(phone)).as("an unconfirmed email is not a channel (AUTH-D18)").isEqualTo(1);
        verify(emailNotificationService, never()).sendPasswordResetOtpEmail(any(), anyString());
    }

    @Test
    @DisplayName("forgot by the confirmed email (any case) emails the code and sends no SMS")
    void should_emailCodeAndSendNoSms_when_identifierIsTheConfirmedEmail() {
        String phone = newPhone();
        String email = newEmail();
        seedUser("INDEPENDENT_MASTER", email, true, phone, null);

        ResponseEntity<String> response = forgot(email.toUpperCase());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(emailedResetCode()).matches("\\d{6}");
        assertThat(smsCountTo(phone)).isZero();
    }

    @Test
    @DisplayName("forgot for an inactive account answers 200 and sends nothing")
    void should_sendNothingAndAnswer200_when_verifiedPhoneAccountIsInactive() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", id);

        ResponseEntity<String> response = forgot(phone);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(otpSmsSender, never()).send(anyString(), anyString());
    }
}
