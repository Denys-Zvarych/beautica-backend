package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthRateLimitFilter — unit")
class AuthRateLimitFilterTest {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilterTest.class);

    private static final String REMOTE_ADDR = "10.0.0.1";

    // ── mocks ──────────────────────────────────────────────────────────────────
    @Mock private LoadingCache<String, Bucket> loginBuckets;
    @Mock private LoadingCache<String, Bucket> refreshBuckets;
    @Mock private Bucket                        bucket;

    // ── subject ────────────────────────────────────────────────────────────────
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthRateLimitFilter(loginBuckets, refreshBuckets);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private MockHttpServletRequest postRequest(String uri) {
        var req = new MockHttpServletRequest("POST", uri);
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    private MockHttpServletRequest getRequest(String uri) {
        var req = new MockHttpServletRequest("GET", uri);
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    private void doFilter(MockHttpServletRequest request,
                          MockHttpServletResponse response,
                          MockFilterChain chain) throws ServletException, IOException {
        filter.doFilterInternal(request, response, chain);
    }

    // ==========================================================================
    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class LoginEndpoint {

        @Test
        @DisplayName("passes through when bucket has tokens")
        void should_passThrough_when_loginBucketHasTokens() throws Exception {
            log.debug("Arrange: loginBuckets returns bucket that allows consumption");
            when(loginBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/login");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/login when bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when login bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verifyNoInteractions(refreshBuckets);
        }

        @Test
        @DisplayName("returns 429 when bucket is exhausted")
        void should_return429_when_loginBucketExhausted() throws Exception {
            log.debug("Arrange: loginBuckets returns bucket that denies consumption");
            when(loginBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/login");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/login when login bucket is exhausted");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when login bucket is exhausted")
                    .isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(chain.getRequest()).isNull();
            verifyNoInteractions(refreshBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("POST /auth/refresh")
    class RefreshEndpoint {

        @Test
        @DisplayName("passes through when bucket has tokens")
        void should_passThrough_when_refreshBucketHasTokens() throws Exception {
            log.debug("Arrange: refreshBuckets returns bucket that allows consumption");
            when(refreshBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/refresh");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/refresh when refresh bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when refresh bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verifyNoInteractions(loginBuckets);
        }

        @Test
        @DisplayName("returns 429 when bucket is exhausted")
        void should_return429_when_refreshBucketExhausted() throws Exception {
            log.debug("Arrange: refreshBuckets returns bucket that denies consumption");
            when(refreshBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/refresh");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/refresh when refresh bucket is exhausted");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when refresh bucket is exhausted")
                    .isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(chain.getRequest()).isNull();
            verifyNoInteractions(loginBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("Non-POST methods — bypass rate limiting")
    class NonPostMethod {

        @Test
        @DisplayName("GET on /auth/login passes through without touching any bucket")
        void should_passThrough_when_getRequestOnLoginPath() throws Exception {
            log.debug("Arrange: GET /auth/login request (method not POST)");
            var request  = getRequest("/api/v1/auth/login");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for GET /auth/login — non-POST method bypasses rate limiting");
            doFilter(request, response, chain);

            assertThat(chain.getRequest())
                    .as("chain must be forwarded — GET method is not rate-limited")
                    .isNotNull();
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(refreshBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("Non-rate-limited POST paths — bypass rate limiting")
    class NonRateLimitedPath {

        @Test
        @DisplayName("POST /auth/register passes through without touching any bucket")
        void should_passThrough_when_postToNonRateLimitedPath() throws Exception {
            log.debug("Arrange: POST /auth/register (path not subject to rate limiting)");
            var request  = postRequest("/api/v1/auth/register");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/register — path not subject to rate limiting");
            doFilter(request, response, chain);

            assertThat(chain.getRequest())
                    .as("chain must be forwarded — /auth/register is not rate-limited")
                    .isNotNull();
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(refreshBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("IP resolution")
    class IpResolution {

        @Test
        @DisplayName("enforces limit on getRemoteAddr even when X-Forwarded-For rotates — bypass prevention")
        void should_return429_when_xForwardedForRotatesButRemoteAddrIsTheSame() throws Exception {
            when(loginBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            String[] rotatingIps = {
                "1.2.3.4", "5.6.7.8", "9.10.11.12", "13.14.15.16", "17.18.19.20"
            };

            MockHttpServletResponse lastResponse = null;
            MockFilterChain lastChain = null;

            for (String fakeIp : rotatingIps) {
                var request  = postRequest("/api/v1/auth/login");
                request.addHeader("X-Forwarded-For", fakeIp);
                lastResponse = new MockHttpServletResponse();
                lastChain    = new MockFilterChain();

                doFilter(request, lastResponse, lastChain);
            }

            assertThat(lastResponse.getStatus())
                    .as("5th request must be 429 — limiter keys on getRemoteAddr, not the rotating XFF header")
                    .isEqualTo(429);
            assertThat(lastChain.getRequest())
                    .as("filter chain must not be forwarded on the 5th (throttled) request")
                    .isNull();
            verify(loginBuckets, times(5)).get(REMOTE_ADDR);
        }

        @Test
        @DisplayName("keys on getRemoteAddr as the sole IP source of truth")
        void should_useRemoteAddr_asIpKey() throws Exception {
            when(loginBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/login");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            doFilter(request, response, chain);

            verify(loginBuckets).get(REMOTE_ADDR);
            assertThat(chain.getRequest())
                    .as("chain must be forwarded when remoteAddr bucket allows the request")
                    .isNotNull();
        }
    }
}
