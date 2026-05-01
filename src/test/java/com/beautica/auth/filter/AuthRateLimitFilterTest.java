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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthRateLimitFilter — unit")
class AuthRateLimitFilterTest {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilterTest.class);

    private static final String REMOTE_ADDR   = "10.0.0.1";
    private static final String FORWARDED_IP  = "203.0.113.42";

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
        @DisplayName("uses first X-Forwarded-For IP as bucket key when header is present")
        void should_useXForwardedForFirstValue_when_headerPresent() throws Exception {
            log.debug("Arrange: X-Forwarded-For={} present; filter must use leftmost IP as rate-limit key", FORWARDED_IP);
            when(loginBuckets.get(FORWARDED_IP)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request = postRequest("/api/v1/auth/login");
            request.addHeader("X-Forwarded-For", FORWARDED_IP + ", 192.168.1.1, 10.10.0.1");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal — X-Forwarded-For header present, bucket must key by first IP {}", FORWARDED_IP);
            doFilter(request, response, chain);

            verify(loginBuckets).get(FORWARDED_IP);
            verify(loginBuckets, never()).get(REMOTE_ADDR);
            assertThat(chain.getRequest())
                    .as("chain must be forwarded when XFF bucket allows the request")
                    .isNotNull();
        }

        @Test
        @DisplayName("falls back to getRemoteAddr when X-Forwarded-For is absent")
        void should_useRemoteAddr_when_xForwardedForHeaderAbsent() throws Exception {
            log.debug("Arrange: no X-Forwarded-For header; remoteAddr={}", REMOTE_ADDR);
            when(loginBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            // postRequest() does not set X-Forwarded-For — deliberately absent.
            var request  = postRequest("/api/v1/auth/login");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal — no X-Forwarded-For, must fall back to remoteAddr={}", REMOTE_ADDR);
            doFilter(request, response, chain);

            verify(loginBuckets).get(REMOTE_ADDR);
            assertThat(chain.getRequest())
                    .as("chain must be forwarded when remoteAddr bucket allows the request")
                    .isNotNull();
        }
    }
}
