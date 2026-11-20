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
        // B516: forgot / change-password / email-code tests make several calls per test from one IP.
        "app.rate-limit.forgot-password-capacity=1000",
        "app.rate-limit.change-password-otp-capacity=1000",
        "app.rate-limit.resend-verification-capacity=1000",
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

    /** This test instance's client IP (IPv4, so it is also its per-network key, AUTH-D27 e). */
    protected String clientIp() {
        return clientIp;
    }

    /** As {@link #post(String, Object)} from another client address (a different network). */
    protected ResponseEntity<String> postFrom(String ip, String url, Object body) {
        return postWithIp(url, body, new HttpHeaders(), ip);
    }

    protected ResponseEntity<String> post(String url, Object body, HttpHeaders headers) {
        return postWithIp(url, body, headers, clientIp);
    }

    private ResponseEntity<String> postWithIp(String url, Object body, HttpHeaders headers, String ip) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", ip);
        try {
            return restTemplate.postForEntity(url, new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                    String.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── B516 helpers: seeded accounts + signed-in calls ─────────────────────────

    protected static final String LOGIN_URL = "/api/v1/auth/login";
    private static final org.springframework.security.crypto.password.PasswordEncoder FIXTURE_ENCODER =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);

    /**
     * Inserts an account. {@code email} / {@code verifiedPhone} may be null (not both — ck_users_login_identity);
     * {@code legacyPhone} is an unverified free-text phone_number (used only when {@code verifiedPhone} is null).
     */
    protected UUID seedUser(String role, String email, boolean emailVerified, String verifiedPhone,
                            String legacyPhone) {
        UUID id = UUID.randomUUID();
        String phone = verifiedPhone != null ? verifiedPhone : legacyPhone;
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, phone_number, "
                        + "phone_verified_at, is_active, email_verified) VALUES (?, ?, ?, ?, 'Тест', ?, ?, true, ?)",
                id, email, FIXTURE_ENCODER.encode(PASSWORD), role, phone,
                verifiedPhone == null ? null : Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                emailVerified);
        return id;
    }

    protected static String newEmail() {
        return "b516-" + UUID.randomUUID() + "@beautica.test";
    }

    /** Signs in with an email or a verified phone; returns the access token. */
    protected String accessToken(String identifier) {
        ResponseEntity<String> login = post(LOGIN_URL, Map.of("identifier", identifier, "password", PASSWORD));
        assertThat(login.getStatusCode().value()).as("login body: %s", login.getBody()).isEqualTo(200);
        return json(login).at("/data/accessToken").asText();
    }

    protected HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    protected ResponseEntity<String> postAs(String token, String url, Object body) {
        return post(url, body, bearer(token));
    }

    protected ResponseEntity<String> exchangeAs(String token, org.springframework.http.HttpMethod method, String url,
                                                Object body) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", clientIp);
        try {
            String payload = body == null ? null : objectMapper.writeValueAsString(body);
            return restTemplate.exchange(url, method, new HttpEntity<>(payload, headers), String.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Number of SMS sent to {@code phoneE164} so far in this test. */
    protected int smsCountTo(String phoneE164) {
        return Mockito.mockingDetails(otpSmsSender).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send") && phoneE164.equals(i.getArguments()[0]))
                .toList().size();
    }

    protected ResponseEntity<String> sendCode(String phone) {
        return post(SEND_URL, Map.of("phone", phone));
    }

    protected ResponseEntity<String> verifyCode(String phone, String code) {
        return post(VERIFY_URL, Map.of("phone", phone, "code", code));
    }

    /**
     * Phone-first hardening (AUTH-D27 a) — moves every code row of {@code phoneE164} 61 s into the past, as if the 60 s
     * per-phone+purpose resend cooldown had elapsed; the rows stay inside the 15-min send window and the 24 h
     * failed-verify window, so those caps still count them. No sleeping, no clock bean.
     */
    protected void elapseResendCooldown(String phoneE164) {
        jdbcTemplate.update("UPDATE phone_otps SET created_at = created_at - interval '61 seconds' WHERE phone = ?",
                phoneE164);
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

    /** B520 — the name every {@link #newSalon()} salon carries (Cyrillic, like a real one). */
    protected static final String SALON_NAME = "Студія Аврора";

    /** B520 — an active salon with a signed-in owner: ids and the owner's access token. */
    protected record Salon(UUID salonId, UUID ownerId, String ownerToken) {
    }

    /** B520 — a fresh active salon named {@link #SALON_NAME}, owned by a fresh signed-in SALON_OWNER. */
    protected Salon newSalon() {
        String ownerEmail = newEmail();
        UUID ownerId = seedUser("SALON_OWNER", ownerEmail, true, null, null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)", salonId, ownerId, SALON_NAME, testCityId());
        return new Salon(salonId, ownerId, accessToken(ownerEmail));
    }

    /** B520 — a pending PHONE invite: the raw link token, the invited phone and its salon. */
    protected record Invite(String rawToken, String phone, UUID salonId) {
    }

    /** An unused 48 h SALON_MASTER phone invite into a fresh active salon (B520: invites go to a phone). */
    protected Invite seedInvite() {
        return seedInvite(newPhone());
    }

    protected Invite seedInvite(String invitedPhone) {
        long nonce = System.nanoTime();
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                + "VALUES (?, ?, 'x', 'SALON_OWNER', true, true)", ownerId, "owner-" + nonce + "@beautica.test");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)", salonId, ownerId, "Salon " + nonce, testCityId());
        String rawToken = "raw-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO invite_tokens (id, token, phone, salon_id, role, expires_at, is_used, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'SALON_MASTER', ?, false, NOW(), NOW())",
                UUID.randomUUID(), sha256Hex(rawToken), invitedPhone, salonId,
                Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)));
        return new Invite(rawToken, invitedPhone, salonId);
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
