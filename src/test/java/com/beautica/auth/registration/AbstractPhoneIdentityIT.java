package com.beautica.auth.registration;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.OtpSmsSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;

/**
 * B514 — shared wiring for the phone-first identity ITs (one cached context): the SMS sender is a mock whose
 * captured text yields the code, the per-IP OTP send bucket is raised (the per-PHONE cap is what the tests pin) and
 * every request carries a fresh {@code X-Forwarded-For} so the 10 / 15 min per-IP verify bucket never couples tests.
 * App Check stays OFF (application-test.yml); {@code AppCheckGuardIT} / {@code AppCheckLogOnlyIT} turn it on.
 */
@TestPropertySource(properties = {
        "app.rate-limit.otp-send-capacity=100000",
        // B514 QA: PhoneOnlyAccountJourneyIT posts the support form; without an inbox it answers 503.
        "app.support.email=" + AbstractPhoneIdentityIT.SUPPORT_INBOX})
@Import(TestSecurityConfig.class)
public abstract class AbstractPhoneIdentityIT extends AbstractIntegrationTest {

    protected static final String SEND_URL = "/api/v1/auth/register/phone/otp/send";
    protected static final String VERIFY_URL = "/api/v1/auth/register/phone/otp/verify";
    protected static final String PASSWORD = "Str0ng!Passw0rd#";
    protected static final String SUPPORT_INBOX = "support-inbox@beautica.test";

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @MockBean
    protected OtpSmsSender otpSmsSender;

    private static final java.util.concurrent.atomic.AtomicInteger IP_SEQUENCE = new java.util.concurrent.atomic.AtomicInteger();

    /** Unique per test instance (never reused in a JVM), so per-IP buckets never couple two tests. */
    private final String clientIp = nextClientIp();

    private static String nextClientIp() {
        int n = IP_SEQUENCE.incrementAndGet();
        return "10." + (n / 62_500 % 250) + "." + (n / 250 % 250) + "." + (n % 250 + 1);
    }

    protected ResponseEntity<String> post(String url, Object body) {
        return post(url, body, new HttpHeaders());
    }

    protected ResponseEntity<String> post(String url, Object body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", clientIp);
        try {
            return restTemplate.postForEntity(url, new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                    String.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    protected ResponseEntity<String> sendCode(String phone) {
        return post(SEND_URL, Map.of("phone", phone));
    }

    protected ResponseEntity<String> verifyCode(String phone, String code) {
        return post(VERIFY_URL, Map.of("phone", phone, "code", code));
    }

    /** The 6-digit code of the LAST SMS sent to {@code phoneE164}. */
    protected String lastCodeSentTo(String phoneE164) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        Mockito.verify(otpSmsSender, Mockito.atLeastOnce()).send(eq(phoneE164), text.capture());
        List<String> texts = text.getAllValues();
        return texts.get(texts.size() - 1).replaceAll("(?s).*?(\\d{6}).*", "$1");
    }

    /** send → capture code → verify; returns the registration ticket. */
    protected String ticketFor(String phoneE164) {
        assertThat(sendCode(phoneE164).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> verified = verifyCode(phoneE164, lastCodeSentTo(phoneE164));
        assertThat(verified.getStatusCode().value()).as("verify body: %s", verified.getBody()).isEqualTo(200);
        return json(verified).at("/data/registrationTicket").asText();
    }

    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fresh Kyivstar-range number per call, so tests never collide on the per-phone cap or the unique index. */
    protected static String newPhone() {
        return "+38067" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    protected Map<String, Object> consent(Map<String, Object> body) {
        var withConsent = new java.util.HashMap<>(body);
        withConsent.put("termsAccepted", true);
        withConsent.put("termsVersion", "2026-10-20");
        withConsent.put("privacyPolicyVersion", "2026-10-20");
        return withConsent;
    }

    protected record Invite(String rawToken, String email) {
    }

    /** An unused 48 h SALON_MASTER invite into a fresh active salon (moved here from InviteAcceptPhoneIT). */
    protected Invite seedInvite() {
        long nonce = System.nanoTime();
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                + "VALUES (?, ?, 'x', 'SALON_OWNER', true, true)", ownerId, "owner-" + nonce + "@beautica.test");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)", salonId, ownerId, "Salon " + nonce, testCityId());
        String email = "invitee-" + nonce + "@beautica.test";
        String rawToken = "raw-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'SALON_MASTER', ?, false, NOW(), NOW())",
                UUID.randomUUID(), sha256Hex(rawToken), email, salonId,
                Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)));
        return new Invite(rawToken, email);
    }

    protected static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
