package com.beautica.appconfig.controller;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 437 D4 — {@code GET /api/v1/app/config} is charged per client IP on the existing anonymous
 * {@code catalogueBrowseBuckets} ({@code AuthRateLimitFilter}): capacity + 1 calls from one IP get 429, another
 * IP is still served. The capacity is lowered for this context only; the four property constants below are
 * shared verbatim with {@code AppConfigControllerIT.WithEnvOverrides} so both run in ONE cached context. Each test uses its own IP (via {@code X-Forwarded-For}, the resolver's rightmost
 * entry), so the singleton bucket cache never leaks budget across tests.
 *
 * <p>Valid-JWT path: the deferral/principal/authenticated-ceiling logic itself is pinned path-agnostically by
 * {@code CatalogueBrowseBearerDeferralTest} (real filters, real tokens); the one case here proves
 * {@code /api/v1/app/config} is wired into that deferral end to end.
 */
@TestPropertySource(properties = {
        AppConfigRateLimitIT.CAPACITY_PROPERTY,
        AppConfigRateLimitIT.PRINCIPAL_CAPACITY_PROPERTY,
        AppConfigRateLimitIT.MIN_PROPERTY,
        AppConfigRateLimitIT.LATEST_PROPERTY,
        AppConfigRateLimitIT.STORE_URL_PROPERTY})
@DisplayName("AppConfigRateLimitIT — per-IP throttle on GET /api/v1/app/config (Phase 437)")
class AppConfigRateLimitIT extends AbstractIntegrationTest {

    static final long CAPACITY = 3;
    static final String CAPACITY_PROPERTY = "app.rate-limit.catalogue-browse-capacity=" + CAPACITY;
    static final long PRINCIPAL_CAPACITY = 4;
    static final String PRINCIPAL_CAPACITY_PROPERTY =
            "app.rate-limit.catalogue-browse-principal-capacity=" + PRINCIPAL_CAPACITY;
    static final String MIN_PROPERTY = "app.client-version.android.min-version-code=41";
    static final String LATEST_PROPERTY = "app.client-version.android.latest-version-code=57";
    static final String STORE_URL_PROPERTY =
            "app.client-version.android.store-url=https://play.google.com/store/apps/details?id=com.beautica.app.test";

    private static final String PATH = "/api/v1/app/config";
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(4);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /** A unique client IP per call (TEST-NET-3). */
    static String nextIp() {
        int n = IP_SEQUENCE.incrementAndGet();
        return "203.0." + (n / 254) % 256 + "." + (n % 254 + 1);
    }

    private ResponseEntity<String> getFrom(String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", ip);
        return restTemplate.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** A call carrying a forged bearer — AuthRateLimitFilter defers it, BookingRateLimitFilter charges it per IP. */
    private ResponseEntity<String> getWithForgedBearerFrom(String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", ip);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer junk");
        return restTemplate.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** A call carrying a real access token for a persisted CLIENT (the JWT filter checks the user exists). */
    private ResponseEntity<String> getWithTokenFrom(String ip, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", ip);
        headers.setBearerAuth(token);
        return restTemplate.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String clientToken() {
        UUID userId = UUID.randomUUID();
        String email = "appconfig-rl-" + userId + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                userId, email, PASSWORD_ENCODER.encode("test-password"));
        return jwtTokenProvider.generateAccessToken(userId, email, Role.CLIENT);
    }

    @Test
    @DisplayName("valid JWT, anonymous IP budget spent → served on its own principal bucket, 429 only once THAT is "
            + "spent; a second account behind the same IP is still served")
    void should_chargePrincipalBucket_when_validJwtAfterAnonymousIpBudgetSpent() {
        String ip = nextIp();
        String token = clientToken();
        String otherToken = clientToken();
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(getFrom(ip).getStatusCode()).as("anonymous call %d", i + 1).isEqualTo(HttpStatus.OK);
        }
        assertThat(getFrom(ip).getStatusCode()).as("precondition: anonymous IP budget spent")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        for (int i = 0; i < PRINCIPAL_CAPACITY; i++) {
            assertThat(getWithTokenFrom(ip, token).getStatusCode())
                    .as("valid-JWT call %d within the principal budget", i + 1).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> principalOverBudget = getWithTokenFrom(ip, token);
        ResponseEntity<String> otherPrincipal = getWithTokenFrom(ip, otherToken);

        assertThat(principalOverBudget.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(principalOverBudget.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(otherPrincipal.getStatusCode()).as("another account's bucket is untouched").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("capacity + 1 anonymous calls from one IP → the extra call is 429; a second IP still gets 200")
    void should_return429ForOneIpOnly_when_budgetSpent() {
        String ip = nextIp();
        String otherIp = nextIp();

        for (int i = 0; i < CAPACITY; i++) {
            assertThat(getFrom(ip).getStatusCode()).as("call %d within budget", i + 1).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> overBudget = getFrom(ip);
        ResponseEntity<String> otherIpResponse = getFrom(otherIp);

        assertThat(overBudget.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(overBudget.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(otherIpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("capacity + 1 calls from one IP with a forged 'Bearer junk' → the extra call is 429 (no bypass)")
    void should_return429_when_forgedBearerExceedsIpBudget() {
        String ip = nextIp();

        for (int i = 0; i < CAPACITY; i++) {
            assertThat(getWithForgedBearerFrom(ip).getStatusCode())
                    .as("forged-bearer call %d within budget", i + 1).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> overBudget = getWithForgedBearerFrom(ip);

        assertThat(overBudget.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(overBudget.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }

    @Test
    @DisplayName("forged-bearer and anonymous calls from one IP drain the SAME per-IP bucket")
    void should_shareAnonymousBucket_when_forgedBearerMixedWithAnonymous() {
        String ip = nextIp();

        for (int i = 0; i < CAPACITY - 1; i++) {
            assertThat(getFrom(ip).getStatusCode()).as("anonymous call %d", i + 1).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> lastInBudget = getWithForgedBearerFrom(ip);
        ResponseEntity<String> overBudget = getFrom(ip);

        assertThat(lastInBudget.getStatusCode()).as("forged-bearer call spends the last token").isEqualTo(HttpStatus.OK);
        assertThat(overBudget.getStatusCode()).as("anonymous call after the forged one").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
