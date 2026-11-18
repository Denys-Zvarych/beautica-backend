package com.beautica.user.phone;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B516 (AUTH-45, D1) — flag ON with the provider deadline still ahead (its own context: a {@code @Nested} class cannot
 * override the enclosing class's {@code @TestPropertySource} value, the enclosing one wins).
 */
@TestPropertySource(properties = {
        "app.auth.require-verified-phone=true",
        "app.auth.phone-provider-deadline=" + PhoneRequiredGateBeforeDeadlineIT.FUTURE_DEADLINE})
@DisplayName("Verified-phone gate ON, provider deadline ahead (B516, AUTH-45)")
class PhoneRequiredGateBeforeDeadlineIT extends AbstractPhoneIdentityIT {

    static final String FUTURE_DEADLINE = "2999-12-31";

    @Test
    @DisplayName("an unverified provider's write is not gated while the deadline has not passed")
    void should_notBlockProviderWrite_when_deadlineNotPassed() {
        String email = newEmail();
        seedUser("INDEPENDENT_MASTER", email, true, null, null);

        ResponseEntity<String> write = exchangeAs(accessToken(email), HttpMethod.PATCH,
                "/api/v1/independent-masters/me/profile", Map.of("bio", "x"));

        assertThat(write.getStatusCode()).as("body: %s", write.getBody()).isNotEqualTo(HttpStatus.CONFLICT);
    }
}
