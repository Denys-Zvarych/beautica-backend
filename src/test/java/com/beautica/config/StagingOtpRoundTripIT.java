package com.beautica.config;

import com.beautica.notification.sms.SmsService;
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

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 368 QA — proves a tester can actually USE the captured OTP: the code read from the capture mail
 * verifies over HTTP and yields a guest token (no bypass, the real generated code), a wrong code does not,
 * and booking-SMS text routed through {@link SmsService} is captured with zero Turbosms traffic.
 */
@DisplayName("prod,staging — captured OTP round trip + booking SMS capture (Phase 368 QA)")
class StagingOtpRoundTripIT extends AbstractStagingProfileIT {

    private static final Pattern CODE = Pattern.compile("(\\d{6})");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SmsService smsService;

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

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        return sent.getValue();
    }

    @Test
    @DisplayName("code read from the capture mail verifies via /book/otp/verify and returns a guest token")
    void should_issueGuestToken_when_capturedOtpIsSubmitted() throws Exception {
        String phone = randomPhone();
        assertThat(post("/api/v1/book/otp/send", "{\"phone\":\"" + phone + "\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        MimeMessage mail = sentMessage();
        Matcher code = CODE.matcher(mail.getContent().toString());
        assertThat(code.find()).as("six-digit OTP in capture mail").isTrue();

        ResponseEntity<String> verified = post("/api/v1/book/otp/verify",
                "{\"phone\":\"" + phone + "\",\"code\":\"" + code.group(1) + "\"}");

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).as("body=%s", verified.getBody()).contains("guestToken");
        assertThat(turbosmsStub().getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("a wrong (well-formed) code is rejected with 401 — no staging bypass code exists")
    void should_return401_when_wrongCodeSubmittedToStaging() throws Exception {
        String phone = randomPhone();
        post("/api/v1/book/otp/send", "{\"phone\":\"" + phone + "\"}");
        String real = CODE.matcher(sentMessage().getContent().toString()).results().findFirst().orElseThrow().group(1);
        String wrong = real.equals("123456") ? "654321" : "123456";

        ResponseEntity<String> verified = post("/api/v1/book/otp/verify",
                "{\"phone\":\"" + phone + "\",\"code\":\"" + wrong + "\"}");

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("booking SMS through SmsService (walk-in/confirmation path) — one capture mail, zero Turbosms hits")
    void should_captureBookingSms_when_smsServiceSends() throws Exception {
        smsService.send("+380671112233", "Ваш запис підтверджено на завтра о 10:00");

        MimeMessage mail = sentMessage();
        assertThat(mail.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("sms+380671112233@sms.staging.invalid");
        assertThat(mail.getSubject()).startsWith("[SMS] ");
        assertThat(mail.getContent().toString()).contains("Ваш запис підтверджено");
        assertThat(turbosmsStub().getAllServeEvents()).as("no call reached Turbosms").isEmpty();
    }
}
