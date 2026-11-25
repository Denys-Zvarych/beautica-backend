package com.beautica.user.security;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * B516 audit-fix cycle 3 (perf) — the AUTH-D19 current-password BCrypt (80–150 ms) runs with NO transaction open on
 * the phone-change and the email verify, so it never holds one of the pooled connections; only the locked part runs in
 * a transaction. The spy records {@link TransactionSynchronizationManager#isActualTransactionActive()} on every
 * {@code matches} call made by the verify request (stubbed after sign-in, so login's own check is not recorded).
 */
@DisplayName("Current-password BCrypt runs outside any transaction (B516, AUTH-D19)")
class CurrentPasswordOutsideTransactionIT extends AbstractPhoneIdentityIT {

    private static final String PHONE_SEND_URL = "/api/v1/users/me/phone/otp/send";
    private static final String PHONE_VERIFY_URL = "/api/v1/users/me/phone/otp/verify";
    private static final String EMAIL_REQUEST_URL = "/api/v1/users/me/email/request-code";
    private static final String EMAIL_VERIFY_URL = "/api/v1/users/me/email/verify";

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    /** Transaction state seen by each BCrypt call from now on. */
    private List<Boolean> recordTransactionStateOnBcrypt() {
        List<Boolean> states = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            states.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());
        return states;
    }

    @Test
    @DisplayName("a phone change checks the current password with no transaction open, then swaps the number")
    void should_runBcryptWithoutTransaction_when_phoneChangeVerified() {
        String oldPhone = newPhone();
        String newPhone = newPhone();
        seedUser("CLIENT", null, false, oldPhone, null);
        String token = accessToken(oldPhone);
        postAs(token, PHONE_SEND_URL, Map.of("phone", newPhone));
        String code = lastCodeSentTo(newPhone);
        List<Boolean> states = recordTransactionStateOnBcrypt();

        ResponseEntity<String> verified = postAs(token, PHONE_VERIFY_URL,
                Map.of("phone", newPhone, "code", code, "currentPassword", PASSWORD));

        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(states).as("BCrypt ran, never inside a transaction").isNotEmpty().containsOnly(false);
    }

    @Test
    @DisplayName("an email verify checks the current password with no transaction open, then stores the address")
    void should_runBcryptWithoutTransaction_when_emailVerified() {
        String phone = newPhone();
        UUID id = seedUser("SALON_OWNER", null, false, phone, null);
        String token = accessToken(phone);
        String email = newEmail();
        postAs(token, EMAIL_REQUEST_URL, Map.of("email", email));
        String code = mailedCodeTo(email);
        List<Boolean> states = recordTransactionStateOnBcrypt();

        ResponseEntity<String> verified = postAs(token, EMAIL_VERIFY_URL,
                Map.of("email", email, "code", code, "currentPassword", PASSWORD));

        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id))
                .isEqualTo(email);
        assertThat(states).as("BCrypt ran, never inside a transaction").isNotEmpty().containsOnly(false);
    }
}
