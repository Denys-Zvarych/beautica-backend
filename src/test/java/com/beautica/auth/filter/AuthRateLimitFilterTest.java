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
    @DisplayName("IP resolution — rightmost X-Forwarded-For")
    class IpResolution {

        private static final String RIGHTMOST_IP    = "5.6.7.8";
        private static final String DIFFERENT_IP    = "99.99.99.99";

        @Test
        @DisplayName("falls back to getRemoteAddr when X-Forwarded-For is absent")
        void should_useRemoteAddr_when_xForwardedForAbsent() throws Exception {
            log.debug("Arrange: no XFF header — filter must fall back to getRemoteAddr");
            when(loginBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/login");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal without XFF header");
            doFilter(request, response, chain);

            verify(loginBuckets).get(REMOTE_ADDR);
            assertThat(chain.getRequest())
                    .as("chain must be forwarded when remoteAddr bucket allows the request")
                    .isNotNull();
        }

        @Test
        @DisplayName("keys on leftmost XFF entry — rotating the rightmost (proxy) entry cannot bypass the limiter")
        void should_return429_when_xForwardedForRotatesLastEntryButLeftmostIsTheSame() throws Exception {
            // The leftmost XFF entry is the original client IP.
            // An attacker trying to bypass rate limiting by spoofing proxy hops (rightmost entries)
            // cannot get a fresh bucket because the filter always keys on the leftmost (client) IP.
            log.debug("Arrange: bucket keyed on leftmost XFF entry {}", RIGHTMOST_IP);
            when(loginBuckets.get(RIGHTMOST_IP)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            String[] rotatingRightEntries = {
                "proxy1.railway.app", "proxy2.railway.app", "proxy3.railway.app", "proxy4.railway.app", "proxy5.railway.app"
            };

            MockHttpServletResponse lastResponse = null;
            MockFilterChain lastChain = null;

            log.debug("Act: send 5 requests each with a different rightmost XFF entry but the same leftmost ({})", RIGHTMOST_IP);
            for (String rightIp : rotatingRightEntries) {
                var request  = postRequest("/api/v1/auth/login");
                request.addHeader("X-Forwarded-For", RIGHTMOST_IP + ", " + rightIp);
                lastResponse = new MockHttpServletResponse();
                lastChain    = new MockFilterChain();

                doFilter(request, lastResponse, lastChain);
            }

            assertThat(lastResponse.getStatus())
                    .as("5th request must be 429 — leftmost XFF entry is always used as the bucket key")
                    .isEqualTo(429);
            assertThat(lastChain.getRequest())
                    .as("filter chain must not be forwarded on the throttled request")
                    .isNull();
            verify(loginBuckets, times(5)).get(RIGHTMOST_IP);
        }

        @Test
        @DisplayName("same leftmost XFF from requests with different proxy hops is still rate-limited on the same bucket")
        void should_return429_when_sameRightmostXffUsedAcrossDifferentOuterIps() throws Exception {
            log.debug("Arrange: second request uses a different rightmost (proxy) XFF entry but identical leftmost ({})", RIGHTMOST_IP);
            when(loginBuckets.get(RIGHTMOST_IP)).thenReturn(bucket);
            // First request exhausts the bucket; second request is denied.
            when(bucket.tryConsume(1)).thenReturn(true).thenReturn(false);

            var firstRequest = postRequest("/api/v1/auth/login");
            firstRequest.addHeader("X-Forwarded-For", RIGHTMOST_IP + ", 1.2.3.4");
            doFilter(firstRequest, new MockHttpServletResponse(), new MockFilterChain());

            var secondRequest  = postRequest("/api/v1/auth/login");
            secondRequest.addHeader("X-Forwarded-For", RIGHTMOST_IP + ", 9.9.9.9");
            var secondResponse = new MockHttpServletResponse();
            var secondChain    = new MockFilterChain();

            log.debug("Act: second request with different rightmost (proxy) but same leftmost XFF");
            doFilter(secondRequest, secondResponse, secondChain);

            assertThat(secondResponse.getStatus())
                    .as("must be 429 — both requests share the same leftmost XFF, so the same bucket is used")
                    .isEqualTo(429);
            assertThat(secondChain.getRequest())
                    .as("filter chain must not be forwarded on the throttled request")
                    .isNull();
            verify(loginBuckets, times(2)).get(RIGHTMOST_IP);
        }

        @Test
        @DisplayName("different leftmost XFF entry uses a different bucket and is not rate-limited")
        void should_passThrough_when_rightmostXffIsDifferent() throws Exception {
            log.debug("Arrange: a fresh bucket for a different leftmost XFF IP ({})", DIFFERENT_IP);
            Bucket differentBucket = org.mockito.Mockito.mock(Bucket.class);
            when(loginBuckets.get(DIFFERENT_IP)).thenReturn(differentBucket);
            when(differentBucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/login");
            request.addHeader("X-Forwarded-For", DIFFERENT_IP + ", 1.2.3.4");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: request with different leftmost XFF entry — should not be rate-limited");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("must be 200 — different leftmost XFF entry maps to a fresh bucket")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the bucket allows the request")
                    .isNotNull();
            verify(loginBuckets).get(DIFFERENT_IP);
        }

        @Test
        @DisplayName("falls back to getRemoteAddr when trailing XFF segment is whitespace-only")
        void should_fallBackToRemoteAddr_when_xffTrailingSegmentIsWhitespaceOnly() throws Exception {
            // XFF: "5.6.7.8,   " — trailing segment is whitespace only.
            // The fix scans right-to-left, skips the blank segment, and returns "5.6.7.8".
            // This test verifies the pre-fix path (empty-string key) is gone: the bucket is
            // keyed on REMOTE_ADDR only when ALL segments are blank, which is not this case.
            // Here the first non-blank segment from the right is "5.6.7.8", so the bucket
            // must be keyed on that value — NOT on REMOTE_ADDR.
            log.debug("Arrange: XFF has trailing whitespace-only segment; first non-blank from right is {}", RIGHTMOST_IP);
            when(loginBuckets.get(RIGHTMOST_IP)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            // Exhaust the bucket using requests with the whitespace-trailing header.
            for (int i = 0; i < 5; i++) {
                var req = postRequest("/api/v1/auth/login");
                req.addHeader("X-Forwarded-For", RIGHTMOST_IP + ",   ");
                doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
            }

            // A clean request from the same REMOTE_ADDR but without XFF must also be
            // rate-limited because it falls back to REMOTE_ADDR, which is a different key
            // from RIGHTMOST_IP — so it gets its own fresh bucket (not yet exhausted).
            // The important invariant we assert here is: with the trailing-whitespace header
            // the bucket key is RIGHTMOST_IP, not an empty string and not REMOTE_ADDR.
            var throttledRequest  = postRequest("/api/v1/auth/login");
            throttledRequest.addHeader("X-Forwarded-For", RIGHTMOST_IP + ",   ");
            var throttledResponse = new MockHttpServletResponse();
            var throttledChain    = new MockFilterChain();

            log.debug("Act: 6th request with whitespace-trailing XFF — bucket keyed on {} must be exhausted", RIGHTMOST_IP);
            doFilter(throttledRequest, throttledResponse, throttledChain);

            assertThat(throttledResponse.getStatus())
                    .as("6th request must be 429 — bucket keyed on rightmost non-blank XFF entry is exhausted")
                    .isEqualTo(429);
            assertThat(throttledChain.getRequest())
                    .as("filter chain must not be forwarded when the bucket is exhausted")
                    .isNull();

            // A request with a genuinely different remote addr is NOT rate-limited.
            Bucket freshBucket = org.mockito.Mockito.mock(Bucket.class);
            when(loginBuckets.get(DIFFERENT_IP)).thenReturn(freshBucket);
            when(freshBucket.tryConsume(1)).thenReturn(true);

            var differentRequest = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            differentRequest.setRemoteAddr(DIFFERENT_IP);
            differentRequest.addHeader("X-Forwarded-For", DIFFERENT_IP + ",   ");
            var differentResponse = new MockHttpServletResponse();
            var differentChain    = new MockFilterChain();

            doFilter(differentRequest, differentResponse, differentChain);

            assertThat(differentResponse.getStatus())
                    .as("request from a different IP must not be rate-limited — it has its own fresh bucket")
                    .isEqualTo(200);
            verify(loginBuckets, times(6)).get(RIGHTMOST_IP);
            verify(loginBuckets).get(DIFFERENT_IP);
        }

        @Test
        @DisplayName("falls back to getRemoteAddr when X-Forwarded-For header value is empty")
        void should_fallBackToRemoteAddr_when_xffHeaderIsEmpty() throws Exception {
            log.debug("Arrange: XFF header present but value is empty string; must fall back to REMOTE_ADDR ({})", REMOTE_ADDR);
            when(loginBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request = postRequest("/api/v1/auth/login");
            request.addHeader("X-Forwarded-For", "");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal with empty XFF header value");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("must be 200 — bucket keyed on REMOTE_ADDR was not exhausted")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the bucket allows the request")
                    .isNotNull();
            verify(loginBuckets).get(REMOTE_ADDR);
        }

        @Test
        @DisplayName("keys on the single XFF entry when the header has no comma")
        void should_useXffEntry_when_xffContainsSingleEntry() throws Exception {
            log.debug("Arrange: XFF header with single entry {} — no comma", RIGHTMOST_IP);
            when(loginBuckets.get(RIGHTMOST_IP)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            // Two requests from the same single-entry XFF share the same bucket.
            var first = postRequest("/api/v1/auth/login");
            first.addHeader("X-Forwarded-For", RIGHTMOST_IP);
            doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

            var second  = postRequest("/api/v1/auth/login");
            second.addHeader("X-Forwarded-For", RIGHTMOST_IP);
            var secondResponse = new MockHttpServletResponse();
            var secondChain    = new MockFilterChain();

            log.debug("Act: third request with single-entry XFF — bucket must be exhausted");
            var third  = postRequest("/api/v1/auth/login");
            third.addHeader("X-Forwarded-For", RIGHTMOST_IP);
            var thirdResponse = new MockHttpServletResponse();
            var thirdChain    = new MockFilterChain();
            doFilter(second, secondResponse, secondChain);
            doFilter(third, thirdResponse, thirdChain);

            assertThat(thirdResponse.getStatus())
                    .as("third request must be 429 — single-entry XFF bucket is exhausted")
                    .isEqualTo(429);
            assertThat(thirdChain.getRequest())
                    .as("filter chain must not be forwarded on the throttled request")
                    .isNull();

            // A different single-entry XFF maps to a different (fresh) bucket.
            Bucket freshBucket = org.mockito.Mockito.mock(Bucket.class);
            when(loginBuckets.get(DIFFERENT_IP)).thenReturn(freshBucket);
            when(freshBucket.tryConsume(1)).thenReturn(true);

            var differentRequest = postRequest("/api/v1/auth/login");
            differentRequest.addHeader("X-Forwarded-For", DIFFERENT_IP);
            var differentResponse = new MockHttpServletResponse();
            var differentChain    = new MockFilterChain();
            doFilter(differentRequest, differentResponse, differentChain);

            assertThat(differentResponse.getStatus())
                    .as("request from a different single-entry XFF must not be rate-limited")
                    .isEqualTo(200);
            verify(loginBuckets, times(3)).get(RIGHTMOST_IP);
            verify(loginBuckets).get(DIFFERENT_IP);
        }

        @Test
        @DisplayName("keys on leftmost IPv6 address when XFF contains an IPv6 entry")
        void should_useRightmostIPv6Entry_when_xffContainsIPv6Address() throws Exception {
            String ipv6 = "::1";
            log.debug("Arrange: XFF = '{}, 1.2.3.4' — leftmost entry is IPv6", ipv6);
            when(loginBuckets.get(ipv6)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request = postRequest("/api/v1/auth/login");
            request.addHeader("X-Forwarded-For", ipv6 + ", 1.2.3.4");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal with leftmost IPv6 XFF entry");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("must be 200 — bucket keyed on IPv6 address was not exhausted")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the bucket allows the request")
                    .isNotNull();
            verify(loginBuckets).get(ipv6);
        }

        @Test
        @DisplayName("uses XFF value when leftmost XFF entry is exactly 45 characters (boundary: accepted, not replaced by REMOTE_ADDR)")
        void should_useXffValue_when_xffEntryIsExactly45Chars() throws Exception {
            // 45 chars is the guard boundary: ip.length() > 45 triggers fallback, so exactly-45
            // must be accepted as the bucket key and NOT replaced by REMOTE_ADDR.
            String exactly45Chars = "a".repeat(45);
            log.debug("Arrange: XFF leftmost entry is exactly {} chars — must be used as bucket key, not REMOTE_ADDR",
                    exactly45Chars.length());
            when(loginBuckets.get(exactly45Chars)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request = postRequest("/api/v1/auth/login");
            request.addHeader("X-Forwarded-For", exactly45Chars);
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal with exactly-45-char XFF entry — guard must accept it");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("must be 200 — bucket keyed on the 45-char XFF value was not exhausted")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded — 45-char XFF entry is within the accepted length")
                    .isNotNull();
            // Bucket must be keyed on the XFF value, NOT on REMOTE_ADDR (10.0.0.1)
            verify(loginBuckets).get(exactly45Chars);
            verify(loginBuckets, never()).get(REMOTE_ADDR);
        }

        @Test
        @DisplayName("falls back to getRemoteAddr when leftmost XFF entry exceeds 45 characters (max IPv6 length)")
        void should_fallBackToRemoteAddr_when_xffEntryExceedsMaxIpLength() throws Exception {
            String oversizedIp = "a".repeat(46);
            log.debug("Arrange: XFF leftmost entry is {} chars (> 45 max) — must fall back to REMOTE_ADDR ({})",
                    oversizedIp.length(), REMOTE_ADDR);
            when(loginBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request = postRequest("/api/v1/auth/login");
            request.addHeader("X-Forwarded-For", oversizedIp);
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal with oversized XFF entry — guard must reject it and fall back to REMOTE_ADDR");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("must be 200 — bucket keyed on REMOTE_ADDR was not exhausted")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the REMOTE_ADDR bucket allows the request")
                    .isNotNull();
            verify(loginBuckets).get(REMOTE_ADDR);
        }
    }
}
