package com.beautica.auth.registration;

import com.beautica.user.phone.UserPhoneOtpITPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B514 / B516 (AUTH-41, AUTH-44) — the per-IP phone OTP send bucket at its REAL production capacity (3 sends / 15 min
 * per IP, shared by guest booking, registration and the signed-in add / change phone). The shared phone-identity
 * context raises that bucket so the per-phone / per-account caps can be pinned in isolation; this class restores the
 * real value in its own context and proves the IP bound on its own: the 4th send from one IP is 429 even though no
 * phone and no account reached its own cap.
 */
@TestPropertySource(properties = "app.rate-limit.otp-send-capacity=3")
@DisplayName("Phone OTP send: 3 per 15 min per IP across flows and accounts (B514/B516, AUTH-41, AUTH-44)")
class PhoneOtpSendPerIpLimitIT extends AbstractPhoneIdentityIT {

    private String signedInLegacyClient() {
        String email = newEmail();
        seedUser("CLIENT", email, true, null, null);
        return accessToken(email);
    }

    @Test
    @DisplayName("a fourth phone code from one IP within 15 minutes is 429 with Retry-After, and no SMS goes out")
    void should_return429_when_fourthSendFromOneIp() {
        String first = signedInLegacyClient();
        String second = signedInLegacyClient();
        assertThat(sendCode(newPhone()).getStatusCode()).as("registration send").isEqualTo(HttpStatus.OK);
        assertThat(postAs(first, UserPhoneOtpITPaths.SEND, Map.of("phone", newPhone())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(postAs(second, UserPhoneOtpITPaths.SEND, Map.of("phone", newPhone())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String fourthPhone = newPhone();

        ResponseEntity<String> fourth = postAs(second, UserPhoneOtpITPaths.SEND, Map.of("phone", fourthPhone));

        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(smsCountTo(fourthPhone)).isZero();
    }
}
