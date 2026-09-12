package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>MEDIUM-FIX REGRESSION NET (Phase 314 audit) — per-IP throttle on the two public
 * catalogue-browse reads.</b>
 *
 * <p>{@code GET /api/v1/salons/{salonId}/services} and {@code GET /api/v1/masters/{masterId}/services}
 * are {@code permitAll()} in {@code SecurityConfig}. {@code ServiceCatalogService}'s
 * {@code @Cacheable(key = "#salonId"/"#masterId")} only absorbs repeat hits on the SAME id — a
 * caller sweeping distinct ids forced a cache miss plus, on a cold key, a per-master N+1 read on
 * every request, previously with no throttle anywhere in {@link AuthRateLimitFilter} at all.
 * <b>Phase 315 killed the N+1 itself</b> ({@code SlotCalculationService#filterBookableAssignmentsBatch}
 * now resolves every master in the salon in a statement count invariant in master count — see
 * {@code SalonCatalogueBatchLoadIT}'s D6 ledger, which corrects the finding's claimed "3 SQL
 * statements per master" to the measured 4: two schedule/override bulk loads, one lazy
 * {@code WeeklySchedule.discreteTimes} batch-fetch the finding did not enumerate, and one booking
 * load). The throttle below is therefore now a backstop on an O(1) read, not blast-radius control
 * over an O(masters) one — it stays in place regardless, since a cold key is still a real cache miss
 * regardless of how cheap the miss became. Both browse paths share ONE bucket,
 * {@code catalogueBrowseBuckets} (see {@code RateLimitConfig#catalogueBrowseCapacity} for the
 * 60/60s sizing rationale).
 *
 * <p>The production bucket is an injected {@code @Qualifier} bean whose capacity is raised to
 * 100 000 in {@code src/test/resources/application-test.yml} so {@code ServicesIntegrationTest},
 * {@code SalonCatalogueAggregatePriceIT} and {@code SalonSearchPriceBandIT} — which each fire many
 * real HTTP GETs against these two paths from 127.0.0.1 — are not themselves throttled. This test
 * therefore builds the filter DIRECTLY against a hand-built, single-slot bucket at the matching
 * constructor position — bypassing that property entirely — so the raised test capacity cannot
 * neuter the throttling coverage. Same pattern as {@code InviteValidateGetRateLimitTest} /
 * {@code ServiceWriteRateLimitRegressionTest}.
 */
@DisplayName("AuthRateLimitFilter — GET catalogue-browse per-IP throttle (Phase 314 MEDIUM-fix regression net)")
class CatalogueBrowseGetRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.201";
    private static final String OTHER_ADDR = "10.0.0.202";

    /** Capacity of the hand-built catalogue-browse bucket under test — deliberately tiny. */
    private static final long TEST_CATALOGUE_BROWSE_CAPACITY = 4;

    private static LoadingCache<String, Bucket> permissive() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(bandwidth(1_000_000))
                .build());
    }

    /** The bucket under test: 4 tokens / minute, so the 5th request in a burst must 429. */
    private static LoadingCache<String, Bucket> tinyCatalogueBrowseCache() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(bandwidth(TEST_CATALOGUE_BROWSE_CAPACITY))
                .build());
    }

    private static Bandwidth bandwidth(long capacity) {
        return BandwidthBuilder.builder()
                .capacity(capacity)
                .refillIntervally(capacity, Duration.ofMinutes(1))
                .build();
    }

    /**
     * 22 positional caches — one per {@code @Qualifier} arg on the production constructor. Only
     * the 22nd (catalogue-browse, LAST) is constrained; every other bucket — including the 20th
     * (invite-validate) and the 21st (invite-accept) — is permissive, so a 429 here can only have
     * come from the branch under test.
     */
    private AuthRateLimitFilter filterWithTinyCatalogueBrowseBucket() {
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(),
                permissive(), permissive(), tinyCatalogueBrowseCache());
    }

    private static MockHttpServletRequest get(String path, String remoteAddr) {
        var req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private static String salonServicesPath() {
        return "/api/v1/salons/" + UUID.randomUUID() + "/services";
    }

    private static String masterServicesPath() {
        return "/api/v1/masters/" + UUID.randomUUID() + "/services";
    }

    @Test
    @DisplayName("should_return429_when_salonCatalogueServicesFloodedFromOneIp")
    void should_return429_when_salonCatalogueServicesFloodedFromOneIp() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        String path = salonServicesPath();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        int allowedBeforeThrottle = 0;

        for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY + 5; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(get(path, REMOTE_ADDR), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(allowedBeforeThrottle)
                .as("GET %s must consume the catalogue-browse bucket and 429 on request %d — an "
                        + "unthrottled route means the flood guard is missing",
                        path, TEST_CATALOGUE_BROWSE_CAPACITY + 1)
                .isEqualTo(TEST_CATALOGUE_BROWSE_CAPACITY);
        assertThat(lastResponse.getStatus())
                .as("the throttled salon-catalogue request must return 429")
                .isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("a throttled caller must be told when to come back")
                .isEqualTo("60");
        assertThat(lastResponse.getContentType())
                .as("the 429 body is the JSON too-many-requests envelope")
                .contains("application/json");
        assertThat(lastChain.getRequest())
                .as("the throttled salon-catalogue request must NOT be forwarded down the filter "
                        + "chain — the cold-key catalogue read must never run for a throttled caller")
                .isNull();
    }

    @Test
    @DisplayName("should_return429_when_masterCatalogueServicesFloodedFromOneIp (shares the same bucket)")
    void should_return429_when_masterCatalogueServicesFloodedFromOneIp() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        String path = masterServicesPath();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        int allowedBeforeThrottle = 0;

        for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY + 5; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(get(path, REMOTE_ADDR), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(allowedBeforeThrottle)
                .as("GET %s must consume the SAME catalogue-browse bucket as the salon read", path)
                .isEqualTo(TEST_CATALOGUE_BROWSE_CAPACITY);
        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("the throttled master-catalogue request must NOT be forwarded")
                .isNull();
    }

    @Test
    @DisplayName("should_notThrottle_when_requestsStayUnderTheCap")
    void should_notThrottle_when_requestsStayUnderTheCap() throws Exception {
        // Non-vacuity for the flood tests above: legitimate traffic under the cap must sail
        // through untouched.
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        String path = salonServicesPath();

        for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY; i++) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilterInternal(get(path, REMOTE_ADDR), response, chain);

            assertThat(response.getStatus())
                    .as("request %d under the cap must pass", i + 1)
                    .isNotEqualTo(429);
            assertThat(chain.getRequest())
                    .as("request %d under the cap must be forwarded", i + 1)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("should_throttlePerIp_when_oneSourceIsAlreadyExhausted")
    void should_throttlePerIp_when_oneSourceIsAlreadyExhausted() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        String path = salonServicesPath();

        for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY + 5; i++) {
            filter.doFilterInternal(get(path, REMOTE_ADDR),
                    new MockHttpServletResponse(), new MockFilterChain());
        }

        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilterInternal(get(path, OTHER_ADDR), response, chain);

        assertThat(response.getStatus())
                .as("a second IP must keep its own budget after the first exhausted theirs")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("the second IP's request must be forwarded")
                .isNotNull();
    }

    /**
     * <b>The disambiguation pin.</b> {@code GET /api/v1/salons/{salonId}/masters/{masterId}/services}
     * (Phase 309's authenticated salon-management read) shares the exact same
     * {@code "/api/v1/salons/"} prefix and {@code "/services"} suffix as the public catalogue read
     * under test, but has TWO path variables, not one. It must stay the documented accepted-risk
     * exception on {@code RateLimitConfig#serviceWriteCapacity} — unthrottled, like every other
     * authenticated GET on {@code ServiceController} — even once the tiny catalogue-browse bucket
     * above is fully exhausted. A regression that widened {@code isSalonCatalogueServicesPath} (or
     * reverted to a bare prefix+suffix check) would fail this test with a 429.
     */
    @Test
    @DisplayName("should_notThrottle_when_salonManagementReadRequested (Phase 309 route stays untouched)")
    void should_notThrottle_when_salonManagementReadRequested() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        String catalogueSalonId = UUID.randomUUID().toString();
        String managementPath = "/api/v1/salons/" + catalogueSalonId + "/masters/" + UUID.randomUUID()
                + "/services";

        // Exhaust the tiny catalogue-browse bucket for this IP via the PUBLIC catalogue route.
        for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY + 5; i++) {
            filter.doFilterInternal(get("/api/v1/salons/" + catalogueSalonId + "/services", REMOTE_ADDR),
                    new MockHttpServletResponse(), new MockFilterChain());
        }

        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilterInternal(get(managementPath, REMOTE_ADDR), response, chain);

        assertThat(response.getStatus())
                .as("the Phase 309 salon-management read must NOT be throttled by the catalogue-"
                        + "browse bucket, even after that bucket is exhausted for the same IP")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("the salon-management read must be forwarded")
                .isNotNull();
    }

    /**
     * <b>The regression pin for the new branch's placement.</b> Adding the catalogue-browse GET
     * branch means it now runs ahead of the filter's unconditional non-POST early return. This
     * test proves the check is scoped to the two literal shapes: an unrelated, un-bucketed GET
     * (here, a master-reviews read that matches no branch in the filter at all) must sail through
     * completely unthrottled even when fired far past the tiny catalogue-browse cap under test. A
     * regression that widened the new check into a path-prefix or a blanket GET match would fail
     * this test with a 429.
     */
    @Test
    @DisplayName("should_notThrottle_when_unrelatedGetPathRequestedManyTimes")
    void should_notThrottle_when_unrelatedGetPathRequestedManyTimes() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        String unrelatedPath = "/api/v1/masters/" + UUID.randomUUID() + "/reviews";

        for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY + 20; i++) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilterInternal(get(unrelatedPath, REMOTE_ADDR), response, chain);

            assertThat(response.getStatus())
                    .as("request %d to an unrelated, un-bucketed GET path must never be throttled "
                            + "— the catalogue-browse check must not have widened", i + 1)
                    .isNotEqualTo(429);
            assertThat(chain.getRequest())
                    .as("request %d to an unrelated GET path must be forwarded", i + 1)
                    .isNotNull();
        }
    }
}
