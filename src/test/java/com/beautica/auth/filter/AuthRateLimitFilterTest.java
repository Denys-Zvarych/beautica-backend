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
    @Mock private LoadingCache<String, Bucket> registerBuckets;
    @Mock private LoadingCache<String, Bucket> loginBuckets;
    @Mock private LoadingCache<String, Bucket> refreshBuckets;
    @Mock private LoadingCache<String, Bucket> verifyEmailBuckets;
    @Mock private LoadingCache<String, Bucket> slotsBuckets;
    @Mock private LoadingCache<String, Bucket> deviceTokenBuckets;
    @Mock private LoadingCache<String, Bucket> mediaUploadBuckets;
    @Mock private LoadingCache<String, Bucket> profileUpdateBuckets;
    @Mock private LoadingCache<String, Bucket> resendVerificationBuckets;
    @Mock private LoadingCache<String, Bucket> forgotPasswordBuckets;
    @Mock private LoadingCache<String, Bucket> resetPasswordBuckets;
    @Mock private LoadingCache<String, Bucket> categoryRequestBuckets;
    @Mock private LoadingCache<String, Bucket> suggestServiceTypeBuckets;
    @Mock private LoadingCache<String, Bucket> bulkServiceSetupBuckets;
    @Mock private LoadingCache<String, Bucket> supportContactBuckets;
    @Mock private LoadingCache<String, Bucket> otpSendBuckets;
    @Mock private LoadingCache<String, Bucket> verifyPasswordResetOtpBuckets;
    @Mock private LoadingCache<String, Bucket> changePasswordOtpBuckets;
    @Mock private Bucket                        bucket;

    // ── subject ────────────────────────────────────────────────────────────────
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthRateLimitFilter(
                registerBuckets, loginBuckets, refreshBuckets, verifyEmailBuckets,
                slotsBuckets, deviceTokenBuckets, mediaUploadBuckets, profileUpdateBuckets,
                resendVerificationBuckets, forgotPasswordBuckets, resetPasswordBuckets,
                categoryRequestBuckets, suggestServiceTypeBuckets, bulkServiceSetupBuckets,
                supportContactBuckets, otpSendBuckets, verifyPasswordResetOtpBuckets,
                changePasswordOtpBuckets);
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

    private MockHttpServletRequest deleteRequest(String uri) {
        var req = new MockHttpServletRequest("DELETE", uri);
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
            verifyNoInteractions(registerBuckets);
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
            assertThat(response.getContentType())
                    .as("Content-Type must be application/json on 429 login response")
                    .startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(chain.getRequest()).isNull();
            verifyNoInteractions(registerBuckets);
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
            verifyNoInteractions(registerBuckets);
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
            assertThat(response.getContentType())
                    .as("Content-Type must be application/json on 429 refresh response")
                    .startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(chain.getRequest()).isNull();
            verifyNoInteractions(registerBuckets);
            verifyNoInteractions(loginBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("/api/v1/devices/token — POST and DELETE")
    class DeviceTokenEndpoint {

        @Test
        @DisplayName("POST routes to deviceTokenBuckets and not loginBuckets/refreshBuckets")
        void should_routeToDeviceTokenBuckets_when_postDevicesToken() throws Exception {
            log.debug("Arrange: deviceTokenBuckets returns bucket that allows consumption");
            when(deviceTokenBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/devices/token");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /devices/token when deviceToken bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when deviceToken bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verify(deviceTokenBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(refreshBuckets);
            verifyNoInteractions(slotsBuckets);
        }

        @Test
        @DisplayName("DELETE routes to deviceTokenBuckets and not loginBuckets/refreshBuckets")
        void should_routeToDeviceTokenBuckets_when_deleteDevicesToken() throws Exception {
            log.debug("Arrange: deviceTokenBuckets returns bucket that allows consumption");
            when(deviceTokenBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = deleteRequest("/api/v1/devices/token");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for DELETE /devices/token when deviceToken bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when deviceToken bucket allows the DELETE request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verify(deviceTokenBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(refreshBuckets);
            verifyNoInteractions(slotsBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("/api/v1/media/* — POST and DELETE")
    class MediaUploadEndpoint {

        @Test
        @DisplayName("POST /api/v1/media/avatar routes to mediaUploadBuckets and not the other 4 buckets")
        void should_routeToMediaUploadBuckets_when_postMediaAvatar() throws Exception {
            log.debug("Arrange: mediaUploadBuckets returns bucket that allows consumption");
            when(mediaUploadBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/media/avatar");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /api/v1/media/avatar when mediaUpload bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when mediaUpload bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verify(mediaUploadBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(refreshBuckets);
            verifyNoInteractions(slotsBuckets);
            verifyNoInteractions(deviceTokenBuckets);
        }

        @Test
        @DisplayName("DELETE /api/v1/media/portfolio/{id} routes to mediaUploadBuckets and not the other 4 buckets")
        void should_routeToMediaUploadBuckets_when_deleteMediaPortfolio() throws Exception {
            log.debug("Arrange: mediaUploadBuckets returns bucket that allows consumption");
            when(mediaUploadBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = deleteRequest("/api/v1/media/portfolio/" + java.util.UUID.randomUUID());
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for DELETE /api/v1/media/portfolio/{id} when mediaUpload bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when mediaUpload bucket allows the DELETE request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verify(mediaUploadBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(refreshBuckets);
            verifyNoInteractions(slotsBuckets);
            verifyNoInteractions(deviceTokenBuckets);
        }

        @Test
        @DisplayName("GET /api/v1/salons/{id}/portfolio passes through without touching ANY bucket (not under /api/v1/media/)")
        void should_passThrough_when_getSalonsPortfolio() throws Exception {
            // Public read endpoint at /api/v1/salons/{id}/portfolio is NOT under /api/v1/media/
            // and uses GET, so none of the upload/auth/slot/device-token branches must fire.
            // The slots branch is similarly inapplicable because the path does not end in /slots.
            log.debug("Arrange: GET /api/v1/salons/{id}/portfolio — outside the /api/v1/media/ prefix, GET method, not /slots suffix");
            var request  = getRequest("/api/v1/salons/" + java.util.UUID.randomUUID() + "/portfolio");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal — public portfolio listing must bypass every rate-limit branch");
            doFilter(request, response, chain);

            assertThat(chain.getRequest())
                    .as("chain must be forwarded — public portfolio listing is not rate-limited")
                    .isNotNull();
            verifyNoInteractions(mediaUploadBuckets);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(refreshBuckets);
            verifyNoInteractions(slotsBuckets);
            verifyNoInteractions(deviceTokenBuckets);
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
            verifyNoInteractions(registerBuckets);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(refreshBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("POST /api/v1/auth/register and /register/independent-master")
    class RegisterEndpoint {

        @Test
        @DisplayName("passes through when registerBucket has tokens")
        void should_passThrough_when_registerBucketHasTokens() throws Exception {
            log.debug("Arrange: registerBuckets returns bucket that allows consumption");
            when(registerBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/register");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/register when bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when register bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verify(registerBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(refreshBuckets);
        }

        @Test
        @DisplayName("returns 429 when registerBucket is exhausted for POST /auth/register")
        void should_return429_when_registerCalledMoreThanCapacity() throws Exception {
            log.debug("Arrange: registerBuckets returns bucket that denies consumption");
            when(registerBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/register");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/register when register bucket is exhausted");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when register bucket is exhausted")
                    .isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            assertThat(response.getContentType())
                    .as("Content-Type must be application/json on 429 register response")
                    .startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(chain.getRequest()).isNull();
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(refreshBuckets);
        }

        @Test
        @DisplayName("returns 429 when registerBucket is exhausted for POST /auth/register/independent-master")
        void should_return429_when_independentMasterRegisterCalledMoreThanCapacity() throws Exception {
            log.debug("Arrange: registerBuckets returns bucket that denies consumption for IM path");
            when(registerBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/register/independent-master");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/register/independent-master when register bucket is exhausted");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when register bucket is exhausted for /register/independent-master")
                    .isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            assertThat(chain.getRequest()).isNull();
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(refreshBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("IP resolution — rightmost X-Forwarded-For")
    class IpResolution {

        private static final String CLIENT_IP    = "5.6.7.8";
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
        @DisplayName("keys on rightmost XFF entry — rotating the leftmost (client-supplied) entry cannot bypass the limiter")
        void should_return429_when_xForwardedForRotatesLeftEntryButRightmostIsTheSame() throws Exception {
            // Railway appends the real client IP as the rightmost XFF entry.
            // An attacker trying to bypass rate limiting by cycling spoofed leftmost entries
            // cannot get a fresh bucket because the filter always keys on the rightmost (proxy-set) IP.
            log.debug("Arrange: bucket keyed on rightmost XFF entry {}", CLIENT_IP);
            when(loginBuckets.get(CLIENT_IP)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            String[] rotatingLeftEntries = {
                "fake1", "fake2", "fake3", "fake4", "fake5"
            };

            MockHttpServletResponse lastResponse = null;
            MockFilterChain lastChain = null;

            log.debug("Act: send 5 requests each with a different leftmost (spoofed) XFF entry but the same rightmost ({})", CLIENT_IP);
            for (String leftIp : rotatingLeftEntries) {
                var request  = postRequest("/api/v1/auth/login");
                request.addHeader("X-Forwarded-For", leftIp + ", " + CLIENT_IP);
                lastResponse = new MockHttpServletResponse();
                lastChain    = new MockFilterChain();

                doFilter(request, lastResponse, lastChain);
            }

            assertThat(lastResponse.getStatus())
                    .as("5th request must be 429 — rightmost XFF entry is always used as the bucket key")
                    .isEqualTo(429);
            assertThat(lastChain.getRequest())
                    .as("filter chain must not be forwarded on the throttled request")
                    .isNull();
            verify(loginBuckets, times(5)).get(CLIENT_IP);
        }

        @Test
        @DisplayName("same rightmost XFF from requests with different spoofed leftmost entries is still rate-limited on the same bucket")
        void should_return429_when_sameRightmostXffUsedAcrossDifferentSpoofedLeftEntries() throws Exception {
            log.debug("Arrange: second request uses a different leftmost (spoofed) XFF entry but identical rightmost ({})", CLIENT_IP);
            when(loginBuckets.get(CLIENT_IP)).thenReturn(bucket);
            // First request exhausts the bucket; second request is denied.
            when(bucket.tryConsume(1)).thenReturn(true).thenReturn(false);

            var firstRequest = postRequest("/api/v1/auth/login");
            firstRequest.addHeader("X-Forwarded-For", "spoofed1, " + CLIENT_IP);
            doFilter(firstRequest, new MockHttpServletResponse(), new MockFilterChain());

            var secondRequest  = postRequest("/api/v1/auth/login");
            secondRequest.addHeader("X-Forwarded-For", "spoofed2, " + CLIENT_IP);
            var secondResponse = new MockHttpServletResponse();
            var secondChain    = new MockFilterChain();

            log.debug("Act: second request with different leftmost (spoofed) but same rightmost XFF");
            doFilter(secondRequest, secondResponse, secondChain);

            assertThat(secondResponse.getStatus())
                    .as("must be 429 — both requests share the same rightmost XFF, so the same bucket is used")
                    .isEqualTo(429);
            assertThat(secondChain.getRequest())
                    .as("filter chain must not be forwarded on the throttled request")
                    .isNull();
            verify(loginBuckets, times(2)).get(CLIENT_IP);
        }

        @Test
        @DisplayName("different rightmost XFF entry uses a different bucket and is not rate-limited")
        void should_passThrough_when_rightmostXffIsDifferent() throws Exception {
            log.debug("Arrange: a fresh bucket for a different rightmost XFF IP ({})", DIFFERENT_IP);
            Bucket differentBucket = org.mockito.Mockito.mock(Bucket.class);
            when(loginBuckets.get(DIFFERENT_IP)).thenReturn(differentBucket);
            when(differentBucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/login");
            request.addHeader("X-Forwarded-For", "1.2.3.4, " + DIFFERENT_IP);
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: request with different rightmost XFF entry — should not be rate-limited");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("must be 200 — different rightmost XFF entry maps to a fresh bucket")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the bucket allows the request")
                    .isNotNull();
            verify(loginBuckets).get(DIFFERENT_IP);
        }

        @Test
        @DisplayName("skips trailing whitespace-only XFF segment and keys on the next non-blank rightmost entry")
        void should_fallBackToRemoteAddr_when_xffTrailingSegmentIsWhitespaceOnly() throws Exception {
            // XFF: "5.6.7.8,   " — trailing segment is whitespace only.
            // Scanning right-to-left, the blank trailing segment is skipped and "5.6.7.8"
            // is the first non-blank rightmost entry, so the bucket is keyed on that value.
            // REMOTE_ADDR is used only when ALL segments are blank.
            log.debug("Arrange: XFF has trailing whitespace-only segment; first non-blank from right is {}", CLIENT_IP);
            when(loginBuckets.get(CLIENT_IP)).thenReturn(bucket);
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
                req.addHeader("X-Forwarded-For", CLIENT_IP + ",   ");
                doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
            }

            // A clean request from the same REMOTE_ADDR but without XFF must also be
            // rate-limited because it falls back to REMOTE_ADDR, which is a different key
            // from CLIENT_IP — so it gets its own fresh bucket (not yet exhausted).
            // The important invariant we assert here is: with the trailing-whitespace header
            // the bucket key is CLIENT_IP, not an empty string and not REMOTE_ADDR.
            var throttledRequest  = postRequest("/api/v1/auth/login");
            throttledRequest.addHeader("X-Forwarded-For", CLIENT_IP + ",   ");
            var throttledResponse = new MockHttpServletResponse();
            var throttledChain    = new MockFilterChain();

            log.debug("Act: 6th request with whitespace-trailing XFF — bucket keyed on {} must be exhausted", CLIENT_IP);
            doFilter(throttledRequest, throttledResponse, throttledChain);

            assertThat(throttledResponse.getStatus())
                    .as("6th request must be 429 — bucket keyed on leftmost non-blank XFF entry (CLIENT_IP) is exhausted")
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
            verify(loginBuckets, times(6)).get(CLIENT_IP);
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
            log.debug("Arrange: XFF header with single entry {} — no comma", CLIENT_IP);
            when(loginBuckets.get(CLIENT_IP)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            // Two requests from the same single-entry XFF share the same bucket.
            var first = postRequest("/api/v1/auth/login");
            first.addHeader("X-Forwarded-For", CLIENT_IP);
            doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

            var second  = postRequest("/api/v1/auth/login");
            second.addHeader("X-Forwarded-For", CLIENT_IP);
            var secondResponse = new MockHttpServletResponse();
            var secondChain    = new MockFilterChain();

            log.debug("Act: third request with single-entry XFF — bucket must be exhausted");
            var third  = postRequest("/api/v1/auth/login");
            third.addHeader("X-Forwarded-For", CLIENT_IP);
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
            verify(loginBuckets, times(3)).get(CLIENT_IP);
            verify(loginBuckets).get(DIFFERENT_IP);
        }

        @Test
        @DisplayName("keys on rightmost XFF entry — IPv6 address in leftmost position is not used as bucket key")
        void should_useRightmostEntry_when_xffContainsIPv6AddressOnLeft() throws Exception {
            String ipv6 = "::1";
            String realClientIp = "1.2.3.4";
            log.debug("Arrange: XFF = '{}, {}' — rightmost entry is the real client IP", ipv6, realClientIp);
            when(loginBuckets.get(realClientIp)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request = postRequest("/api/v1/auth/login");
            request.addHeader("X-Forwarded-For", ipv6 + ", " + realClientIp);
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal with rightmost XFF entry as real client IP");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("must be 200 — bucket keyed on rightmost XFF entry was not exhausted")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the bucket allows the request")
                    .isNotNull();
            verify(loginBuckets).get(realClientIp);
        }

        @Test
        @DisplayName("uses XFF value when rightmost XFF entry is exactly 45 characters (boundary: accepted, not replaced by REMOTE_ADDR)")
        void should_useXffValue_when_xffEntryIsExactly45Chars() throws Exception {
            // 45 chars is the guard boundary: ip.length() > 45 triggers fallback, so exactly-45
            // must be accepted as the bucket key and NOT replaced by REMOTE_ADDR.
            String exactly45Chars = "a".repeat(45);
            log.debug("Arrange: XFF rightmost entry is exactly {} chars — must be used as bucket key, not REMOTE_ADDR",
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
        @DisplayName("falls back to getRemoteAddr when rightmost XFF entry exceeds 45 characters (max IPv6 length)")
        void should_fallBackToRemoteAddr_when_xffEntryExceedsMaxIpLength() throws Exception {
            String oversizedIp = "a".repeat(46);
            log.debug("Arrange: XFF rightmost entry is {} chars (> 45 max) — must fall back to REMOTE_ADDR ({})",
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

        @Test
        @DisplayName("two distinct remote addresses use independent buckets and both succeed")
        void should_useIndependentBuckets_when_twoDistinctRemoteAddresses() throws Exception {
            String firstIp  = "11.11.11.11";
            String secondIp = "22.22.22.22";
            log.debug("Arrange: two independent buckets for {} and {}", firstIp, secondIp);

            Bucket firstBucket  = org.mockito.Mockito.mock(Bucket.class);
            Bucket secondBucket = org.mockito.Mockito.mock(Bucket.class);
            when(loginBuckets.get(firstIp)).thenReturn(firstBucket);
            when(loginBuckets.get(secondIp)).thenReturn(secondBucket);
            when(firstBucket.tryConsume(1)).thenReturn(true);
            when(secondBucket.tryConsume(1)).thenReturn(true);

            var firstRequest = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            firstRequest.setRemoteAddr(firstIp);
            var firstResponse = new MockHttpServletResponse();
            var firstChain    = new MockFilterChain();

            var secondRequest = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            secondRequest.setRemoteAddr(secondIp);
            var secondResponse = new MockHttpServletResponse();
            var secondChain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for two requests from distinct IPs");
            doFilter(firstRequest, firstResponse, firstChain);
            doFilter(secondRequest, secondResponse, secondChain);

            assertThat(firstResponse.getStatus())
                    .as("first IP must receive 200 — its bucket was not exhausted")
                    .isEqualTo(200);
            assertThat(secondResponse.getStatus())
                    .as("second IP must receive 200 — it has its own independent bucket")
                    .isEqualTo(200);
            verify(loginBuckets).get(firstIp);
            verify(loginBuckets).get(secondIp);
        }

        @Test
        @DisplayName("returns 429 when XFF refresh endpoint is throttled based on rightmost XFF entry")
        void should_return429_when_xffRefreshEndpointThrottled() throws Exception {
            // rightmost entry is CLIENT_IP (appended by Railway's proxy)
            log.debug("Arrange: refresh bucket keyed on rightmost XFF entry {} is exhausted", CLIENT_IP);
            when(refreshBuckets.get(CLIENT_IP)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request = postRequest("/api/v1/auth/refresh");
            request.addHeader("X-Forwarded-For", "spoofed-ip, " + CLIENT_IP);
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/refresh with exhausted XFF-keyed refresh bucket");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("must be 429 — refresh bucket keyed on rightmost XFF entry is exhausted")
                    .isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            assertThat(chain.getRequest())
                    .as("filter chain must not be forwarded when the refresh bucket is exhausted")
                    .isNull();
            verify(refreshBuckets).get(CLIENT_IP);
            verifyNoInteractions(loginBuckets);
        }

        @Test
        @DisplayName("Rate limit is applied to rightmost XFF IP — client-supplied leftmost entry cannot spoof a different bucket")
        void should_rateLimit_when_attackerSpoofsFakeLeftmostXffEntry() throws Exception {
            // An attacker sends X-Forwarded-For: fake-ip-{i}, real-client-ip.
            // The rightmost entry (real-client-ip) is the Railway-appended real IP and is
            // always the bucket key — cycling the leftmost spoofed entry has no effect.
            String realClientIp = "real-client-ip";
            log.debug("Arrange: bucket keyed on rightmost (real) XFF entry {}", realClientIp);
            when(loginBuckets.get(realClientIp)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            MockHttpServletResponse lastResponse = null;
            MockFilterChain lastChain = null;

            log.debug("Act: 6 requests each with a different leftmost spoofed entry but the same rightmost ({})", realClientIp);
            for (int i = 0; i < 6; i++) {
                var request = postRequest("/api/v1/auth/login");
                request.addHeader("X-Forwarded-For", "fake-ip-" + i + ", " + realClientIp);
                lastResponse = new MockHttpServletResponse();
                lastChain    = new MockFilterChain();
                doFilter(request, lastResponse, lastChain);
            }

            assertThat(lastResponse.getStatus())
                    .as("6th request must be 429 — all requests share the same rightmost XFF bucket key")
                    .isEqualTo(429);
            assertThat(lastChain.getRequest())
                    .as("filter chain must not be forwarded on the throttled request")
                    .isNull();
            verify(loginBuckets, times(6)).get(realClientIp);
        }

        @Test
        @DisplayName("falls back to getRemoteAddr when all XFF segments are whitespace")
        void should_keyOnAllWhitespaceXff_when_allXffSegmentsAreWhitespace() throws Exception {
            // XFF = "  ,   ,  " — outer isBlank() does NOT fire because the full header value
            // is not blank (it contains commas). The segment loop iterates all three segments;
            // each is blank after trim, so no segment is accepted. The filter falls through to
            // getRemoteAddr() as the bucket key.
            log.debug("Arrange: XFF = '  ,   ,  ' — all segments whitespace; filter must fall back to REMOTE_ADDR ({})", REMOTE_ADDR);
            when(loginBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request = postRequest("/api/v1/auth/login");
            request.addHeader("X-Forwarded-For", "  ,   ,  ");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal with all-whitespace XFF segments — must key on REMOTE_ADDR");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("must be 200 — bucket keyed on REMOTE_ADDR was not exhausted")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the REMOTE_ADDR bucket allows the request")
                    .isNotNull();
            verify(loginBuckets).get(REMOTE_ADDR);
            verify(loginBuckets, never()).get("");
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("POST /api/v1/auth/verify-email")
    class VerifyEmailEndpoint {

        @Test
        @DisplayName("passes through when verifyEmail bucket has tokens")
        void should_passThrough_when_verifyEmailBucketHasTokens() throws Exception {
            log.debug("Arrange: verifyEmailBuckets returns bucket that allows consumption");
            when(verifyEmailBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/verify-email");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/verify-email when bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when verifyEmail bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verify(verifyEmailBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(registerBuckets);
            verifyNoInteractions(refreshBuckets);
        }

        @Test
        @DisplayName("returns 429 when verifyEmail bucket is exhausted")
        void should_return429_when_verifyEmailBucketExhausted() throws Exception {
            log.debug("Arrange: verifyEmailBuckets returns bucket that denies consumption");
            when(verifyEmailBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/verify-email");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/verify-email when verifyEmail bucket is exhausted");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when verifyEmail bucket is exhausted")
                    .isEqualTo(429);
            // verify-email bucket window is 15 minutes — Retry-After must reflect 900 s
            assertThat(response.getHeader("Retry-After")).isEqualTo("900");
            assertThat(response.getContentType())
                    .as("Content-Type must be application/json on 429 verify-email response")
                    .startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(chain.getRequest()).isNull();
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(registerBuckets);
            verifyNoInteractions(refreshBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("POST /api/v1/auth/resend-verification")
    class ResendVerificationEndpoint {

        @Test
        @DisplayName("passes through when resend-verification bucket has tokens")
        void should_allowRequest_when_resendVerificationWithinLimit() throws Exception {
            log.debug("Arrange: resendVerificationBuckets returns bucket that allows consumption");
            when(resendVerificationBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/resend-verification");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/resend-verification when bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when resend-verification bucket allows the request")
                    .isNotEqualTo(429);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the bucket allows the request")
                    .isNotNull();
            verify(resendVerificationBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(registerBuckets);
            verifyNoInteractions(refreshBuckets);
            verifyNoInteractions(verifyEmailBuckets);
        }

        @Test
        @DisplayName("returns 429 when resend-verification rate limit exceeded")
        void should_return429_when_resendVerificationRateLimitExceeded() throws Exception {
            log.debug("Arrange: resendVerificationBuckets returns bucket that denies consumption");
            when(resendVerificationBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/resend-verification");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/resend-verification when bucket is exhausted");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when resend-verification bucket is exhausted")
                    .isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            assertThat(response.getContentType())
                    .as("Content-Type must be application/json on 429 resend-verification response")
                    .startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(chain.getRequest())
                    .as("filter chain must NOT be forwarded when the bucket is exhausted")
                    .isNull();
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(registerBuckets);
            verifyNoInteractions(refreshBuckets);
            verifyNoInteractions(verifyEmailBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("POST /api/v1/auth/forgot-password")
    class ForgotPasswordEndpoint {

        @Test
        @DisplayName("passes through when forgot-password bucket has tokens")
        void should_passThrough_when_forgotPasswordBucketHasTokens() throws Exception {
            log.debug("Arrange: forgotPasswordBuckets returns bucket that allows consumption");
            when(forgotPasswordBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/forgot-password");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/forgot-password when bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when forgot-password bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verify(forgotPasswordBuckets).get(REMOTE_ADDR);
            // Decoupling invariant: forgot-password must NOT touch the reset-password bucket.
            verifyNoInteractions(resetPasswordBuckets);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(verifyEmailBuckets);
        }

        @Test
        @DisplayName("returns 429 with 3600s Retry-After when forgot-password bucket is exhausted")
        void should_return429_when_forgotPasswordBucketExhausted() throws Exception {
            log.debug("Arrange: forgotPasswordBuckets returns bucket that denies consumption");
            when(forgotPasswordBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/forgot-password");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/forgot-password when bucket is exhausted");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when forgot-password bucket is exhausted")
                    .isEqualTo(429);
            // 60-minute window — Retry-After must reflect 3600 s.
            assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
            assertThat(response.getContentType())
                    .as("Content-Type must be application/json on 429 forgot-password response")
                    .startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(chain.getRequest()).isNull();
            verifyNoInteractions(resetPasswordBuckets);
            verifyNoInteractions(loginBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("POST /api/v1/auth/reset-password")
    class ResetPasswordEndpoint {

        @Test
        @DisplayName("passes through when reset-password bucket has tokens")
        void should_passThrough_when_resetPasswordBucketHasTokens() throws Exception {
            log.debug("Arrange: resetPasswordBuckets returns bucket that allows consumption");
            when(resetPasswordBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/reset-password");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/reset-password when bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when reset-password bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verify(resetPasswordBuckets).get(REMOTE_ADDR);
            // Decoupling invariant: reset-password must NOT touch the forgot-password bucket.
            verifyNoInteractions(forgotPasswordBuckets);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(verifyEmailBuckets);
        }

        @Test
        @DisplayName("returns 429 with 3600s Retry-After when reset-password bucket is exhausted")
        void should_return429_when_resetPasswordBucketExhausted() throws Exception {
            log.debug("Arrange: resetPasswordBuckets returns bucket that denies consumption");
            when(resetPasswordBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/reset-password");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/reset-password when bucket is exhausted");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when reset-password bucket is exhausted")
                    .isEqualTo(429);
            // 60-minute window — Retry-After must reflect 3600 s.
            assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
            assertThat(response.getContentType())
                    .as("Content-Type must be application/json on 429 reset-password response")
                    .startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(chain.getRequest()).isNull();
            verifyNoInteractions(forgotPasswordBuckets);
            verifyNoInteractions(loginBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("POST /api/v1/auth/verify-password-reset-otp")
    class VerifyPasswordResetOtpEndpoint {

        @Test
        @DisplayName("passes through when verifyPasswordResetOtp bucket has tokens")
        void should_passThrough_when_verifyPasswordResetOtpBucketHasTokens() throws Exception {
            log.debug("Arrange: verifyPasswordResetOtpBuckets returns bucket that allows consumption");
            when(verifyPasswordResetOtpBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/auth/verify-password-reset-otp");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/verify-password-reset-otp when bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when verifyPasswordResetOtp bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verify(verifyPasswordResetOtpBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(verifyEmailBuckets);
            verifyNoInteractions(forgotPasswordBuckets);
            verifyNoInteractions(resetPasswordBuckets);
        }

        @Test
        @DisplayName("returns 429 with 900s Retry-After when verifyPasswordResetOtp bucket is exhausted")
        void should_return429_when_verifyPasswordResetOtpBucketExhausted() throws Exception {
            log.debug("Arrange: verifyPasswordResetOtpBuckets returns bucket that denies consumption");
            when(verifyPasswordResetOtpBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/verify-password-reset-otp");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /auth/verify-password-reset-otp when bucket is exhausted");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when verifyPasswordResetOtp bucket is exhausted")
                    .isEqualTo(429);
            // 15-minute window — Retry-After must reflect 900 s.
            assertThat(response.getHeader("Retry-After")).isEqualTo("900");
            assertThat(response.getContentType())
                    .as("Content-Type must be application/json on 429 verify-password-reset-otp response")
                    .startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(chain.getRequest()).isNull();
            verifyNoInteractions(verifyEmailBuckets);
            verifyNoInteractions(forgotPasswordBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("POST /api/v1/users/me/change-password/request-otp")
    class ChangePasswordOtpEndpoint {

        @Test
        @DisplayName("passes through when changePasswordOtp bucket has tokens")
        void should_passThrough_when_changePasswordOtpBucketHasTokens() throws Exception {
            log.debug("Arrange: changePasswordOtpBuckets returns bucket that allows consumption");
            when(changePasswordOtpBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/users/me/change-password/request-otp");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /users/me/change-password/request-otp when bucket allows consumption");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when changePasswordOtp bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            verify(changePasswordOtpBuckets).get(REMOTE_ADDR);
            // Decoupling invariant: change-password-otp must NOT touch the forgot-password bucket.
            verifyNoInteractions(forgotPasswordBuckets);
            verifyNoInteractions(loginBuckets);
        }

        @Test
        @DisplayName("returns 429 with 3600s Retry-After when changePasswordOtp bucket is exhausted")
        void should_return429_when_changePasswordOtpBucketExhausted() throws Exception {
            log.debug("Arrange: changePasswordOtpBuckets returns bucket that denies consumption");
            when(changePasswordOtpBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/users/me/change-password/request-otp");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /users/me/change-password/request-otp when bucket is exhausted");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when changePasswordOtp bucket is exhausted")
                    .isEqualTo(429);
            // 60-minute window — Retry-After must reflect 3600 s.
            assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
            assertThat(response.getContentType())
                    .as("Content-Type must be application/json on 429 change-password-otp response")
                    .startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(chain.getRequest()).isNull();
            verifyNoInteractions(forgotPasswordBuckets);
            verifyNoInteractions(loginBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("Security headers on 429 responses")
    class SecurityHeadersOn429 {

        @Test
        @DisplayName("X-Content-Type-Options: nosniff is present on 429 login response")
        void should_setXContentTypeOptionsNosniff_when_loginBucketExhausted() throws Exception {
            when(loginBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/login");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when login bucket is exhausted")
                    .isEqualTo(429);
            assertThat(response.getHeader("X-Content-Type-Options"))
                    .as("X-Content-Type-Options must be nosniff on every 429 response from the filter")
                    .isEqualTo("nosniff");
        }

        @Test
        @DisplayName("X-Content-Type-Options: nosniff is present on 429 refresh response")
        void should_setXContentTypeOptionsNosniff_when_refreshBucketExhausted() throws Exception {
            when(refreshBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/refresh");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when refresh bucket is exhausted")
                    .isEqualTo(429);
            assertThat(response.getHeader("X-Content-Type-Options"))
                    .as("X-Content-Type-Options must be nosniff on every 429 response from the filter")
                    .isEqualTo("nosniff");
        }

        @Test
        @DisplayName("X-Content-Type-Options: nosniff is present on 429 register response")
        void should_setXContentTypeOptionsNosniff_when_registerBucketExhausted() throws Exception {
            when(registerBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/register");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when register bucket is exhausted")
                    .isEqualTo(429);
            assertThat(response.getHeader("X-Content-Type-Options"))
                    .as("X-Content-Type-Options must be nosniff on every 429 response from the filter")
                    .isEqualTo("nosniff");
        }

        @Test
        @DisplayName("X-Content-Type-Options: nosniff is present on 429 verify-email response (15-min window)")
        void should_setXContentTypeOptionsNosniff_when_verifyEmailBucketExhausted() throws Exception {
            when(verifyEmailBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/verify-email");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when verifyEmail bucket is exhausted")
                    .isEqualTo(429);
            assertThat(response.getHeader("X-Content-Type-Options"))
                    .as("X-Content-Type-Options must be nosniff on every 429 response from the filter")
                    .isEqualTo("nosniff");
        }

        @Test
        @DisplayName("X-Content-Type-Options: nosniff is present on 429 forgot-password response (60-min window)")
        void should_setXContentTypeOptionsNosniff_when_forgotPasswordBucketExhausted() throws Exception {
            when(forgotPasswordBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = postRequest("/api/v1/auth/forgot-password");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when forgotPassword bucket is exhausted")
                    .isEqualTo(429);
            assertThat(response.getHeader("X-Content-Type-Options"))
                    .as("X-Content-Type-Options must be nosniff on every 429 response from the filter")
                    .isEqualTo("nosniff");
        }

        @Test
        @DisplayName("X-Content-Type-Options: nosniff is present on 429 media-upload response (DELETE path)")
        void should_setXContentTypeOptionsNosniff_when_mediaUploadBucketExhausted() throws Exception {
            when(mediaUploadBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = deleteRequest("/api/v1/media/portfolio/" + java.util.UUID.randomUUID());
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when mediaUpload bucket is exhausted on DELETE")
                    .isEqualTo(429);
            assertThat(response.getHeader("X-Content-Type-Options"))
                    .as("X-Content-Type-Options must be nosniff on every 429 response from the filter")
                    .isEqualTo("nosniff");
        }

        @Test
        @DisplayName("X-Content-Type-Options: nosniff is present on 429 device-token response (DELETE path)")
        void should_setXContentTypeOptionsNosniff_when_deviceTokenBucketExhausted() throws Exception {
            when(deviceTokenBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var request  = deleteRequest("/api/v1/devices/token");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when deviceToken bucket is exhausted on DELETE")
                    .isEqualTo(429);
            assertThat(response.getHeader("X-Content-Type-Options"))
                    .as("X-Content-Type-Options must be nosniff on every 429 response from the filter")
                    .isEqualTo("nosniff");
        }

        @Test
        @DisplayName("X-Content-Type-Options: nosniff is present on 429 profile-update response (PATCH path)")
        void should_setXContentTypeOptionsNosniff_when_profileUpdateBucketExhausted() throws Exception {
            when(profileUpdateBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(false);

            var req = new MockHttpServletRequest("PATCH", "/api/v1/users/me");
            req.setRemoteAddr(REMOTE_ADDR);
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            doFilter(req, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 429 when profileUpdate bucket is exhausted on PATCH")
                    .isEqualTo(429);
            assertThat(response.getHeader("X-Content-Type-Options"))
                    .as("X-Content-Type-Options must be nosniff on every 429 response from the filter")
                    .isEqualTo("nosniff");
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("PATCH /api/v1/masters/me/profile")
    class MastersMeProfileEndpoint {

        @Test
        @DisplayName("returns 429 when SALON_MASTER exceeds rate limit on /api/v1/masters/me/profile")
        void should_return429_when_salonMasterExceedsRateLimitOnMastersMeProfile() throws Exception {
            log.debug("Arrange: profileUpdateBuckets returns bucket that allows 10 then denies the 11th");
            when(profileUpdateBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            MockHttpServletResponse lastResponse = null;
            MockFilterChain         lastChain    = null;

            log.debug("Act: send 11 PATCH requests to /api/v1/masters/me/profile from SALON_MASTER IP");
            for (int i = 0; i < 11; i++) {
                var request = new MockHttpServletRequest("PATCH", "/api/v1/masters/me/profile");
                request.setRemoteAddr(REMOTE_ADDR);
                request.addHeader("Authorization", "Bearer mock-salon-master-token");
                lastResponse = new MockHttpServletResponse();
                lastChain    = new MockFilterChain();
                doFilter(request, lastResponse, lastChain);

                if (i < 10) {
                    assertThat(lastResponse.getStatus())
                            .as("request %d must not be 429 — bucket not yet exhausted", i + 1)
                            .isNotEqualTo(429);
                }
            }

            assertThat(lastResponse.getStatus())
                    .as("11th request must be 429 — profileUpdateBuckets exhausted for /api/v1/masters/me/profile")
                    .isEqualTo(429);
            assertThat(lastResponse.getHeader("Retry-After")).isEqualTo("60");
            assertThat(lastResponse.getContentType())
                    .as("Content-Type must be application/json on 429 response")
                    .startsWith("application/json");
            assertThat(lastResponse.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(lastChain.getRequest())
                    .as("filter chain must not be forwarded on the throttled request")
                    .isNull();
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(registerBuckets);
            verifyNoInteractions(refreshBuckets);
        }
    }

    // ==========================================================================
    @Nested
    @DisplayName("POST /api/v1/service-categories/requests — 5/hr inbox-flood guard")
    class CategoryRequestEndpoint {

        @Test
        @DisplayName("routes to categoryRequestBuckets and passes through when within limit")
        void should_routeToCategoryRequestBuckets_when_withinLimit() throws Exception {
            log.debug("Arrange: categoryRequestBuckets returns bucket that allows consumption");
            when(categoryRequestBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/service-categories/requests");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /service-categories/requests within limit");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when category-request bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the bucket allows the request")
                    .isNotNull();
            verify(categoryRequestBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(registerBuckets);
            verifyNoInteractions(refreshBuckets);
        }

        @Test
        @DisplayName("returns 429 with 3600s Retry-After on the 6th request within the window")
        void should_return429_when_sixthCategoryRequestWithinWindow() throws Exception {
            // 5/hr cap: first five pass, the sixth is throttled. Retry-After mirrors the
            // 60-minute category-request window (3600 s).
            log.debug("Arrange: categoryRequestBuckets allows 5 then denies the 6th");
            when(categoryRequestBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            MockHttpServletResponse lastResponse = null;
            MockFilterChain         lastChain    = null;

            log.debug("Act: send 6 POSTs to /service-categories/requests from the same IP");
            for (int i = 0; i < 6; i++) {
                var request = postRequest("/api/v1/service-categories/requests");
                lastResponse = new MockHttpServletResponse();
                lastChain    = new MockFilterChain();
                doFilter(request, lastResponse, lastChain);

                if (i < 5) {
                    assertThat(lastResponse.getStatus())
                            .as("request %d must not be 429 — bucket not yet exhausted", i + 1)
                            .isNotEqualTo(429);
                }
            }

            assertThat(lastResponse.getStatus())
                    .as("6th request must be 429 — category-request bucket exhausted (5/hr)")
                    .isEqualTo(429);
            assertThat(lastResponse.getHeader("Retry-After"))
                    .as("Retry-After must reflect the 60-minute category-request window")
                    .isEqualTo("3600");
            assertThat(lastResponse.getContentType())
                    .as("Content-Type must be application/json on 429 category-request response")
                    .startsWith("application/json");
            assertThat(lastResponse.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(lastChain.getRequest())
                    .as("filter chain must not be forwarded on the throttled request")
                    .isNull();
            verify(categoryRequestBuckets, times(6)).get(REMOTE_ADDR);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(registerBuckets);
            verifyNoInteractions(refreshBuckets);
        }

        @Test
        @DisplayName("GET /api/v1/service-categories/requests is not rate-limited (POST-only)")
        void should_passThrough_when_getCategoryRequests() throws Exception {
            log.debug("Arrange: GET on the category-request path — non-POST bypasses the limiter");
            var request  = getRequest("/api/v1/service-categories/requests");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for GET /service-categories/requests");
            doFilter(request, response, chain);

            assertThat(chain.getRequest())
                    .as("chain must be forwarded — GET on the category-request path is not rate-limited")
                    .isNotNull();
            verifyNoInteractions(categoryRequestBuckets);
        }
    }

    // ==========================================================================
    // Phase 16.7 — POST /api/v1/service-types/suggest emails the admin on every
    // successful suggestion, so it is an inbox-flood surface throttled at 5/hr
    // (mirrors the category-request limiter). Window is 60 minutes → Retry-After 3600.
    @Nested
    @DisplayName("POST /api/v1/service-types/suggest — 5/hr inbox-flood guard")
    class SuggestServiceTypeEndpoint {

        @Test
        @DisplayName("routes to suggestServiceTypeBuckets and passes through when within limit")
        void should_routeToSuggestServiceTypeBuckets_when_withinLimit() throws Exception {
            log.debug("Arrange: suggestServiceTypeBuckets returns a bucket that allows consumption");
            when(suggestServiceTypeBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/service-types/suggest");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /service-types/suggest within limit");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when suggest bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the bucket allows the request")
                    .isNotNull();
            verify(suggestServiceTypeBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(categoryRequestBuckets);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(registerBuckets);
        }

        @Test
        @DisplayName("returns 429 with 3600s Retry-After on the 6th request within the window")
        void should_return429_when_sixthSuggestWithinWindow() throws Exception {
            // 5/hr cap: first five pass, the sixth is throttled. Retry-After mirrors the
            // 60-minute suggest-service-type window (3600 s).
            log.debug("Arrange: suggestServiceTypeBuckets allows 5 then denies the 6th");
            when(suggestServiceTypeBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            MockHttpServletResponse lastResponse = null;
            MockFilterChain         lastChain    = null;

            log.debug("Act: send 6 POSTs to /service-types/suggest from the same IP");
            for (int i = 0; i < 6; i++) {
                var request = postRequest("/api/v1/service-types/suggest");
                lastResponse = new MockHttpServletResponse();
                lastChain    = new MockFilterChain();
                doFilter(request, lastResponse, lastChain);

                if (i < 5) {
                    assertThat(lastResponse.getStatus())
                            .as("request %d must not be 429 — bucket not yet exhausted", i + 1)
                            .isNotEqualTo(429);
                }
            }

            assertThat(lastResponse.getStatus())
                    .as("6th request must be 429 — suggest-service-type bucket exhausted (5/hr)")
                    .isEqualTo(429);
            assertThat(lastResponse.getHeader("Retry-After"))
                    .as("Retry-After must reflect the 60-minute suggest-service-type window")
                    .isEqualTo("3600");
            assertThat(lastResponse.getContentType())
                    .as("Content-Type must be application/json on 429 suggest response")
                    .startsWith("application/json");
            assertThat(lastResponse.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(lastChain.getRequest())
                    .as("filter chain must not be forwarded on the throttled request")
                    .isNull();
            verify(suggestServiceTypeBuckets, times(6)).get(REMOTE_ADDR);
            verifyNoInteractions(categoryRequestBuckets);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(registerBuckets);
        }

        @Test
        @DisplayName("GET /api/v1/service-types/suggest is not rate-limited (POST-only)")
        void should_passThrough_when_getSuggestServiceType() throws Exception {
            log.debug("Arrange: GET on the suggest path — non-POST bypasses the limiter");
            var request  = getRequest("/api/v1/service-types/suggest");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for GET /service-types/suggest");
            doFilter(request, response, chain);

            assertThat(chain.getRequest())
                    .as("chain must be forwarded — GET on the suggest path is not rate-limited")
                    .isNotNull();
            verifyNoInteractions(suggestServiceTypeBuckets);
        }
    }

    // ==========================================================================
    // POST /api/v1/support/contact emails the support inbox on every successful
    // request, so it is an email-bomb / outbound-quota surface throttled at 5/hr
    // (mirrors the category-request limiter). Window is 60 minutes → Retry-After 3600.
    @Nested
    @DisplayName("POST /api/v1/support/contact — 5/hr email-bomb guard")
    class SupportContactEndpoint {

        @Test
        @DisplayName("routes to supportContactBuckets and passes through when within limit")
        void should_routeToSupportContactBuckets_when_withinLimit() throws Exception {
            log.debug("Arrange: supportContactBuckets returns a bucket that allows consumption");
            when(supportContactBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/support/contact");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /support/contact within limit");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when support-contact bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the bucket allows the request")
                    .isNotNull();
            verify(supportContactBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(categoryRequestBuckets);
            verifyNoInteractions(suggestServiceTypeBuckets);
            verifyNoInteractions(loginBuckets);
        }

        @Test
        @DisplayName("returns 429 with 3600s Retry-After when supportContact called over the limit")
        void should_return429_when_supportContactCalledOverLimit() throws Exception {
            // 5/hr cap: first five pass, the sixth is throttled. Retry-After mirrors the
            // 60-minute support-contact window (3600 s).
            log.debug("Arrange: supportContactBuckets allows 5 then denies the 6th");
            when(supportContactBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            MockHttpServletResponse lastResponse = null;
            MockFilterChain         lastChain    = null;

            log.debug("Act: send 6 POSTs to /support/contact from the same IP");
            for (int i = 0; i < 6; i++) {
                var request = postRequest("/api/v1/support/contact");
                lastResponse = new MockHttpServletResponse();
                lastChain    = new MockFilterChain();
                doFilter(request, lastResponse, lastChain);

                if (i < 5) {
                    assertThat(lastResponse.getStatus())
                            .as("request %d must not be 429 — bucket not yet exhausted", i + 1)
                            .isNotEqualTo(429);
                }
            }

            assertThat(lastResponse.getStatus())
                    .as("6th request must be 429 — support-contact bucket exhausted (5/hr)")
                    .isEqualTo(429);
            assertThat(lastResponse.getHeader("Retry-After"))
                    .as("Retry-After must reflect the 60-minute support-contact window")
                    .isEqualTo("3600");
            assertThat(lastResponse.getContentType())
                    .as("Content-Type must be application/json on 429 support-contact response")
                    .startsWith("application/json");
            assertThat(lastResponse.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(lastChain.getRequest())
                    .as("filter chain must not be forwarded on the throttled request")
                    .isNull();
            verify(supportContactBuckets, times(6)).get(REMOTE_ADDR);
            verifyNoInteractions(categoryRequestBuckets);
            verifyNoInteractions(loginBuckets);
            verifyNoInteractions(registerBuckets);
        }

        @Test
        @DisplayName("GET /api/v1/support/contact is not rate-limited (POST-only)")
        void should_passThrough_when_getSupportContact() throws Exception {
            log.debug("Arrange: GET on the support-contact path — non-POST bypasses the limiter");
            var request  = getRequest("/api/v1/support/contact");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for GET /support/contact");
            doFilter(request, response, chain);

            assertThat(chain.getRequest())
                    .as("chain must be forwarded — GET on the support-contact path is not rate-limited")
                    .isNotNull();
            verifyNoInteractions(supportContactBuckets);
        }
    }

    // Phase 13.2 — POST /api/v1/book/otp/send: per-IP SMS-bomb guard (3 / 15 min).
    // Window is 15 minutes → Retry-After 900. This is the IP-layer defence complementing
    // the per-phone rate limit inside PhoneOtpService.
    @Nested
    @DisplayName("POST /api/v1/book/otp/send — 3/15min SMS-bomb guard")
    class OtpSendEndpoint {

        @Test
        @DisplayName("routes to otpSendBuckets and passes through when within limit")
        void should_routeToOtpSendBuckets_when_withinLimit() throws Exception {
            log.debug("Arrange: otpSendBuckets returns a bucket that allows consumption");
            when(otpSendBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1)).thenReturn(true);

            var request  = postRequest("/api/v1/book/otp/send");
            var response = new MockHttpServletResponse();
            var chain    = new MockFilterChain();

            log.debug("Act: doFilterInternal for POST /book/otp/send within limit");
            doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("status must be 200 when otp-send bucket allows the request")
                    .isEqualTo(200);
            assertThat(chain.getRequest())
                    .as("filter chain must be forwarded when the bucket allows the request")
                    .isNotNull();
            verify(otpSendBuckets).get(REMOTE_ADDR);
            verifyNoInteractions(supportContactBuckets);
            verifyNoInteractions(loginBuckets);
        }

        @Test
        @DisplayName("returns 429 with 900s Retry-After when otp-send called over the limit")
        void should_return429_when_otpSendCalledOverLimit() throws Exception {
            // 3 / 15 min cap: first three pass, the fourth is throttled. Retry-After mirrors
            // the 15-minute otp-send window (900 s).
            log.debug("Arrange: otpSendBuckets allows 3 then denies the 4th");
            when(otpSendBuckets.get(REMOTE_ADDR)).thenReturn(bucket);
            when(bucket.tryConsume(1))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);

            MockHttpServletResponse lastResponse = null;
            MockFilterChain         lastChain    = null;

            log.debug("Act: send 4 POSTs to /book/otp/send from the same IP");
            for (int i = 0; i < 4; i++) {
                var request = postRequest("/api/v1/book/otp/send");
                lastResponse = new MockHttpServletResponse();
                lastChain    = new MockFilterChain();
                doFilter(request, lastResponse, lastChain);

                if (i < 3) {
                    assertThat(lastResponse.getStatus())
                            .as("request %d must not be 429 — bucket not yet exhausted", i + 1)
                            .isNotEqualTo(429);
                }
            }

            assertThat(lastResponse.getStatus())
                    .as("4th request must be 429 — otp-send bucket exhausted (3/15min)")
                    .isEqualTo(429);
            assertThat(lastResponse.getHeader("Retry-After"))
                    .as("Retry-After must reflect the 15-minute otp-send window")
                    .isEqualTo("900");
            assertThat(lastResponse.getContentAsString()).isEqualTo("{\"error\":\"Too many requests\"}");
            assertThat(lastChain.getRequest())
                    .as("filter chain must not be forwarded on the throttled request")
                    .isNull();
            verify(otpSendBuckets, times(4)).get(REMOTE_ADDR);
            verifyNoInteractions(loginBuckets);
        }

        @Test
        @DisplayName("POST /api/v1/book/otp/verify IS rate-limited per-IP (internal verify bucket, not otpSendBuckets)")
        void should_rateLimit_when_postOtpVerify() throws Exception {
            // CRITICAL-fix IP layer: /verify is throttled by an internal verify bucket
            // (10 / 15 min), NOT by the injected otpSendBuckets. Fire well above the cap
            // from one IP and assert at least one request is throttled (429) and not
            // forwarded — and that the /send bucket is never consulted for a /verify path.
            log.debug("Arrange: fire many POST /book/otp/verify from one IP — internal verify bucket must throttle");

            MockHttpServletResponse lastResponse = null;
            MockFilterChain lastChain = null;
            boolean anyThrottled = false;

            for (int i = 0; i < 25; i++) {
                var request  = postRequest("/api/v1/book/otp/verify");
                lastResponse = new MockHttpServletResponse();
                lastChain    = new MockFilterChain();
                doFilter(request, lastResponse, lastChain);
                if (lastResponse.getStatus() == 429) {
                    anyThrottled = true;
                    break;
                }
            }

            assertThat(anyThrottled)
                    .as("POST /book/otp/verify must be IP-throttled by the internal verify bucket")
                    .isTrue();
            assertThat(lastResponse.getStatus())
                    .as("the throttled verify request must return 429")
                    .isEqualTo(429);
            assertThat(lastResponse.getHeader("Retry-After"))
                    .as("Retry-After must reflect the 15-minute verify window")
                    .isEqualTo("900");
            assertThat(lastChain.getRequest())
                    .as("the throttled verify request must NOT be forwarded down the chain")
                    .isNull();
            // /verify must never consume the /send bucket.
            verifyNoInteractions(otpSendBuckets);
        }
    }
}
