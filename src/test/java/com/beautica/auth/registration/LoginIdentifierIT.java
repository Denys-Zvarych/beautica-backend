package com.beautica.auth.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("POST /auth/login by phone or email (B514, AUTH-08 / AUTH-D09)")
class LoginIdentifierIT extends AbstractPhoneIdentityIT {

    private static final String LOGIN_URL = "/api/v1/auth/login";

    @Autowired
    private PasswordEncoder passwordEncoder;

    private void insertUser(String email, String phone, boolean phoneVerified, boolean emailVerified) {
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified, "
                        + "phone_number, phone_verified_at) VALUES (?, ?, ?, 'CLIENT', true, ?, ?, "
                        + (phoneVerified ? "now()" : "NULL") + ")",
                UUID.randomUUID(), email, passwordEncoder.encode(PASSWORD), emailVerified, phone);
    }

    private ResponseEntity<String> login(Map<String, Object> body) {
        return post(LOGIN_URL, body);
    }

    /** Bypasses {@code post(...)}, which pins X-Forwarded-For to the test's own IP. */
    private ResponseEntity<String> loginFrom(String clientIp, String phone, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", clientIp);
        return restTemplate.postForEntity(LOGIN_URL,
                new HttpEntity<>(Map.of("identifier", phone, "password", password), headers), String.class);
    }

    @Test
    @DisplayName("a verified phone signs in whether typed as +380… or as the national 0… form")
    void should_signIn_when_identifierIsVerifiedPhoneInE164OrNationalForm() {
        String phone = newPhone();
        insertUser(null, phone, true, false);

        ResponseEntity<String> e164 = login(Map.of("identifier", phone, "password", PASSWORD));
        ResponseEntity<String> national = login(Map.of("identifier", "0" + phone.substring(4), "password", PASSWORD));

        assertThat(e164.getStatusCode()).as("body: %s", e164.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(national.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(e164).at("/data/accessToken").asText()).isNotBlank();
    }

    @Test
    @DisplayName("an email signs in case-insensitively via identifier and via the legacy email field")
    void should_signIn_when_identifierIsEmailOrLegacyEmailField() {
        String email = "olena-" + System.nanoTime() + "@example.com";
        insertUser(email, null, false, true);

        ResponseEntity<String> byIdentifier = login(Map.of("identifier", email.toUpperCase(), "password", PASSWORD));
        ResponseEntity<String> legacyBody = login(Map.of("email", email, "password", PASSWORD));

        assertThat(byIdentifier.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(legacyBody.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an unverified phone gets the exact 401 body of a wrong password (mutation guard for the "
            + "verified-only lookup)")
    void should_return401IdenticalToWrongPassword_when_phoneUnverified() {
        String unverified = newPhone();
        String verified = newPhone();
        insertUser("u-" + System.nanoTime() + "@example.com", unverified, false, true);
        insertUser(null, verified, true, false);

        ResponseEntity<String> unverifiedLogin = login(Map.of("identifier", unverified, "password", PASSWORD));
        ResponseEntity<String> wrongPassword = login(Map.of("identifier", verified, "password", "Wr0ng!Passw0rd#"));

        assertThat(unverifiedLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode unverifiedBody = ((ObjectNode) json(unverifiedLogin)).without("requestId");
        JsonNode wrongPasswordBody = ((ObjectNode) json(wrongPassword)).without("requestId");
        assertThat(unverifiedBody.toString()).isEqualTo(wrongPasswordBody.toString());
    }

    @Test
    @DisplayName("a login with neither identifier nor email is a 400")
    void should_return400_when_neitherIdentifierNorEmailSent() {
        ResponseEntity<String> response = login(Map.of("password", PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("ten failures from one IP lock that IP out, while the owner still signs in from another IP")
    void should_keepLockOnAttackerIpOnly_when_tenFailuresFromOneIp() {
        String phone = newPhone();
        insertUser(null, phone, true, false);
        String attackerIp = "203.0.113." + (System.nanoTime() % 200 + 1);
        String ownerIp = "198.51.100." + (System.nanoTime() % 200 + 1);
        for (int i = 0; i < 10; i++) {
            assertThat(loginFrom(attackerIp, phone, "Wr0ng!Passw0rd#").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> owner = loginFrom(ownerIp, phone, PASSWORD);
        ResponseEntity<String> attacker = loginFrom(attackerIp, phone, PASSWORD);

        assertThat(owner.getStatusCode()).as("body: %s", owner.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(attacker.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
