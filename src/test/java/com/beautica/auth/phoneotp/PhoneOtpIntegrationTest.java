package com.beautica.auth.phoneotp;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Integration test (Testcontainers) for the Phone OTP flow. Asserts the V87 migration
 * applies cleanly, the OTP row is persisted as a SHA-256 hash (never the plaintext code),
 * and both endpoints are reachable with no auth header. {@code SmsService} is mocked so
 * no real SMS is dispatched; the code is captured from the mock to verify the stored hash.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Phone OTP endpoints — integration")
class PhoneOtpIntegrationTest extends AbstractIntegrationTest {

    private static final String PHONE = "+380671234567";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private SmsService smsService;

    private static String sha256Hex(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    @DisplayName("POST /book/otp/send — reachable without auth; row persisted as SHA-256, not plaintext")
    void should_persistHashedOtp_when_sendWithoutAuth() throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/book/otp/send", json("{\"phone\":\"" + PHONE + "\"}"), String.class);

        assertThat(response.getStatusCode())
                .as("endpoint must be reachable with no Authorization header")
                .isEqualTo(HttpStatus.OK);

        ArgumentCaptor<String> smsText = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq(PHONE), smsText.capture());
        String code = smsText.getValue().replaceAll(".*?(\\d{6}).*", "$1");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT phone, code_hash, used FROM phone_otps WHERE phone = ?", PHONE);
        assertThat(row.get("code_hash"))
                .as("stored code_hash must be the SHA-256 hex of the code, never the plaintext")
                .isEqualTo(sha256Hex(code));
        assertThat(row.get("code_hash").toString()).doesNotContain(code).hasSize(64);
        assertThat(row.get("used")).isEqualTo(false);
    }

    @Test
    @DisplayName("full flow: send then verify returns a guest token without auth")
    void should_returnGuestToken_when_verifyAfterSend() throws Exception {
        restTemplate.postForEntity(
                "/api/v1/book/otp/send", json("{\"phone\":\"" + PHONE + "\"}"), String.class);

        ArgumentCaptor<String> smsText = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq(PHONE), smsText.capture());
        String code = smsText.getValue().replaceAll(".*?(\\d{6}).*", "$1");

        ResponseEntity<String> verify = restTemplate.postForEntity(
                "/api/v1/book/otp/verify",
                json("{\"phone\":\"" + PHONE + "\",\"code\":\"" + code + "\"}"),
                String.class);

        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody())
                .as("verify response must carry a guestToken")
                .contains("guestToken");

        Boolean used = jdbc.queryForObject(
                "SELECT used FROM phone_otps WHERE phone = ?", Boolean.class, PHONE);
        assertThat(used).as("OTP must be marked used after a successful verify").isTrue();
    }

    @Test
    @DisplayName("POST /book/otp/verify — 401 generic for a wrong code")
    void should_return401_when_verifyWrongCode() throws Exception {
        // Seed a known active OTP directly so the wrong-code branch is deterministic
        // (no 1/10000 collision with a random send). expires_at is well in the future.
        jdbc.update(
                "INSERT INTO phone_otps(phone, code_hash, expires_at, used) "
                        + "VALUES (?, ?, now() + interval '10 minutes', false)",
                PHONE, sha256Hex("123456"));

        ResponseEntity<String> verify = restTemplate.postForEntity(
                "/api/v1/book/otp/verify",
                json("{\"phone\":\"" + PHONE + "\",\"code\":\"000000\"}"),
                String.class);

        assertThat(verify.getStatusCode())
                .as("wrong code must yield a generic 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
