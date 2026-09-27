package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Residual claim in the phase doc ("re-registration is unblocked automatically... since nothing
 * outside {@code users} uniquely constrains it") — proven against a real {@code POST
 * /auth/register}, not asserted from reading the migration comment. {@code existsByEmail} reads
 * the (now genuinely empty) {@code users} table directly, and no other table carries a UNIQUE
 * constraint on email or phone, so both must be freely reusable the instant the hard delete commits.
 */
@DisplayName("POST /auth/register — the same email/phone register cleanly after a CLIENT "
        + "self-delete (Phase 300 residual claim)")
class ClientReregistrationAfterSelfDeleteIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @Test
    @DisplayName("the same email is rejected 409 while the account exists, then accepted 200 the "
            + "instant the account is hard-deleted — same phone number too")
    void should_allowSameEmailAndPhoneToRegister_when_priorAccountWasSelfDeleted() throws Exception {
        String email = "csd-reuse-" + System.nanoTime() + "@beautica.test";
        String phone = "+380501234567";
        UUID clientId = csd.createUser(email, "CLIENT", null, "Оксана", "Іванова");
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", phone, clientId);
        String token = fixtures.tokenFor(email);

        RegisterRequest duplicateAttempt = new RegisterRequest(
                email, "AnotherStr0ng!Pass1", SelfRegistrationRole.CLIENT,
                "Марія", "Петренко", phone, null);
        ResponseEntity<String> beforeDelete = restTemplate.postForEntity(
                "/api/v1/auth/register", duplicateAttempt, String.class);
        assertThat(beforeDelete.getStatusCode())
                .as("control — the email is genuinely taken before the delete")
                .isEqualTo(HttpStatus.CONFLICT);

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);
        assertThat(csd.userExists(clientId)).isFalse();

        ResponseEntity<String> afterDelete = restTemplate.postForEntity(
                "/api/v1/auth/register", duplicateAttempt, String.class);

        assertThat(afterDelete.getStatusCode())
                .as("existsByEmail now reads an empty row for this address — the SAME payload "
                        + "that 409'd above must succeed")
                .isEqualTo(HttpStatus.OK);
        assertThat(csd.count("SELECT COUNT(*) FROM users WHERE email = ?", email))
                .as("exactly one (the NEW) row holds the address — not zero, not two")
                .isEqualTo(1);
        Integer phoneCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE phone_number = ? AND email = ?",
                Integer.class, phone, email);
        assertThat(phoneCount)
                .as("the same phone number is reusable too — no unique constraint on it")
                .isEqualTo(1);
    }
}
