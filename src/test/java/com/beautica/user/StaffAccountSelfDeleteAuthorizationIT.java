package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full role matrix for {@code DELETE /api/v1/users/me} (Phase 301), against the real
 * {@code @PreAuthorize} gate + rate limiter, not a mocked service. {@code
 * ClientAccountSelfDeleteAuthorizationIT} owns the CLIENT-flow regression pin (SALON_OWNER 403,
 * self-only scoping); this class is the STAFF-flow counterpart the phase 301 plan's §11 asked for:
 * every one of the four newly-admitted roles actually reaches 204, SALON_OWNER is STILL refused,
 * anonymous is 401, and the per-user 3/hour bucket (role-blind, {@code BookingRateLimitFilter}) is
 * exercised end to end.
 */
@DisplayName("DELETE /api/v1/users/me — Phase 301 full role matrix + rate limit")
class StaffAccountSelfDeleteAuthorizationIT extends AbstractIntegrationTest {

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

    @ParameterizedTest(name = "{0} self-deletes successfully — 204, own account row gone")
    @ValueSource(strings = {"CLIENT", "SALON_ADMIN", "SALON_MASTER", "INDEPENDENT_MASTER"})
    @DisplayName("every one of the four newly-widened-or-shipped roles reaches 204")
    void should_return204_when_callerIsAnyAdmittedRole(String role) throws Exception {
        UUID actorId;
        String email;
        switch (role) {
            case "CLIENT" -> {
                email = "authz-matrix-" + System.nanoTime() + "@beautica.test";
                actorId = csd.createUser(email, "CLIENT", null, "Тест", "Клієнт");
            }
            case "INDEPENDENT_MASTER" -> {
                email = "authz-matrix-" + System.nanoTime() + "@beautica.test";
                UUID masterId = fixtures.createIndependentMaster(email);
                actorId = jdbcTemplate.queryForObject(
                        "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
            }
            case "SALON_MASTER" -> {
                // MUST reuse createSalon()'s OWN master row — a SALON_MASTER user created without
                // a matching `masters` row 403s at StaffAccountSelfDeletionService's step-5
                // defensive guard (ForbiddenException), which would look identical to an
                // authorization failure but is actually a fixture bug. Caught by this exact
                // mistake once already while writing this suite.
                ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
                actorId = salon.masterUserId();
                email = emailOf(actorId);
            }
            default -> { // SALON_ADMIN
                ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
                actorId = csd.createSalonAdmin(salon);
                email = emailOf(actorId);
            }
        }
        String token = fixtures.tokenFor(email);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode())
                .as("role %s must reach the real self-delete cascade and succeed", role)
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(actorId)).isFalse();
    }

    @Test
    @DisplayName("SALON_OWNER still gets 403 after the widening — the one role never admitted "
            + "(D1 — salons.owner_id is NOT NULL NO ACTION)")
    void should_return403_when_callerIsSalonOwner() throws Exception {
        String email = "authz-matrix-owner-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = csd.createUser(email, "SALON_OWNER", null, "Тест", "Власник");
        String token = fixtures.tokenFor(email);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("message").asText()).isEqualTo("Access denied");
        assertThat(csd.userExists(ownerId)).isTrue();
    }

    @Test
    @DisplayName("no bearer token → 401, not 403 — the auth guard is checked before any role gate")
    void should_return401_when_unauthenticated() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the per-user 3/hour self-delete bucket (role-blind, BookingRateLimitFilter) "
            + "throttles a 4th DELETE /users/me within the window with 429 — using SALON_OWNER, "
            + "whose calls always 403 and so never delete the account, letting one caller exhaust "
            + "the budget deterministically")
    void should_return429_when_fourthAttemptWithinTheHour() throws Exception {
        String email = "authz-matrix-ratelimit-" + System.nanoTime() + "@beautica.test";
        csd.createUser(email, "SALON_OWNER", null, "Тест", "Власник");
        String token = fixtures.tokenFor(email);
        HttpEntity<Void> authed = new HttpEntity<>(fixtures.bearerHeaders(token));

        for (int attempt = 1; attempt <= 3; attempt++) {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/v1/users/me", HttpMethod.DELETE, authed, String.class);
            assertThat(response.getStatusCode())
                    .as("attempt %d must still be under the bucket cap — 403 (role gate), not 429",
                            attempt)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        ResponseEntity<String> fourth = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE, authed, String.class);

        assertThat(fourth.getStatusCode())
                .as("the 4th attempt within the hour must be throttled BEFORE reaching the "
                        + "@PreAuthorize gate at all")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
