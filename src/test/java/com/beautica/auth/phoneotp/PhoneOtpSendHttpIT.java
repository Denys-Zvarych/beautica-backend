package com.beautica.auth.phoneotp;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.SmsDeliveryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
 *
 * <p>Phase 389: {@code beautica.sms.otp-domain} is set so the captured SMS text must end with the
 * origin-bound {@code @host #code} line.
 */
@TestPropertySource(properties = {
        "app.rate-limit.otp-send-capacity=100000",
        "beautica.sms.otp-domain=" + PhoneOtpSendHttpIT.OTP_DOMAIN})
@Import(TestSecurityConfig.class)
@DisplayName("POST /book/otp/send — failure and cap behaviour over HTTP (Phase 364)")
class PhoneOtpSendHttpIT extends AbstractIntegrationTest {

    static final String OTP_DOMAIN = "beautica.app";
    private static final String OLD_CODE = "424242";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

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
        return restTemplate.postForEntity("/api/v1/book/otp/send", json("{\"phone\":\"" + phone + "\",\"privacyAccepted\":true,\"privacyPolicyVersion\":\"2026-10-20\"}"), String.class);
    }

    /** Phase 440 — body via Jackson (Q16), so a padded/odd {@code smsFormat} is sent exactly as given. */
    private ResponseEntity<String> sendWithFormat(String phone, String smsFormat) {
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of("phone", phone, "privacyAccepted", true,
                    "privacyPolicyVersion", "2026-10-20", "smsFormat", smsFormat));
            return restTemplate.postForEntity("/api/v1/book/otp/send", json(body), String.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private double sentCount(OtpSmsFormat format) {
        return meterRegistry.counter(PhoneOtpService.SENT_METRIC, "format", format.metricTag()).count();
    }

    private String capturedSms(String phone) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        Mockito.verify(otpSmsSender).send(eq(phone), text.capture());
        return text.getValue();
    }

    private static String codeIn(String sms) {
        return sms.replaceAll("(?s).*?(\\d{6}).*", "$1");
    }

    private double fallbackCount() {
        return meterRegistry.counter(PhoneOtpService.FORMAT_FALLBACK_METRIC).count();
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
        String undeliveredCode = text.getValue().replaceAll("(?s).*?(\\d{6}).*", "$1");
        ResponseEntity<String> verifyResponse = verify(phone, undeliveredCode);
        assertThat(verifyResponse.getStatusCode())
                .as("a burnt row must never be redeemable, body=%s", verifyResponse.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("otp-domain set — SMS ends with `@beautica.app #code` and that code verifies")
    void should_sendTextEndingWithDomainBoundLine_when_otpDomainConfigured() {
        String phone = "+380501000005";
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);

        ResponseEntity<String> response = send(phone);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Mockito.verify(otpSmsSender).send(eq(phone), text.capture());
        String sms = text.getValue();
        String code = sms.replaceAll("(?s).*?(\\d{6}).*", "$1");
        assertThat(sms).isEqualTo(OtpSmsExpectedCopy.domainBound(code, OTP_DOMAIN));
        assertThat(sms.lines().reduce((first, second) -> second).orElseThrow())
                .as("the origin-bound line must be the final line (WebOTP / Apple domain-bound codes)")
                .isEqualTo("@" + OTP_DOMAIN + " #" + code);
        assertThat(sms.length()).as("one UCS-2 segment").isLessThanOrEqualTo(70);
        assertThat(verify(phone, code).getStatusCode())
                .as("the code in the SMS is the one that verifies")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("phase 440: smsFormat PLAIN — exact human line only (43 chars), no fallback counted")
    void should_sendPlainText_when_smsFormatPlain() {
        String phone = "+380501000006";
        double fallbackBefore = fallbackCount();

        ResponseEntity<String> response = sendWithFormat(phone, "PLAIN");

        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        String sms = capturedSms(phone);
        assertThat(sms).isEqualTo(OtpSmsExpectedCopy.plain(codeIn(sms))).hasSize(43);
        assertThat(fallbackCount()).isEqualTo(fallbackBefore);
    }

    @Test
    @DisplayName("phase 440: smsFormat missing — byte-identical domain-bound text, no fallback counted")
    void should_sendDomainBoundText_when_smsFormatMissing() {
        String phone = "+380501000007";
        double fallbackBefore = fallbackCount();

        ResponseEntity<String> response = send(phone);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sms = capturedSms(phone);
        assertThat(sms).isEqualTo(OtpSmsExpectedCopy.domainBound(codeIn(sms), OTP_DOMAIN));
        assertThat(fallbackCount()).isEqualTo(fallbackBefore);
    }

    @Test
    @DisplayName("phase 440: unknown smsFormat FOO — 200 (never 400), domain-bound text, fallback +1")
    void should_sendDomainBoundTextAndCountFallback_when_smsFormatUnknown() {
        String phone = "+380501000008";
        double fallbackBefore = fallbackCount();

        ResponseEntity<String> response = sendWithFormat(phone, "FOO");

        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        String sms = capturedSms(phone);
        assertThat(sms).isEqualTo(OtpSmsExpectedCopy.domainBound(codeIn(sms), OTP_DOMAIN));
        assertThat(fallbackCount()).isEqualTo(fallbackBefore + 1);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @org.junit.jupiter.params.provider.CsvSource(value = {
            "'',            DOMAIN_BOUND, +380501000011",
            "'   ',         DOMAIN_BOUND, +380501000012",
            "' pLaIn ',     PLAIN,        +380501000013",
            "domain_bound,  DOMAIN_BOUND, +380501000014"})
    @DisplayName("phase 440: blank / mixed-case padded / lower-case values over HTTP — exact text, counter tag, no fallback")
    void should_sendExactTextAndCountTag_when_smsFormatBlankOrMixedCase(String smsFormat, OtpSmsFormat expected,
                                                                         String phone) {
        double fallbackBefore = fallbackCount();
        double sentBefore = sentCount(expected);

        ResponseEntity<String> response = sendWithFormat(phone, smsFormat);

        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        String sms = capturedSms(phone);
        String code = codeIn(sms);
        assertThat(sms).isEqualTo(expected == OtpSmsFormat.PLAIN
                ? OtpSmsExpectedCopy.plain(code) : OtpSmsExpectedCopy.domainBound(code, OTP_DOMAIN));
        assertThat(sentCount(expected)).as("beautica.otp_sms.sent{format=%s}", expected.metricTag())
                .isEqualTo(sentBefore + 1);
        assertThat(fallbackCount()).as("blank or recognised is never a fallback").isEqualTo(fallbackBefore);
    }

    @Test
    @DisplayName("phase 440: smsFormat of 33 chars over HTTP — 400 standard body, value not echoed, no SMS, no row")
    void should_return400WithoutSending_when_smsFormatLongerThan32() throws Exception {
        String phone = "+380501000015";
        String tooLong = "Z".repeat(33);
        double fallbackBefore = fallbackCount();

        ResponseEntity<String> response = sendWithFormat(phone, tooLong);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("success").asBoolean(true)).isFalse();
        assertThat(body.path("message").asText()).isEqualTo("Validation failed — check request parameters");
        assertThat(body.path("errors").path("smsFormat").asText()).isEqualTo("size must be between 0 and 32");
        assertThat(response.getBody()).as("the rejected value is never echoed").doesNotContain(tooLong);
        Mockito.verifyNoInteractions(otpSmsSender);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM phone_otps WHERE phone = ?", Integer.class, phone))
                .as("validation runs before any OTP row is issued").isZero();
        assertThat(fallbackCount()).isEqualTo(fallbackBefore);
    }

    @Test
    @DisplayName("phase 440: PLAIN over HTTP increments beautica.otp_sms.sent{format=plain} only")
    void should_incrementPlainSentCounterOnly_when_plainSent() {
        String phone = "+380501000016";
        double plainBefore = sentCount(OtpSmsFormat.PLAIN);
        double domainBefore = sentCount(OtpSmsFormat.DOMAIN_BOUND);

        ResponseEntity<String> response = sendWithFormat(phone, "PLAIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sentCount(OtpSmsFormat.PLAIN)).isEqualTo(plainBefore + 1);
        assertThat(sentCount(OtpSmsFormat.DOMAIN_BOUND)).isEqualTo(domainBefore);
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
