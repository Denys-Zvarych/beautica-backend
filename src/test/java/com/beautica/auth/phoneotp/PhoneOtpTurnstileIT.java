package com.beautica.auth.phoneotp;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.OtpSmsSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 382 — Turnstile on {@code POST /book/otp/send}, end to end with the verifier ENABLED and siteverify
 * stubbed by WireMock: a valid token reaches the OTP row + SMS; a rejected, missing or unverifiable token
 * creates NO {@code phone_otps} row and sends NO SMS (fail closed).
 */
@TestPropertySource(properties = {
        "app.turnstile.enabled=true",
        "app.turnstile.secret-key=0x4AAAAAAA-it-secret-value-9xQ",
        "app.rate-limit.otp-send-capacity=100000"
})
@Import(TestSecurityConfig.class)
@DisplayName("POST /book/otp/send — Turnstile gate with siteverify stubbed (Phase 382)")
class PhoneOtpTurnstileIT extends AbstractIntegrationTest {

    private static final String PATH = "/turnstile/v0/siteverify";
    private static WireMockServer siteverify;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private OtpSmsSender otpSmsSender;

    @BeforeAll
    static void startSiteverifyStub() {
        siteverify = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        siteverify.start();
    }

    @AfterAll
    static void stopSiteverifyStub() {
        siteverify.stop();
    }

    @DynamicPropertySource
    static void siteverifyUrl(DynamicPropertyRegistry registry) {
        registry.add("app.turnstile.siteverify-url", () -> "http://localhost:" + siteverify.port() + PATH);
    }

    @BeforeEach
    void resetStub() {
        siteverify.resetAll();
    }

    private void stubSiteverify(String body) {
        siteverify.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private ResponseEntity<String> send(String phone, String tokenField) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var random = ThreadLocalRandom.current();
        headers.set("X-Forwarded-For", "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "."
                + random.nextInt(1, 255));
        String body = "{\"phone\":\"" + phone + "\",\"privacyAccepted\":true,\"privacyPolicyVersion\":\"2026-10-20\""
                + tokenField + "}";
        return restTemplate.postForEntity("/api/v1/book/otp/send", new HttpEntity<>(body, headers), String.class);
    }

    private static String token(String value) {
        return ",\"turnstileToken\":\"" + value + "\"";
    }

    private int otpRows(String phone) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otps WHERE phone = ?", Integer.class, phone);
    }

    @Test
    @DisplayName("a valid token -> 200, one phone_otps row, one SMS dispatched")
    void should_sendOtp_when_siteverifySucceeds() {
        String phone = "+380671000001";
        stubSiteverify("{\"success\":true,\"hostname\":\"beautica.app\"}");

        ResponseEntity<String> response = send(phone, token("good-token"));

        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(otpRows(phone)).isEqualTo(1);
        verify(otpSmsSender, times(1)).send(org.mockito.ArgumentMatchers.eq(phone), anyString());
        siteverify.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("a rejected token -> 400 'Verification failed', ZERO phone_otps rows, the SMS sender is never invoked")
    void should_rejectAndSendNothing_when_siteverifyRejects() throws Exception {
        String phone = "+380671000002";
        stubSiteverify("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");

        ResponseEntity<String> response = send(phone, token("bad-token"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).path("message").asText()).isEqualTo("Verification failed");
        assertThat(otpRows(phone)).isZero();
        verify(otpSmsSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("siteverify down (connection reset) -> 503, zero rows, no SMS — fail closed")
    void should_failClosedWith503_when_siteverifyIsDown() throws Exception {
        String phone = "+380671000003";
        siteverify.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ResponseEntity<String> response = send(phone, token("any-token"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(objectMapper.readTree(response.getBody()).path("message").asText())
                .isEqualTo(ServiceUnavailableMessages.TURNSTILE_UNAVAILABLE);
        assertThat(otpRows(phone)).isZero();
        verify(otpSmsSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("siteverify answering 500 -> 503, zero rows, no SMS")
    void should_failClosedWith503_when_siteverifyAnswers500() {
        String phone = "+380671000004";
        siteverify.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        ResponseEntity<String> response = send(phone, token("any-token"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(otpRows(phone)).isZero();
        verify(otpSmsSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("missing or blank token while enabled -> 400 without any siteverify call, no row, no SMS")
    void should_reject400WithoutCallingCloudflare_when_tokenMissing() {
        String phone = "+380671000005";
        stubSiteverify("{\"success\":true}");

        ResponseEntity<String> missing = send(phone, "");
        ResponseEntity<String> blank = send(phone, token("  "));

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(otpRows(phone)).isZero();
        verify(otpSmsSender, never()).send(anyString(), anyString());
        siteverify.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }
}
