package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SEC-FIX REGRESSION NET — per-IP throttle on the SINGLE-item service write routes.</b>
 *
 * <p>Every non-bulk write endpoint on {@code ServiceController} previously fell through
 * {@link AuthRateLimitFilter#doFilterInternal} to the unmatched {@code else} / non-POST branch
 * with NO bucket at all:
 * <ul>
 *   <li>{@code POST   /api/v1/independent-masters/me/services}</li>
 *   <li>{@code POST   /api/v1/salons/{salonId}/services}</li>
 *   <li>{@code POST   /api/v1/salons/{salonId}/masters/{masterId}/services}</li>
 *   <li>{@code PATCH  /api/v1/services/{serviceDefId}}</li>
 *   <li>{@code PATCH  /api/v1/services/{serviceDefId}/photo}</li>
 *   <li>{@code DELETE /api/v1/services/{serviceDefId}}</li>
 * </ul>
 *
 * <p>That gap undercut {@code bulkServiceSetupBuckets}' own rationale: the bulk path is capped at
 * 10/min per IP specifically to bound {@code service_definitions} row growth, but an attacker
 * chasing that growth just looped the unthrottled single-create instead — the strictly better
 * lever, and one that skips the per-master advisory lock too. The filter now caps all six routes
 * at {@code app.rate-limit.service-write-capacity} (60 / 60 s per IP) via {@code serviceWriteBuckets}.
 *
 * <p>The production bucket is an injected {@code @Qualifier} bean whose capacity is raised to
 * 100 000 in {@code src/test/resources/application-test.yml} so integration tests firing many
 * service writes from 127.0.0.1 are not themselves throttled (Apache HC5 honours
 * {@code Retry-After}, so an unraised cap turns a throttle hit into a 60-second-long PASS rather
 * than a failure). This test therefore builds the filter DIRECTLY against a hand-built,
 * single-slot bucket — bypassing that property entirely — so the raised test capacity cannot
 * neuter the throttling coverage. Same pattern as {@code BookingRateLimitFilterTest}'s
 * decline/not-complete assertions.
 */
@DisplayName("AuthRateLimitFilter — single-item service write per-IP throttle (SEC-fix regression net)")
class ServiceWriteRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.99";
    /** Capacity of the hand-built service-write bucket under test — deliberately tiny. */
    private static final long TEST_SERVICE_WRITE_CAPACITY = 3;

    private static LoadingCache<String, Bucket> permissive() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(bandwidth(1_000_000))
                .build());
    }

    /** The bucket under test: 3 tokens / minute, so the 4th request in a burst must 429. */
    private static LoadingCache<String, Bucket> tinyServiceWriteCache() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(bandwidth(TEST_SERVICE_WRITE_CAPACITY))
                .build());
    }

    private static Bandwidth bandwidth(long capacity) {
        return BandwidthBuilder.builder()
                .capacity(capacity)
                .refillIntervally(capacity, Duration.ofMinutes(1))
                .build();
    }

    /**
     * 19 positional caches — one per {@code @Qualifier} arg on the production constructor. Only
     * the LAST (service-write) is constrained; every other bucket is permissive so a 429 here can
     * only have come from the branch under test.
     */
    private AuthRateLimitFilter filterWithTinyServiceWriteBucket() {
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), tinyServiceWriteCache());
    }

    private MockHttpServletRequest write(String method, String uri) {
        var req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    // ── the throttle itself ────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} {1} is throttled after the cap")
    @CsvSource({
            "POST,   /api/v1/independent-masters/me/services",
            "POST,   /api/v1/salons/SALON/services",
            "POST,   /api/v1/salons/SALON/masters/MASTER/services",
            "PATCH,  /api/v1/services/DEF",
            "PATCH,  /api/v1/services/DEF/photo",
            "DELETE, /api/v1/services/DEF"
    })
    @DisplayName("should_return429_when_singleServiceWriteExceedsPerIpCap")
    void should_return429_when_singleServiceWriteExceedsPerIpCap(String method, String uriTemplate)
            throws Exception {
        AuthRateLimitFilter filter = filterWithTinyServiceWriteBucket();
        String uri = resolve(uriTemplate);

        int allowedBeforeThrottle = 0;
        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        for (int i = 0; i < TEST_SERVICE_WRITE_CAPACITY + 5; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(write(method, uri), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(allowedBeforeThrottle)
                .as("%s %s must consume the service-write bucket and 429 on request %d — an "
                        + "unthrottled route means the single-item write gap is back",
                        method, uriTemplate, TEST_SERVICE_WRITE_CAPACITY + 1)
                .isEqualTo(TEST_SERVICE_WRITE_CAPACITY);
        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("a throttled service write must NOT be forwarded down the filter chain")
                .isNull();
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("a throttled service write must carry Retry-After so clients back off")
                .isEqualTo("60");
        assertThat(lastResponse.getContentType()).contains("application/json");
    }

    @Test
    @DisplayName("should_shareOneBucketAcrossRoutes_when_differentSingleWriteRoutesAreMixed")
    void should_shareOneBucketAcrossRoutes_when_differentSingleWriteRoutesAreMixed() throws Exception {
        // All six routes are one class of write and share ONE bucket by design: a caller must not
        // be able to multiply their budget by rotating create -> patch -> delete.
        AuthRateLimitFilter filter = filterWithTinyServiceWriteBucket();

        filter.doFilterInternal(write("POST", "/api/v1/independent-masters/me/services"),
                new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilterInternal(write("PATCH", "/api/v1/services/" + UUID.randomUUID()),
                new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilterInternal(write("DELETE", "/api/v1/services/" + UUID.randomUUID()),
                new MockHttpServletResponse(), new MockFilterChain());
        var fourth = new MockHttpServletResponse();
        filter.doFilterInternal(write("POST", "/api/v1/salons/" + UUID.randomUUID() + "/services"),
                fourth, new MockFilterChain());

        assertThat(fourth.getStatus())
                .as("three writes across three different routes must exhaust the shared "
                        + "capacity-%d bucket, so the fourth 429s", TEST_SERVICE_WRITE_CAPACITY)
                .isEqualTo(429);
    }

    @Test
    @DisplayName("should_notConsumeServiceWriteBucket_when_pathIsBulkRoute")
    void should_notConsumeServiceWriteBucket_when_pathIsBulkRoute() throws Exception {
        // The bulk routes keep their OWN (permissive here) bulkServiceSetupBuckets. Sharing would
        // either impose the tight bulk cap on per-item usage or re-loosen the bulk DoS amplifier.
        AuthRateLimitFilter filter = filterWithTinyServiceWriteBucket();
        UUID salonId = UUID.randomUUID();

        MockHttpServletResponse last = null;
        for (int i = 0; i < TEST_SERVICE_WRITE_CAPACITY + 5; i++) {
            last = new MockHttpServletResponse();
            filter.doFilterInternal(
                    write("POST", "/api/v1/independent-masters/me/services/bulk"), last, new MockFilterChain());
        }
        assertThat(last.getStatus())
                .as("the IM bulk route must not draw on the single-write bucket")
                .isNotEqualTo(429);

        for (int i = 0; i < TEST_SERVICE_WRITE_CAPACITY + 5; i++) {
            last = new MockHttpServletResponse();
            filter.doFilterInternal(
                    write("POST", "/api/v1/salons/" + salonId + "/masters/" + UUID.randomUUID() + "/services/bulk"),
                    last, new MockFilterChain());
        }
        assertThat(last.getStatus())
                .as("the salon bulk route must not draw on the single-write bucket")
                .isNotEqualTo(429);
    }

    @ParameterizedTest(name = "{0} {1} must not be captured by the service-write branch")
    @CsvSource({
            // Sibling namespaces that must NOT match the /api/v1/services/ prefix rule.
            "POST,  /api/v1/service-categories/requests",
            "POST,  /api/v1/service-types/suggest",
            // Salon POST that does not end in the literal /services.
            "POST,  /api/v1/salons/SALON/invite",
            // Public reads on the same paths — GET is never a write.
            "GET,   /api/v1/masters/MASTER/services",
            "GET,   /api/v1/salons/SALON/services"
    })
    @DisplayName("should_notThrottle_when_routeIsNotASingleItemServiceWrite")
    void should_notThrottle_when_routeIsNotASingleItemServiceWrite(String method, String uriTemplate)
            throws Exception {
        AuthRateLimitFilter filter = filterWithTinyServiceWriteBucket();
        String uri = resolve(uriTemplate);

        MockHttpServletResponse last = null;
        for (int i = 0; i < TEST_SERVICE_WRITE_CAPACITY + 5; i++) {
            last = new MockHttpServletResponse();
            filter.doFilterInternal(write(method, uri), last, new MockFilterChain());
        }

        assertThat(last.getStatus())
                .as("%s %s must not be starved by the single-item service-write bucket",
                        method, uriTemplate)
                .isNotEqualTo(429);
    }

    /** Substitutes fresh UUIDs for the {@code SALON}/{@code MASTER}/{@code DEF} placeholders. */
    private static String resolve(String uriTemplate) {
        return uriTemplate.trim()
                .replace("SALON", UUID.randomUUID().toString())
                .replace("MASTER", UUID.randomUUID().toString())
                .replace("DEF", UUID.randomUUID().toString());
    }
}
