package com.beautica.auth;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Phone-first hardening AUTH-D27 (c) — every forgot-password request for a UA mobile number takes one per-phone send
 * slot, whether an SMS goes out or not, so the per-phone 429 of another OTP purpose is no oracle on the account or its
 * channel. The per-phone cap is tightened to ONE send per window here (a tightening the guard allows), so "the next
 * registration send is 429" is exactly "the forgot request took the slot".
 */
@TestPropertySource(properties = "app.auth-limits.phone-otp-max-sends-per-window=1")
@DisplayName("Forgot password by phone — every branch burns the same per-phone slot (AUTH-D27 c)")
class ForgotPasswordSlotBurnParityIT extends AbstractPhoneIdentityIT {

    private static final String FORGOT_URL = "/api/v1/auth/forgot-password";

    private ResponseEntity<String> forgot(String phone) {
        return post(FORGOT_URL, Map.of("identifier", phone));
    }

    @Test
    @DisplayName("should_answer429OnNextRegistrationSendForEveryBranch_when_forgotRequestedFirst")
    void should_answer429OnNextRegistrationSendForEveryBranch_when_forgotRequestedFirst() {
        String unknown = newPhone();
        String emailChannel = newPhone();
        seedUser("CLIENT", newEmail(), true, emailChannel, null);
        String smsChannel = newPhone();
        seedUser("CLIENT", null, false, smsChannel, null);
        String smsCooldown = newPhone();
        UUID cooling = seedUser("CLIENT", null, false, smsCooldown, null);
        jdbcTemplate.update("UPDATE users SET password_reset_sms_sent_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(10)), cooling);
        String control = newPhone();

        List<String> burnedOrSent = List.of(unknown, emailChannel, smsChannel, smsCooldown);
        for (String phone : burnedOrSent) {
            assertThat(forgot(phone).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        verify(otpSmsSender, timeout(5_000)).send(eq(smsChannel), anyString()); // the one real (async) SMS

        for (String phone : burnedOrSent) {
            assertThat(sendCode(phone).getStatusCode()).as("registration send after forgot for %s", phone)
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        assertThat(sendCode(control).getStatusCode()).as("a phone with no forgot request").isEqualTo(HttpStatus.OK);
        assertThat(smsCountTo(unknown) + smsCountTo(emailChannel) + smsCountTo(smsCooldown))
                .as("no SMS for the branches that only burn a slot").isZero();
    }
}
