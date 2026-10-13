package com.beautica.auth.phoneotp;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.SmsDeliveryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
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
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * Phase 364 QA — end-to-end HTTP contract of {@code POST /book/otp/send} after the send moved
 * outside the transaction: a failing provider yields 503 and leaves the previous code redeemable
 * through {@code /verify}; the per-phone cap answers 429 at the HTTP layer, failed sends included.
 * The per-IP bucket is raised so the per-PHONE cap is what is under test.
 */
@TestPropertySource(properties = "app.rate-limit.otp-send-capacity=100000")
@Import(TestSecurityConfig.class)
@DisplayName("POST /book/otp/send — failure and cap behaviour over HTTP (Phase 364)")
class PhoneOtpSendHttpIT extends AbstractIntegrationTest {

    private static final String OLD_CODE = "424242";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private OtpSmsSender otpSmsSender;

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> send(String phone) {
        return restTemplate.postForEntity("/api/v1/book/otp/send", json("{\"phone\":\"" + phone + "\"}"), String.class);
    }

    private ResponseEntity<String> verify(String phone, String code) {
        return restTemplate.postForEntity("/api/v1/book/otp/verify",
                json("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"), String.class);
    }

    private void seedOldCode(String phone) throws Exception {
        jdbc.update("INSERT INTO phone_otps(phone, code_hash, expires_at, used, created_at) "
                        + "VALUES (?, ?, now() + interval '9 minutes', false, now() - interval '1 minute')",
                phone, sha256Hex(OLD_CODE));
    }

    @Test
    @DisplayName("should_return503AndKeepPreviousCodeVerifiable_when_turbosmsFails")
    void should_return503AndKeepPreviousCodeVerifiable_when_turbosmsFails() throws Exception {
        String phone = "+380501000001";
        seedOldCode(phone);
        doThrow(new SmsDeliveryException("provider down")).when(otpSmsSender).send(eq(phone), anyString());

        ResponseEntity<String> sendResponse = send(phone);
        ResponseEntity<String> verifyResponse = verify(phone, OLD_CODE);

        JsonNode body = objectMapper.readTree(sendResponse.getBody());
        assertThat(sendResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body.at("/success").asBoolean()).isFalse();
        assertThat(body.at("/message").asText()).isEqualTo(ServiceUnavailableMessages.OTP_SEND_FAILED);
        assertThat(verifyResponse.getStatusCode())
                .as("old code must stay redeemable after a failed re-send, body=%s", verifyResponse.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(verifyResponse.getBody()).contains("guestToken");
    }

    @Test
    @DisplayName("should_rejectUndeliveredCode_when_sendFailedAndItsCodeIsGuessedViaVerify")
    void should_rejectUndeliveredCode_when_sendFailedAndItsCodeIsGuessedViaVerify() {
        String phone = "+380501000002";
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        doThrow(new SmsDeliveryException("provider down")).when(otpSmsSender).send(eq(phone), anyString());

        send(phone);

        Mockito.verify(otpSmsSender).send(eq(phone), text.capture());
        String undeliveredCode = text.getValue().replaceAll(".*?(\\d{6}).*", "$1");
        ResponseEntity<String> verifyResponse = verify(phone, undeliveredCode);
        assertThat(verifyResponse.getStatusCode())
                .as("a burnt row must never be redeemable, body=%s", verifyResponse.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("should_return429OnFourthSend_when_threeSendsSucceeded")
    void should_return429OnFourthSend_when_threeSendsSucceeded() {
        String phone = "+380501000003";

        var first = send(phone);
        var second = send(phone);
        var third = send(phone);
        var fourth = send(phone);

        assertThat(java.util.List.of(first, second, third))
                .extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.OK);
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        Mockito.verify(otpSmsSender, Mockito.times(3)).send(eq(phone), anyString());
    }

    @Test
    @DisplayName("should_return429OnFourthSend_when_threeSendsFailedWith503")
    void should_return429OnFourthSend_when_threeSendsFailedWith503() {
        String phone = "+380501000004";
        doThrow(new SmsDeliveryException("provider down")).when(otpSmsSender).send(eq(phone), anyString());

        var first = send(phone);
        var second = send(phone);
        var third = send(phone);
        var fourth = send(phone);

        assertThat(java.util.List.of(first, second, third))
                .extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(fourth.getStatusCode())
                .as("failed sends still burn the per-phone budget, body=%s", fourth.getBody())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        Mockito.verify(otpSmsSender, Mockito.times(3)).send(eq(phone), anyString());
    }
}
