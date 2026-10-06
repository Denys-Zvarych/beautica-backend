package com.beautica.auth.filter;

import com.beautica.auth.AccessTokenDenylist;
import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.auth.TokenValidityState;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.booking.filter.BookingRateLimitFilter;
import com.beautica.config.JwtConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * <b>B8 regression net (2026-10-05)</b> — the bookability audit put {@code GET /salons/{id}} (and the
 * other public profile reads) on the anonymous per-IP {@code catalogueBrowseBuckets}, but the mobile
 * salon management screen ({@code salonManagementProfileProvider}) and the master/owner own-profile
 * screens call the same routes with a token. Under carrier-grade NAT, anonymous traffic from the
 * shared egress IP would then 429 an owner's management UI.
 *
 * <p>Drives the three REAL filters in production order — {@link AuthRateLimitFilter} (per-IP, before
 * JWT) → {@link JwtAuthenticationFilter} (real {@link JwtTokenProvider}, real signature check) →
 * {@link BookingRateLimitFilter} (per-principal, after JWT) — sharing ONE hand-built tiny per-IP
 * cache, so the deferral handshake between the two rate-limit filters is exercised end to end and
 * the {@code application-test.yml} capacity overrides cannot neuter it.
 */
@DisplayName("Catalogue-browse / public-profile GETs — per-principal for valid tokens, per-IP otherwise")
class CatalogueBrowseBearerDeferralTest {

    private static final String SECRET = "catalogue-browse-deferral-test-secret-0123456789";
    private static final String FORGER_SECRET = "an-attackers-own-signing-key-of-sufficient-len-99";
    private static final long ACCESS_TTL_MS = 900_000;
    private static final long REFRESH_TTL_MS = 86_400_000;
    private static final String SHARED_CGNAT_IP = "100.64.0.7";
    private static final String OTHER_IP = "100.64.0.8";
    private static final long IP_CAPACITY = 4;
    private static final long PRINCIPAL_CAPACITY = 6;
    /**
     * Authenticated per-IP ceiling (charged post-JWT, valid principals only) — above every other
     * test's valid-bearer volume (max 8).
     */
    private static final long AUTH_IP_CAPACITY = 10;

    private final JwtTokenProvider jwtTokenProvider =
            new JwtTokenProvider(new JwtConfig(SECRET, ACCESS_TTL_MS, REFRESH_TTL_MS), Clock.systemUTC());

    private LoadingCache<String, Bucket> perIpBuckets;
    private LoadingCache<String, Bucket> perPrincipalBuckets;
    private LoadingCache<String, Bucket> authenticatedIpBuckets;
    private TokensValidAfterCache tokensValidAfterCache;
    private AccessTokenDenylist accessTokenDenylist;
    private AuthRateLimitFilter authRateLimitFilter;
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    private BookingRateLimitFilter bookingRateLimitFilter;

    @BeforeEach
    void wireFilters() {
        perIpBuckets = AuthRateLimitFilterTestFactory.bucketsOf(IP_CAPACITY);
        perPrincipalBuckets = AuthRateLimitFilterTestFactory.bucketsOf(PRINCIPAL_CAPACITY);
        authenticatedIpBuckets = AuthRateLimitFilterTestFactory.bucketsOf(AUTH_IP_CAPACITY);
        authRateLimitFilter = AuthRateLimitFilterTestFactory.withBucket("catalogueBrowseBuckets", perIpBuckets);

        tokensValidAfterCache = Mockito.mock(TokensValidAfterCache.class);
        when(tokensValidAfterCache.get(any())).thenReturn(TokenValidityState.PRESENT_NO_RESET);
        accessTokenDenylist = Mockito.mock(AccessTokenDenylist.class);
        jwtAuthenticationFilter = new JwtAuthenticationFilter(
                jwtTokenProvider, accessTokenDenylist, tokensValidAfterCache);

        LoadingCache<String, Bucket> generous = AuthRateLimitFilterTestFactory.bucketsOf(1_000_000);
        bookingRateLimitFilter = new BookingRateLimitFilter(
                generous, generous, generous, generous, generous, generous, generous, generous,
                perPrincipalBuckets, perIpBuckets, authenticatedIpBuckets, new ObjectMapper());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Runs one request through the production-ordered chain; returns the final status. */
    private int send(String path, String bearer) throws Exception {
        return sendWithAuthorization(path, bearer == null ? null : "Bearer " + bearer);
    }

    /** Like {@link #send} but with the raw {@code Authorization} header value (scheme included). */
    private int sendWithAuthorization(String path, String authorization) throws Exception {
        return sendFrom(SHARED_CGNAT_IP, path, authorization);
    }

    /** Like {@link #sendWithAuthorization} but from an explicit client IP. */
    private int sendFrom(String remoteAddr, String path, String authorization) throws Exception {
        SecurityContextHolder.clearContext();
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(remoteAddr);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        var response = new MockHttpServletResponse();
        new MockFilterChain(new OkServlet(), authRateLimitFilter, jwtAuthenticationFilter, bookingRateLimitFilter)
                .doFilter(request, response);
        return response.getStatus();
    }

    private void exhaustAnonymousIpBucket() throws Exception {
        for (int i = 0; i < IP_CAPACITY; i++) {
            assertThat(send("/api/v1/salons/" + UUID.randomUUID(), null)).isEqualTo(200);
        }
        assertThat(send("/api/v1/salons/" + UUID.randomUUID(), null))
                .as("precondition: the shared CGNAT IP's anonymous budget is spent")
                .isEqualTo(429);
    }

    private String ownerToken() {
        return jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "owner@example.com", Role.SALON_OWNER);
    }

    @Test
    @DisplayName("an authenticated owner's GET /salons/{id} is NOT 429'd when anonymous traffic from the "
            + "same egress IP has exhausted the per-IP bucket")
    void should_serveAuthenticatedOwner_when_anonymousIpBucketExhausted() throws Exception {
        exhaustAnonymousIpBucket();
        String token = ownerToken();
        String salonPath = "/api/v1/salons/" + UUID.randomUUID();

        int status = send(salonPath, token);

        assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("an anonymous id sweep over the public profile reads still 429s at the per-IP cap")
    void should_return429_when_anonymousIdSweepExceedsPerIpCap() throws Exception {
        int allowed = 0;
        int lastStatus = 0;

        for (int i = 0; i < IP_CAPACITY + 3; i++) {
            String path = i % 2 == 0 ? "/api/v1/masters/" + UUID.randomUUID() : "/api/v1/salons/" + UUID.randomUUID();
            lastStatus = send(path, null);
            if (lastStatus == 429) {
                break;
            }
            allowed++;
        }

        assertThat(allowed).isEqualTo(IP_CAPACITY);
        assertThat(lastStatus).isEqualTo(429);
    }

    @Test
    @DisplayName("a FORGED bearer (signed with the wrong key) does not bypass — it is charged on the "
            + "same per-IP bucket as anonymous traffic")
    void should_return429_when_forgedTokenUsedAfterIpBucketExhausted() throws Exception {
        String forged = new JwtTokenProvider(
                new JwtConfig(FORGER_SECRET, ACCESS_TTL_MS, REFRESH_TTL_MS), Clock.systemUTC())
                .generateAccessToken(UUID.randomUUID(), "attacker@example.com", Role.SALON_OWNER);
        exhaustAnonymousIpBucket();

        int status = send("/api/v1/salons/" + UUID.randomUUID(), forged);

        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("a garbage bearer id sweep is capped at the per-IP budget exactly like anonymous traffic")
    void should_return429_when_garbageBearerSweepExceedsPerIpCap() throws Exception {
        int allowed = 0;
        int lastStatus = 0;

        for (int i = 0; i < IP_CAPACITY + 3; i++) {
            lastStatus = send("/api/v1/masters/" + UUID.randomUUID(), "not-a-jwt-" + i);
            if (lastStatus == 429) {
                break;
            }
            allowed++;
        }

        assertThat(allowed).isEqualTo(IP_CAPACITY);
        assertThat(lastStatus).isEqualTo(429);
    }

    @Test
    @DisplayName("a REFRESH token presented as bearer does not authenticate, so it falls back to the per-IP bucket")
    void should_return429_when_refreshTokenUsedAsBearerAfterIpBucketExhausted() throws Exception {
        String refresh = jwtTokenProvider.generateRefreshToken(UUID.randomUUID());
        exhaustAnonymousIpBucket();

        int status = send("/api/v1/salons/" + UUID.randomUUID(), refresh);

        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("an authenticated caller is still throttled — on their OWN per-principal bucket, which "
            + "does not starve a second account behind the same IP")
    void should_throttlePerPrincipal_when_authenticatedCallerSweepsIds() throws Exception {
        String sweeper = ownerToken();
        for (int i = 0; i < PRINCIPAL_CAPACITY; i++) {
            assertThat(send("/api/v1/masters/" + UUID.randomUUID(), sweeper)).isEqualTo(200);
        }

        int sweeperStatus = send("/api/v1/masters/" + UUID.randomUUID(), sweeper);
        int otherOwnerStatus = send("/api/v1/salons/" + UUID.randomUUID(), ownerToken());
        int anonymousStatus = send("/api/v1/salons/" + UUID.randomUUID(), null);

        assertThat(sweeperStatus).isEqualTo(429);
        assertThat(otherOwnerStatus).isEqualTo(200);
        assertThat(anonymousStatus)
                .as("authenticated traffic never drew on the anonymous per-IP bucket")
                .isEqualTo(200);
    }

    // ── re-audit 2026-10-05 (INFO): real revocation states, not just the always-valid stub ────

    @Test
    @DisplayName("a validly-signed token whose jti is DENYLISTED (logged out) does not authenticate — it "
            + "falls back to the per-IP bucket and never touches the principal bucket")
    void should_return429_when_revokedTokenUsedAfterIpBucketExhausted() throws Exception {
        UUID userId = UUID.randomUUID();
        String revoked = jwtTokenProvider.generateAccessToken(userId, "owner@example.com", Role.SALON_OWNER);
        when(accessTokenDenylist.isRevoked(jwtTokenProvider.getJti(revoked))).thenReturn(true);
        exhaustAnonymousIpBucket();

        int status = send("/api/v1/salons/" + UUID.randomUUID(), revoked);

        assertThat(status).isEqualTo(429);
        assertThat(perPrincipalBuckets.get(userId.toString()).getAvailableTokens())
                .as("a revoked token must never be charged (or credited) as its principal")
                .isEqualTo(PRINCIPAL_CAPACITY);
    }

    @Test
    @DisplayName("a validly-signed token for a DELETED account (no users row) does not authenticate — it "
            + "is charged on the per-IP bucket exactly like anonymous traffic")
    void should_chargePerIpBucket_when_tokenSubjectAccountDeleted() throws Exception {
        UUID deletedUserId = UUID.randomUUID();
        String orphaned = jwtTokenProvider.generateAccessToken(
                deletedUserId, "gone@example.com", Role.SALON_OWNER);
        when(tokensValidAfterCache.get(deletedUserId)).thenReturn(TokenValidityState.ABSENT);
        long ipTokensBefore = perIpBuckets.get(SHARED_CGNAT_IP).getAvailableTokens();

        int status = send("/api/v1/masters/" + UUID.randomUUID(), orphaned);

        assertThat(status).isEqualTo(200);
        assertThat(perIpBuckets.get(SHARED_CGNAT_IP).getAvailableTokens())
                .as("the deleted account's request is charged on the anonymous per-IP bucket")
                .isEqualTo(ipTokensBefore - 1);
        assertThat(perPrincipalBuckets.get(deletedUserId.toString()).getAvailableTokens())
                .as("and never on the deleted principal's bucket")
                .isEqualTo(PRINCIPAL_CAPACITY);
    }

    @Test
    @DisplayName("a VALID token sent with a lowercase 'bearer' scheme fails closed: no deferral, charged on "
            + "the per-IP bucket, so it 429s once that is spent")
    void should_return429_when_lowercaseBearerSchemeUsedAfterIpBucketExhausted() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.generateAccessToken(userId, "owner@example.com", Role.SALON_OWNER);
        exhaustAnonymousIpBucket();

        int status = sendWithAuthorization("/api/v1/salons/" + UUID.randomUUID(), "bearer " + token);

        assertThat(status).isEqualTo(429);
        assertThat(perPrincipalBuckets.get(userId.toString()).getAvailableTokens())
                .isEqualTo(PRINCIPAL_CAPACITY);
        assertThat(authenticatedIpBuckets.get(SHARED_CGNAT_IP).getAvailableTokens())
                .as("a non-Bearer scheme is anonymous traffic — it never enters the authenticated ceiling")
                .isEqualTo(AUTH_IP_CAPACITY);
    }

    // ── re-audit 2026-10-05 (LOW): authenticated per-IP ceiling through the real JWT filter ────

    @Test
    @DisplayName("many distinct VALID principals behind one IP — each well under its own budget — are "
            + "capped by the authenticated per-IP ceiling")
    void should_return429_when_manyDistinctPrincipalsShareOneIp() throws Exception {
        int allowed = 0;
        int lastStatus = 0;

        for (int i = 0; i < AUTH_IP_CAPACITY + 3; i++) {
            lastStatus = send("/api/v1/salons/" + UUID.randomUUID(), ownerToken());
            if (lastStatus == 429) {
                break;
            }
            allowed++;
        }

        assertThat(allowed).isEqualTo(AUTH_IP_CAPACITY);
        assertThat(lastStatus).isEqualTo(429);
        assertThat(send("/api/v1/salons/" + UUID.randomUUID(), null))
                .as("the authenticated ceiling is separate from the anonymous per-IP budget")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("re-audit follow-up: a FORGED-bearer flood above the ceiling from one IP never drains the "
            + "authenticated ceiling — a genuine signed-in user on that IP is still served")
    void should_not429ValidBearer_when_forgedBearerFloodExhaustsSameIp() throws Exception {
        String forged = new JwtTokenProvider(
                new JwtConfig(FORGER_SECRET, ACCESS_TTL_MS, REFRESH_TTL_MS), Clock.systemUTC())
                .generateAccessToken(UUID.randomUUID(), "attacker@example.com", Role.SALON_OWNER);
        for (int i = 0; i < AUTH_IP_CAPACITY + 1; i++) {
            send("/api/v1/salons/" + UUID.randomUUID(), i % 2 == 0 ? forged : "x");
        }

        int status = send("/api/v1/salons/" + UUID.randomUUID(), ownerToken());

        assertThat(status).isEqualTo(200);
        assertThat(authenticatedIpBuckets.get(SHARED_CGNAT_IP).getAvailableTokens())
                .as("only the one genuine request is charged on the authenticated ceiling")
                .isEqualTo(AUTH_IP_CAPACITY - 1);
        assertThat(perIpBuckets.get(SHARED_CGNAT_IP).getAvailableTokens())
                .as("the forged flood spent the anonymous per-IP bucket instead")
                .isZero();
    }

    @Test
    @DisplayName("follow-up LOW: a throttled principal flooding from one IP spends only its OWN bucket — "
            + "the shared ceiling is untouched, so a second signed-in user on that IP is still served")
    void should_not429OtherPrincipal_when_oneThrottledPrincipalFloodsSameIp() throws Exception {
        String flooder = ownerToken();
        for (int i = 0; i < PRINCIPAL_CAPACITY; i++) {
            assertThat(send("/api/v1/salons/" + UUID.randomUUID(), flooder)).isEqualTo(200);
        }
        for (int i = 0; i < AUTH_IP_CAPACITY; i++) {
            assertThat(send("/api/v1/salons/" + UUID.randomUUID(), flooder))
                    .as("flood request %d is over the flooder's own budget", i + 1)
                    .isEqualTo(429);
        }
        long ceilingAfterFlood = authenticatedIpBuckets.get(SHARED_CGNAT_IP).getAvailableTokens();

        int otherStatus = send("/api/v1/salons/" + UUID.randomUUID(), ownerToken());

        assertThat(ceilingAfterFlood)
                .as("only the flooder's in-budget requests were charged on the ceiling — a principal "
                        + "denial must short-circuit before the ceiling is touched")
                .isEqualTo(AUTH_IP_CAPACITY - PRINCIPAL_CAPACITY);
        assertThat(otherStatus).isEqualTo(200);
        assertThat(authenticatedIpBuckets.get(SHARED_CGNAT_IP).getAvailableTokens())
                .isEqualTo(AUTH_IP_CAPACITY - PRINCIPAL_CAPACITY - 1);
    }

    @Test
    @DisplayName("follow-up INFO: when the authenticated per-IP ceiling denies, the caller's principal "
            + "token is refunded — a saturated CGNAT IP does not leave them throttled after it refills")
    void should_refundPrincipalToken_when_ceilingDenies() throws Exception {
        for (int i = 0; i < AUTH_IP_CAPACITY; i++) {
            assertThat(send("/api/v1/salons/" + UUID.randomUUID(), ownerToken())).isEqualTo(200);
        }
        assertThat(authenticatedIpBuckets.get(SHARED_CGNAT_IP).getAvailableTokens())
                .as("precondition: other principals have spent the shared ceiling")
                .isZero();
        UUID userA = UUID.randomUUID();
        String tokenA = jwtTokenProvider.generateAccessToken(userA, "a@example.com", Role.SALON_OWNER);

        int status = send("/api/v1/salons/" + UUID.randomUUID(), tokenA);

        assertThat(status).isEqualTo(429);
        assertThat(perPrincipalBuckets.get(userA.toString()).getAvailableTokens())
                .as("the principal token spent before the ceiling denied must be given back")
                .isEqualTo(PRINCIPAL_CAPACITY);
    }

    @Test
    @DisplayName("re-audit: the authenticated ceiling is per IP — a second IP keeps its own budget")
    void should_notThrottle_when_authenticatedCeilingSpentByAnotherIp() throws Exception {
        for (int i = 0; i < AUTH_IP_CAPACITY + 1; i++) {
            send("/api/v1/masters/" + UUID.randomUUID(), ownerToken());
        }

        int status = sendFrom(OTHER_IP, "/api/v1/masters/" + UUID.randomUUID(), "Bearer " + ownerToken());

        assertThat(status).isEqualTo(200);
        assertThat(authenticatedIpBuckets.get(SHARED_CGNAT_IP).getAvailableTokens()).isZero();
        assertThat(authenticatedIpBuckets.get(OTHER_IP).getAvailableTokens()).isEqualTo(AUTH_IP_CAPACITY - 1);
    }

    /** Terminal servlet standing in for the DispatcherServlet — every request that reaches it is a 200. */
    private static final class OkServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            resp.setStatus(200);
        }
    }
}
