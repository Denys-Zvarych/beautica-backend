package com.beautica.auth.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule 3 (user 2026-11-19) — {@code app.auth-limits.phone-otp-max-sends-per-window} really drives the per-phone OTP
 * send cap end to end (HTTP → AuthRateLimitFilter → PhoneOtpService → DB) under the {@code test} profile. The value
 * here is TIGHTER than the default (1 &lt; 3), which the fingerprint guard allows everywhere. Every request carries
 * its own X-Forwarded-For, so only the per-phone cap can answer 429. The default (3 per phone, 4th send 429) is
 * pinned without an override by {@code PhoneOtpSendHttpIT#should_return429OnFourthSend_when_threeSendsSucceeded}.
 */
@TestPropertySource(properties = "app.auth-limits.phone-otp-max-sends-per-window=1")
@DisplayName("Phone OTP send cap comes from app.auth-limits.phone-otp-max-sends-per-window (user 2026-11-19)")
class PhoneOtpSendCapFromPropertiesIT extends AbstractPhoneIdentityIT {

    private static final AtomicInteger REQUEST_IP_SEQUENCE = new AtomicInteger();

    /** 172.16.0.0/12 — disjoint from the base class's 10.x per-test IPs; a fresh one per request. */
    private static String nextRequestIp() {
        int n = REQUEST_IP_SEQUENCE.incrementAndGet();
        return "172." + (16 + n / 62_500 % 16) + "." + (n / 250 % 250) + "." + (n % 250 + 1);
    }

    private ResponseEntity<String> sendFromFreshIp(String phone) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", nextRequestIp());
        try {
            return restTemplate.postForEntity(SEND_URL,
                    new HttpEntity<>(objectMapper.writeValueAsString(Map.of("phone", phone)), headers), String.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private int otpRowsFor(String phone) {
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otps WHERE phone = ?", Integer.class,
                phone);
        return rows == null ? 0 : rows;
    }

    @Test
    @DisplayName("cap set to 1 — the second code for the same phone is 429, no SMS and no new code row")
    void should_return429OnSecondSend_when_maxSendsPerWindowIsOne() {
        String phone = newPhone();
        ResponseEntity<String> first = sendFromFreshIp(phone);
        assertThat(first.getStatusCode()).as("first send body: %s", first.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = sendFromFreshIp(phone);

        assertThat(second.getStatusCode()).as("second send body: %s", second.getBody())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        JsonNode body = json(second);
        assertThat(body.at("/success").asBoolean(true)).as("success flag").isFalse();
        assertThat(body.at("/message").asText()).as("generic 429 copy").isEqualTo("Request could not be completed");
        assertThat(smsCountTo(phone)).as("SMS sent to the capped phone").isEqualTo(1);
        assertThat(otpRowsFor(phone)).as("phone_otps rows for the capped phone").isEqualTo(1);
    }

    @Test
    @DisplayName("cap set to 1 — capping one phone leaves another phone's first code untouched (per-phone, not global)")
    void should_sendToOtherPhone_when_firstPhoneHitTheCapOfOne() {
        String capped = newPhone();
        String other = newPhone();
        assertThat(sendFromFreshIp(capped).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sendFromFreshIp(capped).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        ResponseEntity<String> otherSend = sendFromFreshIp(other);

        assertThat(otherSend.getStatusCode()).as("other phone body: %s", otherSend.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(smsCountTo(other)).as("SMS sent to the other phone").isEqualTo(1);
    }
}
