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

import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
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
     * Builds the production filter with EVERY bucket permissive except {@code catalogueBrowseBuckets},
     * which gets the tiny cache under test — so a 429 anywhere below can only have come from the
     * branch this file exists to pin.
     *
     * <p><b>Resolved by {@code @Qualifier} NAME, never by a hard-coded position (2026-09-13 audit,
     * Q12).</b> This used to be 22 positional {@code permissive()} arguments with the constrained
     * cache typed in last. Inserting a new bucket qualifier anywhere but at the end silently shifted
     * which bucket was constrained, and every test in this file stayed GREEN while measuring the
     * wrong thing — a wiring bug with no failing test, the exact shape the file is supposed to
     * catch. The reflection below reads each constructor parameter's {@code @Qualifier} value and
     * places the tiny cache at whatever index actually carries {@code "catalogueBrowseBuckets"},
     * failing loudly if that qualifier is absent or ambiguous.
     */
    private AuthRateLimitFilter filterWithTinyCatalogueBrowseBucket() {
        return filterWithTinyBucketFor("catalogueBrowseBuckets");
    }

    @SuppressWarnings("unchecked")
    private static AuthRateLimitFilter filterWithTinyBucketFor(String qualifier) {
        Constructor<?>[] constructors = AuthRateLimitFilter.class.getConstructors();
        assertThat(constructors)
                .as("AuthRateLimitFilter must expose exactly one public constructor for this "
                        + "qualifier-indexed wiring to be unambiguous")
                .hasSize(1);
        Constructor<AuthRateLimitFilter> ctor = (Constructor<AuthRateLimitFilter>) constructors[0];

        Parameter[] params = ctor.getParameters();
        int target = -1;
        for (int i = 0; i < params.length; i++) {
            Qualifier q = params[i].getAnnotation(Qualifier.class);
            if (q != null && qualifier.equals(q.value())) {
                assertThat(target)
                        .as("@Qualifier(\"%s\") must appear on exactly ONE constructor parameter", qualifier)
                        .isEqualTo(-1);
                target = i;
            }
        }
        assertThat(target)
                .as("no constructor parameter carries @Qualifier(\"%s\") — the bucket this file "
                        + "pins was renamed or removed, so the coverage is gone, not merely moved",
                        qualifier)
                .isNotEqualTo(-1);

        Object[] args = new Object[params.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = i == target ? tinyCatalogueBrowseCache() : permissive();
        }
        try {
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not construct AuthRateLimitFilter", e);
        }
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
    @DisplayName("a burst of salon-catalogue GETs from one IP is throttled with 429 + Retry-After "
            + "once the per-IP budget is spent, and the request never reaches the read")
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
    @DisplayName("the master-catalogue GET draws on the SAME bucket as the salon one — flooding it "
            + "alone still 429s at the same cap")
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
    @DisplayName("traffic under the cap sails through untouched — the non-vacuity pin for the two "
            + "flood tests above")
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
    @DisplayName("one exhausted IP does not spend another IP's budget — the bucket is keyed per "
            + "source address, not globally")
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
     * <b>The Phase 309/310 salon-management read is NOT in this bucket (2026-09-13 cycle-2 audit,
     * B8).</b>
     *
     * <p>Cycle 1 matched {@code GET /api/v1/salons/{salonId}/masters/{masterId}/services} here,
     * against {@code catalogueBrowseBuckets}. That bucket is keyed on the client IP and shared with
     * two {@code permitAll} anonymous reads, so under carrier-grade NAT — the norm on Ukrainian
     * mobile networks — the aggregate anonymous browse traffic leaving one egress IP could exhaust
     * the budget and 429 a salon owner's management UI behind the same IP. The route is
     * AUTHENTICATED, so it belongs on a per-principal bucket; {@code AuthRateLimitFilter} runs
     * BEFORE {@code JwtAuthenticationFilter} and has no principal to key on, so the route moved to
     * {@code BookingRateLimitFilter#salonMasterServicesReadBuckets} (see
     * {@code BookingRateLimitFilterTest}'s B8 cases for its throttle coverage).
     *
     * <p>Exhausting the bucket via the PUBLIC route first is deliberate: it proves the management
     * read draws NOTHING from the anonymous per-IP budget, which a test that merely fired the
     * management path once would not.
     */
    @Test
    @DisplayName("B8: a salon owner's management read is NOT throttled by an anonymous per-IP "
            + "catalogue budget already exhausted from the same (CGNAT) address")
    void should_notThrottle_when_salonManagementReadFollowsAnExhaustedCatalogueBudget() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        String managementPath = "/api/v1/salons/" + UUID.randomUUID() + "/masters/" + UUID.randomUUID()
                + "/services";

        // Exhaust the tiny catalogue-browse bucket for this IP via the ANONYMOUS master-catalogue
        // route — i.e. other subscribers behind the same CGNAT egress address.
        String anonymousPath = masterServicesPath();
        for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY + 5; i++) {
            filter.doFilterInternal(get(anonymousPath, REMOTE_ADDR),
                    new MockHttpServletResponse(), new MockFilterChain());
        }
        var exhausted = new MockHttpServletResponse();
        filter.doFilterInternal(get(anonymousPath, REMOTE_ADDR), exhausted, new MockFilterChain());
        assertThat(exhausted.getStatus())
                .as("arrange check — the anonymous per-IP budget for this address really is spent")
                .isEqualTo(429);

        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilterInternal(get(managementPath, REMOTE_ADDR), response, chain);

        assertThat(response.getStatus())
                .as("B8 — the authenticated management read must not be starved by anonymous "
                        + "browse traffic sharing its egress IP; it is throttled per PRINCIPAL in "
                        + "BookingRateLimitFilter instead")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("the management read must be forwarded down the chain from this filter")
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
    @DisplayName("an unrelated, un-bucketed GET is never throttled however many times it is fired — "
            + "the catalogue-browse match must not have widened into a blanket GET rule")
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
