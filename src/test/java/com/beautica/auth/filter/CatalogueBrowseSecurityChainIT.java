package com.beautica.auth.filter;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.AccessTokenDenylist;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * <b>Filter-ORDER regression net (security re-audit 2026-10-05, LOW).</b>
 *
 * <p>{@link CatalogueBrowseBearerDeferralTest} hand-assembles the three filters in production order,
 * so it stays green if {@code SecurityConfig} itself is reordered — e.g. {@code BookingRateLimitFilter}
 * registered BEFORE {@code JwtAuthenticationFilter}. In that order the deferred catalogue-browse GET
 * reaches the booking filter with no {@code Authentication} yet, every valid-bearer caller silently
 * lands on the anonymous per-IP bucket, and the B8 CGNAT starvation is back. This class drives the
 * REAL Spring Security filter chain via {@link MockMvc}, so that reorder fails here.
 *
 * <p>Only the per-principal capacity ({@link #PRINCIPAL_CAPACITY}) and the authenticated per-IP
 * ceiling ({@link #AUTHENTICATED_IP_CEILING}) are lowered — one extra cached context, every other cap
 * keeps its {@code application-test.yml} value. Each test uses its own
 * random client IP and principal, so the singleton bucket caches never leak budget across tests.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.rate-limit.catalogue-browse-principal-capacity=" + CatalogueBrowseSecurityChainIT.PRINCIPAL_CAPACITY,
        "app.rate-limit.catalogue-browse-authenticated-ip-capacity="
                + CatalogueBrowseSecurityChainIT.AUTHENTICATED_IP_CEILING})
@DisplayName("Catalogue-browse deferral through the REAL security filter chain")
class CatalogueBrowseSecurityChainIT extends AbstractIntegrationTest {

    static final long PRINCIPAL_CAPACITY = 3;
    /** Must stay above {@link #PRINCIPAL_CAPACITY} + 1: the principal-bucket test spends that many. */
    static final long AUTHENTICATED_IP_CEILING = 5;

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(4);
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AccessTokenDenylist accessTokenDenylist;

    @Autowired
    @Qualifier("catalogueBrowsePrincipalBuckets")
    private LoadingCache<String, Bucket> principalBuckets;

    @Autowired
    @Qualifier("catalogueBrowseBuckets")
    private LoadingCache<String, Bucket> perIpBuckets;

    @Autowired
    @Qualifier("catalogueBrowseAuthenticatedIpBuckets")
    private LoadingCache<String, Bucket> authenticatedIpBuckets;

    @Test
    @DisplayName("a valid bearer on GET /salons/{id} is charged on the PRINCIPAL bucket (JWT ran first) "
            + "and 429s at that cap — never on the anonymous per-IP bucket")
    void should_chargePrincipalBucket_when_validBearerThroughRealSecurityChain() throws Exception {
        UUID userId = insertClient();
        String token = jwtTokenProvider.generateAccessToken(userId, emailOf(userId), Role.CLIENT);
        String ip = randomIp();
        long ipTokensBefore = perIpBuckets.get(ip).getAvailableTokens();
        long ceilingTokensBefore = authenticatedIpBuckets.get(ip).getAvailableTokens();

        for (int i = 0; i < PRINCIPAL_CAPACITY; i++) {
            int status = status(profileGet(ip).header("Authorization", "Bearer " + token));
            assertThat(status).as("request %d within the principal budget", i + 1).isNotEqualTo(429);
        }
        int overBudget = status(profileGet(ip).header("Authorization", "Bearer " + token));

        assertThat(principalBuckets.get(userId.toString()).getAvailableTokens())
                .as("every valid-bearer request must be charged to the principal — if this is still "
                        + "full, BookingRateLimitFilter ran before JwtAuthenticationFilter")
                .isZero();
        assertThat(overBudget).isEqualTo(429);
        assertThat(perIpBuckets.get(ip).getAvailableTokens())
                .as("authenticated traffic never draws on the anonymous per-IP bucket (B8)")
                .isEqualTo(ipTokensBefore);
        assertThat(authenticatedIpBuckets.get(ip).getAvailableTokens())
                .as("the authenticated per-IP ceiling is charged once per ALLOWED bearer request — the "
                        + "principal-denied request short-circuits before the ceiling")
                .isEqualTo(ceilingTokensBefore - PRINCIPAL_CAPACITY);
    }

    @Test
    @DisplayName("a validly-signed token whose jti was REVOKED (real denylist) falls back to the per-IP "
            + "bucket and never touches the principal bucket")
    void should_chargePerIpBucket_when_tokenRevokedThroughRealSecurityChain() throws Exception {
        UUID userId = insertClient();
        String token = jwtTokenProvider.generateAccessToken(userId, emailOf(userId), Role.CLIENT);
        accessTokenDenylist.revoke(jwtTokenProvider.getJti(token));
        String ip = randomIp();
        long ipTokensBefore = perIpBuckets.get(ip).getAvailableTokens();

        status(profileGet(ip).header("Authorization", "Bearer " + token));

        assertThat(perIpBuckets.get(ip).getAvailableTokens()).isEqualTo(ipTokensBefore - 1);
        assertThat(principalBuckets.get(userId.toString()).getAvailableTokens()).isEqualTo(PRINCIPAL_CAPACITY);
        assertThat(authenticatedIpBuckets.get(ip).getAvailableTokens())
                .as("a token that did not authenticate never spends the authenticated ceiling")
                .isEqualTo(AUTHENTICATED_IP_CEILING);
    }

    @Test
    @DisplayName("a validly-signed token for a DELETED account (no users row) falls back to the per-IP "
            + "bucket and never touches the principal bucket")
    void should_chargePerIpBucket_when_tokenSubjectDeletedThroughRealSecurityChain() throws Exception {
        UUID userId = insertClient();
        String token = jwtTokenProvider.generateAccessToken(userId, emailOf(userId), Role.CLIENT);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        String ip = randomIp();
        long ipTokensBefore = perIpBuckets.get(ip).getAvailableTokens();

        status(profileGet(ip).header("Authorization", "Bearer " + token));

        assertThat(perIpBuckets.get(ip).getAvailableTokens()).isEqualTo(ipTokensBefore - 1);
        assertThat(principalBuckets.get(userId.toString()).getAvailableTokens()).isEqualTo(PRINCIPAL_CAPACITY);
    }

    @Test
    @DisplayName("a valid token under a lowercase 'bearer' scheme is anonymous traffic — charged per IP, "
            + "never deferred to the principal bucket")
    void should_chargePerIpBucket_when_bearerSchemeLowercaseThroughRealSecurityChain() throws Exception {
        UUID userId = insertClient();
        String token = jwtTokenProvider.generateAccessToken(userId, emailOf(userId), Role.CLIENT);
        String ip = randomIp();
        long ipTokensBefore = perIpBuckets.get(ip).getAvailableTokens();

        status(profileGet(ip).header("Authorization", "bearer " + token));

        assertThat(perIpBuckets.get(ip).getAvailableTokens()).isEqualTo(ipTokensBefore - 1);
        assertThat(principalBuckets.get(userId.toString()).getAvailableTokens()).isEqualTo(PRINCIPAL_CAPACITY);
    }

    @Test
    @DisplayName("re-audit follow-up: a forged-bearer flood above the authenticated ceiling from one IP "
            + "does not drain it — a valid bearer from the SAME IP is still served")
    void should_not429ValidBearer_when_forgedBearerFloodExhaustsSameIp() throws Exception {
        UUID userId = insertClient();
        String token = jwtTokenProvider.generateAccessToken(userId, emailOf(userId), Role.CLIENT);
        String ip = randomIp();
        long junkRequests = AUTHENTICATED_IP_CEILING + 1;
        long anonymousIpTokensBefore = perIpBuckets.get(ip).getAvailableTokens();
        for (int i = 0; i < junkRequests; i++) {
            status(rosterGet(ip).header("Authorization", "Bearer x"));
        }
        long anonymousIpTokensAfterFlood = perIpBuckets.get(ip).getAvailableTokens();

        int status = status(rosterGet(ip).header("Authorization", "Bearer " + token));

        assertThat(status)
                .as("the ceiling is charged post-JWT for authenticated principals only — junk bearers "
                        + "must not spend it, or a CGNAT IP's real users are starved (B8)")
                .isEqualTo(200);
        assertThat(authenticatedIpBuckets.get(ip).getAvailableTokens())
                .isEqualTo(AUTHENTICATED_IP_CEILING - 1);
        assertThat(anonymousIpTokensAfterFlood)
                .as("every junk bearer the anonymous per-IP bucket could absorb was charged THERE — "
                        + "proves where the flood went, not just where it did not go")
                .isEqualTo(Math.max(0, anonymousIpTokensBefore - junkRequests));
    }

    /** GET /masters/by-salon/{id} — a deferred public-profile read that is a 200 empty page for any id. */
    private MockHttpServletRequestBuilder rosterGet(String ip) {
        return get("/api/v1/masters/by-salon/" + UUID.randomUUID()).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private MockHttpServletRequestBuilder profileGet(String ip) {
        return get("/api/v1/salons/" + UUID.randomUUID()).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private UUID insertClient() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                userId, emailOf(userId), PASSWORD_ENCODER.encode("test-password"));
        return userId;
    }

    private static String emailOf(UUID userId) {
        return "chain-" + userId + "@beautica.test";
    }

    /** A unique client IP per call (TEST-NET-2), so singleton bucket caches never share budget across tests. */
    private static String randomIp() {
        int n = IP_SEQUENCE.incrementAndGet();
        return "198.51." + (n / 254) % 256 + "." + (n % 254 + 1);
    }
}
