package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-IP throttle on the two token-bearing, {@code permitAll} admin review GET pages. A garbage token
 * still hits the DB via {@code loadForReview}, and every non-POST used to bypass the filter. They are
 * charged to {@code reviewPageBuckets}, a SIBLING of the POST {@code reviewDecisionBuckets}.
 */
@DisplayName("AuthRateLimitFilter — admin review GET pages per-IP throttle")
class ReviewGetRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.93";
    private static final String OTHER_ADDR = "10.0.0.94";
    private static final int TEST_CAPACITY = 3;
    private static final String CATEGORY_PAGE = "/api/v1/service-categories/requests/review/garbage-token";
    private static final String SUGGESTION_PAGE = "/api/v1/service-types/suggestions/review";
    private static final String CATEGORY_APPROVE = "/api/v1/service-categories/requests/approve";

    /** Mirrors the production 60-minute window so Retry-After reflects it. */
    private static LoadingCache<String, Bucket> hourlyBuckets(long capacity) {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(BandwidthBuilder.builder()
                        .capacity(capacity)
                        .refillIntervally(capacity, Duration.ofMinutes(60))
                        .build())
                .build());
    }

    private static AuthRateLimitFilter filterWithTinyPageBucket() {
        return AuthRateLimitFilterTestFactory.withBucket("reviewPageBuckets", hourlyBuckets(TEST_CAPACITY));
    }

    private static MockHttpServletRequest request(String method, String path, String remoteAddr) {
        var req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private static int status(AuthRateLimitFilter filter, MockHttpServletRequest req) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(req, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("should_return429_when_reviewGetExceedsCapacity")
    void should_return429_when_reviewGetExceedsCapacity() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyPageBucket();

        MockHttpServletResponse last = null;
        MockFilterChain lastChain = null;
        int allowed = 0;
        for (int i = 0; i < TEST_CAPACITY + 5; i++) {
            last = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(request("GET", CATEGORY_PAGE, REMOTE_ADDR), last, lastChain);
            if (last.getStatus() == 429) {
                break;
            }
            allowed++;
        }

        assertThat(allowed).isEqualTo(TEST_CAPACITY);
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(lastChain.getRequest()).as("throttled request must not reach the chain").isNull();
    }

    @Test
    @DisplayName("should_return429_when_reviewHeadExceedsCapacity")
    void should_return429_when_reviewHeadExceedsCapacity() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyPageBucket();
        for (int i = 0; i < TEST_CAPACITY; i++) {
            assertThat(status(filter, request("HEAD", CATEGORY_PAGE, REMOTE_ADDR))).isNotEqualTo(429);
        }

        assertThat(status(filter, request("HEAD", CATEGORY_PAGE, REMOTE_ADDR))).isEqualTo(429);
        assertThat(status(filter, request("HEAD", SUGGESTION_PAGE, REMOTE_ADDR))).isEqualTo(429);
    }

    @Test
    @DisplayName("should_shareOneBucket_when_bothReviewPagesHitFromOneIp")
    void should_shareOneBucket_when_bothReviewPagesHitFromOneIp() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyPageBucket();
        for (int i = 0; i < TEST_CAPACITY; i++) {
            status(filter, request("GET", i % 2 == 0 ? CATEGORY_PAGE : SUGGESTION_PAGE, REMOTE_ADDR));
        }

        assertThat(status(filter, request("GET", CATEGORY_PAGE, REMOTE_ADDR))).isEqualTo(429);
        assertThat(status(filter, request("GET", SUGGESTION_PAGE, REMOTE_ADDR))).isEqualTo(429);
    }

    @Test
    @DisplayName("should_return429_when_encodedPathVariantExceedsCapacity")
    void should_return429_when_encodedPathVariantExceedsCapacity() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyPageBucket();
        String[] variants = {
                "/api/v1/service-categories/requests/review/garbage-token",
                "/api/v1/service-categories%2Frequests/review/garbage-token",
                "/api/v1/service-types/suggestions;x=1/review",
                "/api/v1/service-types/suggestions/./review",
        };
        for (int i = 0; i < TEST_CAPACITY; i++) {
            status(filter, request("GET", CATEGORY_PAGE, REMOTE_ADDR));
        }

        for (String variant : variants) {
            assertThat(status(filter, request("GET", variant, REMOTE_ADDR)))
                    .as("%s is the same page and must drain the same bucket", variant)
                    .isEqualTo(429);
        }
    }

    @Test
    @DisplayName("should_keepGetAndPostBucketsIndependent_when_oneIsExhausted")
    void should_keepGetAndPostBucketsIndependent_when_oneIsExhausted() throws Exception {
        AuthRateLimitFilter filter = AuthRateLimitFilterTestFactory.withBuckets(Map.<String, LoadingCache<String, Bucket>>of(
                "reviewPageBuckets", AuthRateLimitFilterTestFactory.bucketsOf(TEST_CAPACITY),
                "reviewDecisionBuckets", AuthRateLimitFilterTestFactory.bucketsOf(TEST_CAPACITY)));

        for (int i = 0; i < TEST_CAPACITY + 1; i++) {
            status(filter, request("GET", CATEGORY_PAGE, REMOTE_ADDR));
        }
        assertThat(status(filter, request("GET", CATEGORY_PAGE, REMOTE_ADDR))).as("GET exhausted").isEqualTo(429);
        for (int i = 0; i < TEST_CAPACITY; i++) {
            assertThat(status(filter, request("POST", CATEGORY_APPROVE, REMOTE_ADDR)))
                    .as("POST %d still allowed", i).isNotEqualTo(429);
        }
        assertThat(status(filter, request("POST", CATEGORY_APPROVE, REMOTE_ADDR))).as("POST exhausted").isEqualTo(429);
        assertThat(status(filter, request("GET", SUGGESTION_PAGE, OTHER_ADDR)))
                .as("exhausting POST must not matter to other IP's GET").isNotEqualTo(429);
    }

    @Test
    @DisplayName("should_notDrainGetBucket_when_postDecisionsSent")
    void should_notDrainGetBucket_when_postDecisionsSent() throws Exception {
        AuthRateLimitFilter filter = AuthRateLimitFilterTestFactory.withBuckets(Map.<String, LoadingCache<String, Bucket>>of(
                "reviewPageBuckets", AuthRateLimitFilterTestFactory.bucketsOf(TEST_CAPACITY),
                "reviewDecisionBuckets", AuthRateLimitFilterTestFactory.bucketsOf(1)));
        status(filter, request("POST", CATEGORY_APPROVE, REMOTE_ADDR));
        assertThat(status(filter, request("POST", CATEGORY_APPROVE, REMOTE_ADDR))).isEqualTo(429);

        for (int i = 0; i < TEST_CAPACITY; i++) {
            assertThat(status(filter, request("GET", CATEGORY_PAGE, REMOTE_ADDR))).isNotEqualTo(429);
        }
    }

    @Test
    @DisplayName("should_throttlePerIp_when_oneSourceIsExhausted")
    void should_throttlePerIp_when_oneSourceIsExhausted() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyPageBucket();
        for (int i = 0; i < TEST_CAPACITY + 2; i++) {
            status(filter, request("GET", SUGGESTION_PAGE, REMOTE_ADDR));
        }

        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request("GET", SUGGESTION_PAGE, OTHER_ADDR), response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("should_notThrottle_when_unrelatedGet")
    void should_notThrottle_when_unrelatedGet() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyPageBucket();

        for (int i = 0; i < TEST_CAPACITY + 3; i++) {
            assertThat(status(filter, request("GET", "/api/v1/service-types/suggestions", REMOTE_ADDR)))
                    .isNotEqualTo(429);
        }
    }
}
