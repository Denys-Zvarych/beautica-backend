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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * catch. {@link AuthRateLimitFilterTestFactory#withBucket} reads each constructor parameter's
     * {@code @Qualifier} value and
     * places the tiny cache at whatever index actually carries {@code "catalogueBrowseBuckets"},
     * failing loudly if that qualifier is absent or ambiguous.
     */
    private AuthRateLimitFilter filterWithTinyCatalogueBrowseBucket() {
        return filterWithTinyBucketFor("catalogueBrowseBuckets");
    }

    private static AuthRateLimitFilter filterWithTinyBucketFor(String qualifier) {
        return AuthRateLimitFilterTestFactory.withBucket(qualifier, tinyCatalogueBrowseCache());
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

    // ── public profile reads (audit 2026-10-05, finding 2) ─────────────────────────────────

    /** Fires {@code cap + 5} GETs at {@code path} from one IP; returns how many passed before a 429. */
    private static int allowedBeforeThrottle(AuthRateLimitFilter filter, String path, MockHttpServletResponse[] last)
            throws Exception {
        int allowed = 0;
        for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY + 5; i++) {
            last[0] = new MockHttpServletResponse();
            filter.doFilterInternal(get(path, REMOTE_ADDR), last[0], new MockFilterChain());
            if (last[0].getStatus() == 429) {
                break;
            }
            allowed++;
        }
        return allowed;
    }

    @Test
    @DisplayName("GET /masters/{id} — the public master profile (strict bookable verdict) 429s at the "
            + "catalogue-browse per-IP cap")
    void should_return429_when_masterDetailExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        MockHttpServletResponse[] last = new MockHttpServletResponse[1];

        int allowed = allowedBeforeThrottle(filter, "/api/v1/masters/" + UUID.randomUUID(), last);

        assertThat(allowed).isEqualTo(TEST_CATALOGUE_BROWSE_CAPACITY);
        assertThat(last[0].getStatus()).isEqualTo(429);
        assertThat(last[0].getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("GET /salons/{id}, GET /salons/{id}/masters and GET /masters/by-salon/{id} each 429 at "
            + "the same per-IP cap")
    void should_return429_when_salonDetailOrRosterExceedsPerIpCap() throws Exception {
        for (String path : List.of(
                "/api/v1/salons/" + UUID.randomUUID(),
                "/api/v1/salons/" + UUID.randomUUID() + "/masters",
                "/api/v1/masters/by-salon/" + UUID.randomUUID())) {
            AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
            MockHttpServletResponse[] last = new MockHttpServletResponse[1];

            int allowed = allowedBeforeThrottle(filter, path, last);

            assertThat(allowed).as(path).isEqualTo(TEST_CATALOGUE_BROWSE_CAPACITY);
            assertThat(last[0].getStatus()).as(path).isEqualTo(429);
        }
    }

    @Test
    @DisplayName("the profile reads share ONE bucket with the catalogue reads — a mixed profile visit "
            + "spends from the same per-IP budget")
    void should_shareOneBucket_when_profileAndCatalogueReadsMixed() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        UUID masterId = UUID.randomUUID();
        for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY; i++) {
            String path = i % 2 == 0 ? "/api/v1/masters/" + masterId : "/api/v1/masters/" + masterId + "/services";
            filter.doFilterInternal(get(path, REMOTE_ADDR), new MockHttpServletResponse(), new MockFilterChain());
        }

        var response = new MockHttpServletResponse();
        filter.doFilterInternal(get("/api/v1/masters/" + masterId, REMOTE_ADDR), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("B8: authenticated siblings at the same prefixes (/masters/me, /salons/mine, "
            + "/salons/{id}/staff) never draw on the anonymous per-IP bucket")
    void should_notThrottle_when_authenticatedSiblingAtProfilePrefix() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        UUID salonId = UUID.randomUUID();
        for (String path : List.of("/api/v1/masters/me", "/api/v1/salons/mine", "/api/v1/masters/by-salon",
                "/api/v1/masters/%20me", "/api/v1/salons/mine%20/masters",
                "/api/v1/salons/" + salonId + "/staff", "/api/v1/salons/" + salonId + "/masters/effective-schedule")) {
            for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY + 5; i++) {
                var response = new MockHttpServletResponse();
                var chain = new MockFilterChain();

                filter.doFilterInternal(get(path, REMOTE_ADDR), response, chain);

                assertThat(response.getStatus()).as("%s request %d", path, i + 1).isNotEqualTo(429);
                assertThat(chain.getRequest()).as("%s request %d forwarded", path, i + 1).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("B8 regression fix: a Bearer-carrying profile/catalogue GET is NOT charged on the per-IP "
            + "bucket here — it is deferred to BookingRateLimitFilter with the IP key attached")
    void should_deferWithIpKey_when_profileReadCarriesBearerToken() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        String path = "/api/v1/salons/" + UUID.randomUUID();
        MockHttpServletResponse last = null;
        MockHttpServletRequest lastRequest = null;

        for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY + 5; i++) {
            lastRequest = get(path, REMOTE_ADDR);
            lastRequest.addHeader("Authorization", "Bearer any-token");
            last = new MockHttpServletResponse();
            filter.doFilterInternal(lastRequest, last, new MockFilterChain());
        }

        assertThat(last.getStatus()).isNotEqualTo(429);
        assertThat(lastRequest.getAttribute(AuthRateLimitFilter.CATALOGUE_BROWSE_DEFERRED_IP_KEY_ATTRIBUTE))
                .isEqualTo(REMOTE_ADDR);
    }

    @Test
    @DisplayName("an anonymous profile GET is charged here and carries no deferral attribute")
    void should_notSetDeferralAttribute_when_profileReadIsAnonymous() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        MockHttpServletRequest request = get("/api/v1/masters/" + UUID.randomUUID(), REMOTE_ADDR);

        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(AuthRateLimitFilter.CATALOGUE_BROWSE_DEFERRED_IP_KEY_ATTRIBUTE)).isNull();
    }

    // ── re-audit 2026-10-05: non-canonical UUID spellings (MEDIUM) ─────────────────────────

    /** A short, non-canonical spelling {@code UUID.fromString} accepts, distinct per {@code i}. */
    private static String shortUuid(int i) {
        return Integer.toHexString(0xa1b2c00 + i) + "-e4f-1-2-3";
    }

    /**
     * Spring trims the {@code {id}} path variable and {@code UUID.fromString} accepts short forms, so
     * {@code /salons/%20<uuid>}, {@code /masters/<uuid>%20} and {@code /masters/by-salon/a1b2c3d-e4f-1-2-3}
     * all reach the handler. The canonical-only regex let every one of them skip the per-IP charge —
     * an id sweep through any of these spellings must 429 at the same cap as the canonical one.
     */
    @Test
    @DisplayName("re-audit: %20-padded and short UUID spellings of the profile reads are throttled at the "
            + "same per-IP cap — they route to the same handler")
    void should_return429_when_paddedOrShortUuidSpellingSweepsProfiles() throws Exception {
        Map<String, java.util.function.IntFunction<String>> sweeps = new LinkedHashMap<>();
        sweeps.put("%20 prefix", i -> "/api/v1/salons/%20" + UUID.randomUUID());
        sweeps.put("%20 suffix", i -> "/api/v1/masters/" + UUID.randomUUID() + "%20");
        sweeps.put("short form", i -> "/api/v1/masters/by-salon/" + shortUuid(i));
        sweeps.put("short form roster", i -> "/api/v1/salons/" + shortUuid(i) + "/masters");
        sweeps.put("%20 both sides roster", i -> "/api/v1/salons/%20" + UUID.randomUUID() + "%20/masters");

        for (var sweep : sweeps.entrySet()) {
            AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
            int allowed = 0;
            MockHttpServletResponse last = null;
            MockFilterChain lastChain = null;

            for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY + 5; i++) {
                last = new MockHttpServletResponse();
                lastChain = new MockFilterChain();
                filter.doFilterInternal(get(sweep.getValue().apply(i), REMOTE_ADDR), last, lastChain);
                if (last.getStatus() == 429) {
                    break;
                }
                allowed++;
            }

            assertThat(allowed).as(sweep.getKey()).isEqualTo(TEST_CATALOGUE_BROWSE_CAPACITY);
            assertThat(last.getStatus()).as(sweep.getKey()).isEqualTo(429);
            assertThat(lastChain.getRequest()).as(sweep.getKey() + " must not be forwarded").isNull();
        }
    }

    @Test
    @DisplayName("re-audit: the /services prefix+suffix routes stay throttled under padded and short "
            + "spellings — the profile-path change did not narrow them")
    void should_return429_when_paddedOrShortSpellingSweepsCatalogueServices() throws Exception {
        for (String path : List.of(
                "/api/v1/salons/%20" + UUID.randomUUID() + "/services",
                "/api/v1/masters/" + UUID.randomUUID() + "%20/services",
                "/api/v1/salons/" + shortUuid(0) + "/services",
                "/api/v1/masters/" + shortUuid(0) + "/services")) {
            AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
            MockHttpServletResponse[] last = new MockHttpServletResponse[1];

            int allowed = allowedBeforeThrottle(filter, path, last);

            assertThat(allowed).as(path).isEqualTo(TEST_CATALOGUE_BROWSE_CAPACITY);
            assertThat(last[0].getStatus()).as(path).isEqualTo(429);
        }
    }

    // ── re-audit 2026-10-05: lowercase scheme (INFO); the authenticated per-IP ceiling (LOW) is ──
    // ── charged post-JWT in BookingRateLimitFilter — see CatalogueBrowseBearerDeferralTest ──

    @Test
    @DisplayName("re-audit follow-up: a junk-bearer flood from ONE IP — above the 600/min production "
            + "ceiling — is never refused pre-JWT; every request is deferred with the IP key")
    void should_deferEveryRequest_when_junkBearerFloodExceedsProductionCeiling() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        int deferred = 0;
        int refused = 0;
        int volume = 601;

        for (int i = 0; i < volume; i++) {
            var request = get("/api/v1/salons/" + UUID.randomUUID(), REMOTE_ADDR);
            request.addHeader("Authorization", "Bearer x" + i);
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, new MockFilterChain());
            if (response.getStatus() == 429) {
                refused++;
            }
            if (REMOTE_ADDR.equals(
                    request.getAttribute(AuthRateLimitFilter.CATALOGUE_BROWSE_DEFERRED_IP_KEY_ATTRIBUTE))) {
                deferred++;
            }
        }

        assertThat(refused)
                .as("AuthRateLimitFilter runs before JWT validation, so it must charge NO bucket on a "
                        + "bearer request — a pre-JWT ceiling is drainable by forged tokens")
                .isZero();
        assertThat(deferred).isEqualTo(volume);
    }

    @Test
    @DisplayName("re-audit: a lowercase 'bearer' scheme is not a bearer token — it fails closed to the "
            + "anonymous per-IP bucket and is never deferred")
    void should_chargePerIpBucket_when_bearerSchemeIsLowercase() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyCatalogueBrowseBucket();
        int allowed = 0;
        MockHttpServletResponse last = null;
        MockHttpServletRequest lastRequest = null;

        for (int i = 0; i < TEST_CATALOGUE_BROWSE_CAPACITY + 5; i++) {
            lastRequest = get("/api/v1/salons/" + UUID.randomUUID(), REMOTE_ADDR);
            lastRequest.addHeader("Authorization", "bearer any-token-" + i);
            last = new MockHttpServletResponse();
            filter.doFilterInternal(lastRequest, last, new MockFilterChain());
            if (last.getStatus() == 429) {
                break;
            }
            allowed++;
        }

        assertThat(allowed).isEqualTo(TEST_CATALOGUE_BROWSE_CAPACITY);
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(lastRequest.getAttribute(AuthRateLimitFilter.CATALOGUE_BROWSE_DEFERRED_IP_KEY_ATTRIBUTE)).isNull();
    }
}
