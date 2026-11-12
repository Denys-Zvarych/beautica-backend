package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * D5 (CLIENT role only) and D2 (no id in the request — the principal is the ONLY source) against
 * the real security filter chain + {@code @PreAuthorize}, not a mocked {@code Authentication}.
 * {@code ClientAccountDeletionServiceTest} already unit-tests the service-layer defence-in-depth
 * re-check; this class is what actually exercises {@code @PreAuthorize("hasRole('CLIENT')")} and
 * the unauthenticated entry point.
 */
@DisplayName("DELETE /api/v1/users/me — authorization (Phase 300 D5/D2)")
class ClientAccountSelfDeleteAuthorizationIT extends AbstractIntegrationTest {

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

    /**
     * Phase 488 widened the gate to {@code SALON_OWNER} (SUP-11): the owner is routed to
     * {@code OwnerAccountSelfDeletionService}, never to the CLIENT cascade. With an ACTIVE salon it is refused
     * 409 {@code OWNER_HAS_ACTIVE_SALONS} (was 403 before Phase 488) and the account row survives untouched.
     */
    @Test
    @DisplayName("SALON_OWNER with an active salon gets 409 OWNER_HAS_ACTIVE_SALONS, never reaches the CLIENT "
            + "cascade, and its account row survives untouched (Phase 488)")
    void should_return409_when_callerIsSalonOwnerWithActiveSalon() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        String email = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, salon.ownerId());
        String token = fixtures.tokenFor(email);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("data").path("code").asText()).isEqualTo("OWNER_HAS_ACTIVE_SALONS");
        assertThat(csd.userExists(salon.ownerId()))
                .as("a 409 must be a pure no-op — the account row is untouched")
                .isTrue();
    }

    @Test
    @DisplayName("no bearer token at all is refused 401, not 403 — distinct guards stay "
            + "independently distinguishable")
    void should_return401_when_unauthenticated() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the endpoint takes no id: a CLIENT authenticated as A can only ever delete A's "
            + "own row — B's account survives untouched by A's call")
    void should_onlyDeleteCallersOwnAccount_never_anotherClientsAccount() throws Exception {
        UUID clientA = csd.createClient();
        String tokenA = fixtures.tokenFor(emailOf(clientA));
        UUID clientB = csd.createClient();

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(tokenA)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(clientA))
                .as("the authenticated caller's OWN row is gone")
                .isFalse();
        assertThat(csd.userExists(clientB))
                .as("there is no id in the request the caller could have redirected the delete to "
                        + "— B is untouched")
                .isTrue();
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
