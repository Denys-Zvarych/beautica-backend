package com.beautica.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 389 QA — the guest OTP SMS end to end through the REAL capture transport ({@code prod,staging}:
 * {@code CapturingOtpSmsSender -> CapturingSmsSender -> JavaMailSender}), with the domain supplied the way
 * staging supplies it: the {@code OTP_SMS_DOMAIN} placeholder in {@code application-staging.yml}.
 *
 * <p>Pins what a tester reads in Mailpit: the exact two-paragraph body, the origin-bound {@code @host #code}
 * as the FINAL line, the same code in both lines, and that code being the one {@code /verify} accepts.
 * Only {@link JavaMailSender} is mocked (the SMTP hop); the template, properties binding and capture
 * adapter are real.
 */
@TestPropertySource(properties = "OTP_SMS_DOMAIN=" + StagingOtpDomainCaptureIT.STAGING_HOST)
@DisplayName("prod,staging + OTP_SMS_DOMAIN — captured guest OTP SMS carries the origin-bound last line (Phase 389 QA)")
class StagingOtpDomainCaptureIT extends AbstractStagingProfileIT {

    static final String STAGING_HOST = "stg.beautica.app";

    private static final Pattern BODY = Pattern.compile(
            "Код Beautica: (\\d{6})\\. Нікому не кажіть\\.\n\n@" + Pattern.quote(STAGING_HOST) + " #(\\d{6})");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JavaMailSender mailSender;

    @BeforeEach
    void stubMime() {
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
        turbosmsStub().resetRequests();
    }

    private static String randomPhone() {
        return "+380" + (50 + ThreadLocalRandom.current().nextInt(40))
                + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(path,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
    }

    private ResponseEntity<String> sendOtp(String phone) throws Exception {
        return post("/api/v1/book/otp/send", Map.of("phone", phone, "privacyAccepted", true,
                "privacyPolicyVersion", "2026-10-20", "turnstileToken", "XXXX.DUMMY.TOKEN.XXXX"));
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        return sent.getValue();
    }

    @Test
    @DisplayName("captured OTP body is exactly the two-paragraph template, final line `@stg.beautica.app #code`, same code twice")
    void should_captureExactDomainBoundBody_when_otpSmsDomainSetViaStagingPlaceholder() throws Exception {
        String phone = randomPhone();

        ResponseEntity<String> response = sendOtp(phone);

        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        MimeMessage mail = sentMessage();
        String body = mail.getContent().toString();
        Matcher m = BODY.matcher(body);
        assertThat(m.matches()).as("exact Phase 389 template through the capture transport, got: %s", body).isTrue();
        assertThat(m.group(2)).as("origin-bound line carries the same code as the first line").isEqualTo(m.group(1));
        assertThat(body.substring(body.lastIndexOf('\n') + 1))
                .as("WebOTP / Apple domain-bound codes need `@host #code` as the FINAL line")
                .isEqualTo("@" + STAGING_HOST + " #" + m.group(1));
        assertThat(body.length()).as("one UCS-2 segment").isEqualTo(50 + STAGING_HOST.length()).isLessThanOrEqualTo(70);
        assertThat(mail.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("sms" + phone + "@sms.staging.invalid");
        assertThat(mail.getSubject()).as("subject is the flattened first line, never the raw CR/LF text")
                .startsWith("[SMS] Код Beautica: " + m.group(1) + ". Нікому не кажіть.")
                .doesNotContain("\n");
        assertThat(turbosmsStub().getAllServeEvents()).as("no call reached Turbosms").isEmpty();
    }

    @Test
    @DisplayName("the code read from the captured domain-bound SMS is the one /book/otp/verify accepts")
    void should_verifyAndIssueGuestToken_when_codeReadFromDomainBoundCapture() throws Exception {
        String phone = randomPhone();
        sendOtp(phone);
        Matcher m = BODY.matcher(sentMessage().getContent().toString());
        assertThat(m.matches()).isTrue();

        ResponseEntity<String> verified = post("/api/v1/book/otp/verify", Map.of("phone", phone, "code", m.group(2)));

        assertThat(verified.getStatusCode()).as("body=%s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).contains("guestToken");
    }
}
