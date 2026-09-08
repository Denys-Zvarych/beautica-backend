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
     * Phase 301 narrowed this case list to {@code SALON_OWNER} ONLY. Before phase 301,
     * {@code DELETE /api/v1/users/me} was {@code hasRole('CLIENT')}-only and every other role
     * (including {@code SALON_ADMIN}/{@code SALON_MASTER}/{@code INDEPENDENT_MASTER}) 403'd here.
     * Phase 301 widened the {@code @PreAuthorize} gate to admit those three roles — they now reach
     * the real self-delete cascade and are asserted 204 by {@code
     * StaffAccountSelfDeleteAuthorizationIT} instead. {@code SALON_OWNER} is the ONE role that
     * still 403s post-widening (D1 — {@code salons.owner_id} is {@code NOT NULL NO ACTION}, so an
     * owner can never self-delete through this route), so it is the only case left in the shipped
     * CLIENT-flow regression suite. Running this test with the pre-301 case list against the
     * widened endpoint would falsely fail for SALON_ADMIN/INDEPENDENT_MASTER (204, not 403) — a
     * regression discovered by this very QA pass, not a hypothetical.
     */
    @Test
    @DisplayName("SALON_OWNER gets 403, never reaches the deletion cascade, and its account row "
            + "survives untouched — the one role the widened endpoint still refuses (Phase 301 D1)")
    void should_return403_when_callerIsSalonOwner() throws Exception {
        String email = "csd-authz-" + System.nanoTime() + "@beautica.test";
        UUID actorId = csd.createUser(email, "SALON_OWNER", null, "Тест", "Власник");
        String token = fixtures.tokenFor(email);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);

        assertThat(response.getStatusCode())
                .as("SALON_OWNER must never reach the self-delete cascade")
                .isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("message").asText()).isEqualTo("Access denied");
        assertThat(csd.userExists(actorId))
                .as("a 403 must be a pure no-op — the account row is untouched")
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
