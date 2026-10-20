package com.beautica.auth.filter;

import com.beautica.AbstractIntegrationTest;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * PR #168 final QA — the website-Worker key on {@code GET /book/**}{@code /info} through the REAL Spring
 * Security filter chain (MockMvc), with the key bound from configuration exactly as in production
 * ({@code app.booking-info.worker-key}). {@code GuestInfoGetRateLimitTest} drives the filter by hand; this
 * class proves the Spring constructor receives the bound key and that the chain in front of the filter
 * does not strip or rewrite {@code X-Beautica-Worker-Key} / {@code CF-Connecting-IP}.
 *
 * <p>Bucket assertions read the filter's own {@code guestInfoBuckets} token counts — deterministic, no
 * reliance on a request burst landing inside the 60 s refill window. The one 429 per test is produced by
 * draining the bucket directly, then sending a single request.
 *
 * <p>Every test uses fresh random egress + visitor IPs, so the context-singleton bucket cache never leaks
 * budget between tests. DEBUG logging is switched on per test through {@link LoggingSystem} and restored
 * afterwards (a {@code logging.level.*} property would leak DEBUG into every later context in the JVM).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.booking-info.worker-key=" + BookingInfoWorkerKeySecurityChainIT.WORKER_KEY)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("/book/**/info worker key through the REAL security filter chain — bucket keying + no key in logs")
class BookingInfoWorkerKeySecurityChainIT extends AbstractIntegrationTest {

    /** ≥ 32 chars (boot guard); a distinctive marker so a leak is unambiguous in captured output. */
    static final String WORKER_KEY = "qa168-WORKER-SECRET-7f3c9a1e5b2d4c6e8a0f";

    private static final String WORKER_KEY_HEADER = "X-Beautica-Worker-Key";
    private static final String CF_CONNECTING_IP = "CF-Connecting-IP";
    private static final long INFO_CAPACITY = 60;
    private static final String[] DEBUG_LOGGERS = {
            "com.beautica", "org.springframework.security", "org.springframework.web.servlet.DispatcherServlet"};

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthRateLimitFilter authRateLimitFilter;
    @Autowired private LoggingSystem loggingSystem;

    private final Map<String, LogLevel> savedLevels = new LinkedHashMap<>();
    private String egressIp;
    private String salonInfoPath;
    private String masterInfoPath;

    @BeforeEach
    void setUp() {
        egressIp = randomIp(10, ThreadLocalRandom.current().nextInt(1, 255));
        String tag = Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36);
        salonInfoPath = "/api/v1/book/s/qa-nema-salonu-" + tag + "/info";
        masterInfoPath = "/api/v1/book/qa-nema-maistra-" + tag + "/info";
        for (String name : DEBUG_LOGGERS) {
            LoggerConfiguration config = loggingSystem.getLoggerConfiguration(name);
            savedLevels.put(name, config == null ? null : config.getConfiguredLevel());
            loggingSystem.setLogLevel(name, LogLevel.DEBUG);
        }
    }

    @AfterEach
    void restoreLogLevels() {
        savedLevels.forEach(loggingSystem::setLogLevel);
        savedLevels.clear();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** A random IPv4 literal inside {@code firstOctet.secondOctet.0.0/16}. */
    private static String randomIp(int firstOctet, int secondOctet) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return firstOctet + "." + secondOctet + "." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
    }

    @SuppressWarnings("unchecked")
    private LoadingCache<String, Bucket> infoBuckets() {
        return (LoadingCache<String, Bucket>) ReflectionTestUtils.getField(authRateLimitFilter, "guestInfoBuckets");
    }

    /** Tokens left in {@code key}'s bucket, or {@code null} when the request never created it. */
    private Long tokensOf(String key) {
        Bucket bucket = infoBuckets().getIfPresent(key);
        return bucket == null ? null : bucket.getAvailableTokens();
    }

    private void drain(String key) {
        infoBuckets().get(key).tryConsumeAsMuchAsPossible();
    }

    private MockHttpServletRequestBuilder infoRead(String path, String workerKey, String visitorIp) {
        MockHttpServletRequestBuilder request = get(path).with(r -> {
            r.setRemoteAddr(egressIp);
            return r;
        });
        if (workerKey != null) {
            request.header(WORKER_KEY_HEADER, workerKey);
        }
        if (visitorIp != null) {
            request.header(CF_CONNECTING_IP, visitorIp);
        }
        return request;
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    // ── with the key: per-visitor buckets ────────────────────────────────────────────────────

    @Test
    @DisplayName("valid key + CF-Connecting-IP → the visitor's bucket is charged, the egress IP's bucket is never created")
    void should_chargeVisitorBucket_when_validKeyAndVisitorIp() throws Exception {
        String visitor = randomIp(198, 18);

        MvcResult result = perform(infoRead(salonInfoPath, WORKER_KEY, visitor));

        assertThat(result.getResponse().getStatus()).as("unknown slug reaches the controller").isEqualTo(404);
        assertThat(tokensOf(visitor)).isEqualTo(INFO_CAPACITY - 1);
        assertThat(tokensOf(egressIp)).as("egress bucket untouched").isNull();
    }

    @Test
    @DisplayName("valid key: an exhausted visitor gets 429 while a second visitor behind the same Worker egress IP is served")
    void should_throttleOnlyExhaustedVisitor_when_twoVisitorsShareWorkerEgress() throws Exception {
        String flooder = randomIp(198, 18);
        String neighbour = randomIp(198, 19);
        drain(flooder);

        MvcResult throttled = perform(infoRead(masterInfoPath, WORKER_KEY, flooder));
        MvcResult served = perform(infoRead(salonInfoPath, WORKER_KEY, neighbour));

        assertThat(throttled.getResponse().getStatus()).isEqualTo(429);
        assertThat(throttled.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(served.getResponse().getStatus()).as("neighbour is not pooled with the flooder").isEqualTo(404);
        assertThat(tokensOf(neighbour)).isEqualTo(INFO_CAPACITY - 1);
        assertThat(tokensOf(egressIp)).isNull();
    }

    // ── without (or with a wrong) key: the egress bucket ─────────────────────────────────────

    @Test
    @DisplayName("no key → egress-IP bucket charged; CF-Connecting-IP is ignored (no visitor bucket created)")
    void should_chargeEgressBucket_when_noWorkerKey() throws Exception {
        String spoofedVisitor = randomIp(198, 18);

        MvcResult result = perform(infoRead(salonInfoPath, null, spoofedVisitor));

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(tokensOf(egressIp)).isEqualTo(INFO_CAPACITY - 1);
        assertThat(tokensOf(spoofedVisitor)).as("a forged CF-Connecting-IP never mints a bucket").isNull();
    }

    @Test
    @DisplayName("no key, egress bucket exhausted → 429 whatever CF-Connecting-IP claims (rotation buys nothing)")
    void should_throttleEveryClaimedVisitor_when_noKeyAndEgressExhausted() throws Exception {
        drain(egressIp);

        MvcResult first = perform(infoRead(salonInfoPath, null, randomIp(198, 18)));
        MvcResult second = perform(infoRead(masterInfoPath, null, randomIp(198, 19)));

        assertThat(first.getResponse().getStatus()).isEqualTo(429);
        assertThat(second.getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("wrong key (same length, one char off) → treated as no key: egress bucket charged, visitor bucket absent")
    void should_chargeEgressBucket_when_workerKeyWrong() throws Exception {
        String visitor = randomIp(198, 18);
        String wrongKey = WORKER_KEY.substring(0, WORKER_KEY.length() - 1) + "X";

        perform(infoRead(salonInfoPath, wrongKey, visitor));

        assertThat(tokensOf(egressIp)).isEqualTo(INFO_CAPACITY - 1);
        assertThat(tokensOf(visitor)).isNull();
    }

    @Test
    @DisplayName("valid key on a non-info /book read (availability) → the info buckets are not touched at all")
    void should_leaveInfoBucketsAlone_when_validKeyOnAvailabilityRead() throws Exception {
        String visitor = randomIp(198, 18);

        perform(infoRead("/api/v1/book/qa-nema-maistra/availability?date=2030-01-01", WORKER_KEY, visitor));

        assertThat(tokensOf(visitor)).isNull();
        assertThat(tokensOf(egressIp)).isNull();
    }

    // ── the key is a secret: never logged, never echoed ──────────────────────────────────────

    @Test
    @DisplayName("served, throttled and wrong-key requests at DEBUG → the key never appears in logs or responses")
    void should_neverLogOrEchoWorkerKey_when_requestsCarryIt(CapturedOutput output) throws Exception {
        String visitor = randomIp(198, 18);
        String throttledVisitor = randomIp(198, 19);
        drain(throttledVisitor);
        String wrongKey = WORKER_KEY.substring(0, WORKER_KEY.length() - 4) + "ZZZZ";

        MvcResult served = perform(infoRead(salonInfoPath, WORKER_KEY, visitor));
        MvcResult throttled = perform(infoRead(masterInfoPath, WORKER_KEY, throttledVisitor));
        MvcResult wrong = perform(infoRead(salonInfoPath, wrongKey, visitor));

        assertThat(throttled.getResponse().getStatus()).as("the 429 path ran").isEqualTo(429);
        assertThat(output.getAll())
                .as("non-vacuity: DEBUG request logging was captured for these requests")
                .contains(salonInfoPath.substring(salonInfoPath.indexOf("qa-nema-salonu-")));
        assertThat(output.getAll()).doesNotContain(WORKER_KEY).doesNotContain(wrongKey);
        for (MvcResult result : new MvcResult[]{served, throttled, wrong}) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(WORKER_KEY);
            for (String header : result.getResponse().getHeaderNames()) {
                assertThat(result.getResponse().getHeaders(header).toString())
                        .as("response header %s", header).doesNotContain(WORKER_KEY);
            }
        }
    }
}
