package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import com.beautica.config.RateLimitConfig;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Value;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-IP throttle on the four token-authenticated, {@code permitAll} admin review POSTs
 * (category-request and service-type-suggestion approve/reject). They previously fell through to the
 * unthrottled branch, so a garbage-token flood hit the DB on every call. All four share ONE bucket.
 */
@DisplayName("AuthRateLimitFilter — admin review POSTs per-IP throttle")
class ReviewPostRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.91";
    private static final String OTHER_ADDR = "10.0.0.92";
    private static final int TEST_CAPACITY = 3;
    private static final String[] REVIEW_PATHS = {
            "/api/v1/service-categories/requests/approve",
            "/api/v1/service-categories/requests/reject",
            "/api/v1/service-types/suggestions/approve",
            "/api/v1/service-types/suggestions/reject"
    };

    private static LoadingCache<String, Bucket> cacheOf(long capacity, Duration window) {
        Bandwidth bandwidth = BandwidthBuilder.builder()
                .capacity(capacity)
                .refillIntervally(capacity, window)
                .build();
        return Caffeine.newBuilder().build(key -> Bucket.builder().addLimit(bandwidth).build());
    }

    /** Only the review-decision bucket is constrained (resolved by @Qualifier name, not position). */
    private AuthRateLimitFilter filterWithTinyReviewBucket() {
        return AuthRateLimitFilterTestFactory.withBucket(
                "reviewDecisionBuckets", cacheOf(TEST_CAPACITY, Duration.ofMinutes(60)));
    }

    private static MockHttpServletRequest post(String path, String remoteAddr) {
        var req = new MockHttpServletRequest("POST", path);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_reviewPostFlooded")
    void should_return429_when_reviewPostFlooded() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyReviewBucket();

        MockHttpServletResponse last = null;
        MockFilterChain lastChain = null;
        int allowed = 0;
        for (int i = 0; i < TEST_CAPACITY + 5; i++) {
            last = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(post(REVIEW_PATHS[0], REMOTE_ADDR), last, lastChain);
            if (last.getStatus() == 429) {
                break;
            }
            allowed++;
        }

        assertThat(allowed).isEqualTo(TEST_CAPACITY);
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(last.getContentType()).contains("application/json");
        assertThat(lastChain.getRequest()).as("throttled request must not reach the chain").isNull();
    }

    @Test
    @DisplayName("should_shareOneBucket_when_allFourReviewPathsHitFromOneIp")
    void should_shareOneBucket_when_allFourReviewPathsHitFromOneIp() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyReviewBucket();
        for (int i = 0; i < TEST_CAPACITY; i++) {
            filter.doFilterInternal(post(REVIEW_PATHS[i % 2], REMOTE_ADDR),
                    new MockHttpServletResponse(), new MockFilterChain());
        }

        for (String path : REVIEW_PATHS) {
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(post(path, REMOTE_ADDR), response, new MockFilterChain());

            assertThat(response.getStatus()).as("%s drains the same bucket", path).isEqualTo(429);
        }
    }

    @Test
    @DisplayName("should_throttlePerIp_when_oneSourceIsExhausted")
    void should_throttlePerIp_when_oneSourceIsExhausted() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyReviewBucket();
        for (int i = 0; i < TEST_CAPACITY + 2; i++) {
            filter.doFilterInternal(post(REVIEW_PATHS[2], REMOTE_ADDR),
                    new MockHttpServletResponse(), new MockFilterChain());
        }

        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilterInternal(post(REVIEW_PATHS[2], OTHER_ADDR), response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("should_defaultTo20PerHour_when_noPropertyConfigured")
    void should_defaultTo20PerHour_when_noPropertyConfigured() throws Exception {
        var field = RateLimitConfig.class.getDeclaredField("reviewDecisionCapacity");

        assertThat(field.getAnnotation(Value.class).value())
                .isEqualTo("${app.rate-limit.review-decision-capacity:20}");
    }

    @Test
    @DisplayName("should_notThrottleReviewBucket_when_getReviewPage")
    void should_notThrottleReviewBucket_when_getReviewPage() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyReviewBucket();

        for (int i = 0; i < TEST_CAPACITY + 3; i++) {
            var req = new MockHttpServletRequest("GET", "/api/v1/service-categories/requests/review/abc");
            req.setRemoteAddr(REMOTE_ADDR);
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(req, response, new MockFilterChain());

            assertThat(response.getStatus()).isNotEqualTo(429);
        }
    }
}
