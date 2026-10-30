package com.beautica.media.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.config.CloudflareCdnProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 417 — {@link CloudflareCachePurger} against a WireMock-stubbed Cloudflare API: request contract
 * (path, Bearer header, {@code files} body), 100-URL chunking, disabled mode, retry-once on 429/5xx, failure
 * counting, never-throws, the token never reaching the log, redirects never followed, and the circuit breaker.
 */
@DisplayName("CloudflareCachePurger — WireMock-stubbed Cloudflare purge_cache")
class CloudflareCachePurgerTest {

    private static final String TOKEN = "cf-super-secret-purge-token";
    private static final String ZONE = "0123456789abcdef0123456789abcdef";
    private static final String PATH = "/client/v4/zones/" + ZONE + "/purge_cache";
    private static final String URL_A = "https://media.beautica.app/avatars/a.jpg";
    private static final String URL_B = "https://media.beautica.app/avatars/b.jpg";
    private static final String OK_BODY = "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":{\"id\":\"x\"}}";
    private static final String OUTAGE_BODY = "{\"success\":false,\"errors\":[]}";
    private static final Duration SHORT_RETRY = Duration.ofMillis(10);

    private WireMockServer wireMock;
    private SimpleMeterRegistry meters;
    private MutableClock clock;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger purgerLogger;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        meters = new SimpleMeterRegistry();
        clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        purgerLogger = (Logger) LoggerFactory.getLogger(CloudflareCachePurger.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        purgerLogger.addAppender(logAppender);
        purgerLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        purgerLogger.detachAppender(logAppender);
        purgerLogger.setLevel(null);
        wireMock.stop();
    }

    private CloudflareCdnProperties props(String zone, String token, Duration readTimeout) {
        return new CloudflareCdnProperties(zone, token, wireMock.baseUrl() + "/client/v4", true,
                Duration.ofSeconds(2), readTimeout);
    }

    private CloudflareCachePurger purger(String zone, String token) {
        return new CloudflareCachePurger(props(zone, token, Duration.ofSeconds(5)), true, meters,
                RestClient.builder(), SHORT_RETRY, clock);
    }

    private CloudflareCachePurger purger() {
        return purger(ZONE, TOKEN);
    }

    private void stubStatus(int status, String body) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private double counter(String name) {
        return meters.counter(name).count();
    }

    @Test
    @DisplayName("should_postFilesBodyWithBearerToZonePath_when_purging")
    void should_postFilesBodyWithBearerToZonePath_when_purging() {
        stubStatus(200, OK_BODY);

        purger().purgeUrls(List.of(URL_A, URL_B));

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson("{\"files\":[\"" + URL_A + "\",\"" + URL_B + "\"]}")));
        assertThat(counter(CloudflareCachePurger.PURGED_METRIC)).isEqualTo(2.0);
        assertThat(counter(CloudflareCachePurger.FAILED_METRIC)).isZero();
    }

    @Test
    @DisplayName("should_sendThreeRequests_when_250UrlsPurged")
    void should_sendThreeRequests_when_250UrlsPurged() {
        stubStatus(200, OK_BODY);
        List<String> urls = IntStream.range(0, 250).mapToObj(i -> "https://media.beautica.app/k-" + i + ".jpg").toList();

        purger().purgeUrls(urls);

        wireMock.verify(3, postRequestedFor(urlEqualTo(PATH)));
        assertThat(counter(CloudflareCachePurger.PURGED_METRIC)).isEqualTo(250.0);
    }

    @Test
    @DisplayName("should_postEachUrlOnceInFirstSeenOrder_when_inputHasDuplicates")
    void should_postEachUrlOnceInFirstSeenOrder_when_inputHasDuplicates() {
        stubStatus(200, OK_BODY);
        String urlC = "https://media.beautica.app/avatars/c.jpg";

        purger().purgeUrls(List.of(URL_A, URL_B, URL_A, URL_B, urlC));

        // equalToJson is array-order strict by default; Stream.distinct() on a List keeps encounter order.
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(equalToJson("{\"files\":[\"" + URL_A + "\",\"" + URL_B + "\",\"" + urlC + "\"]}")));
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(counter(CloudflareCachePurger.PURGED_METRIC)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("should_sendNoRequest_when_inputEmpty")
    void should_sendNoRequest_when_inputEmpty() {
        stubStatus(200, OK_BODY);

        purger().purgeUrls(List.of());

        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("should_sendNoRequestAndNotThrow_when_tokenOrZoneBlank")
    void should_sendNoRequestAndNotThrow_when_tokenOrZoneBlank() {
        stubStatus(200, OK_BODY);
        CloudflareCachePurger blankToken = purger(ZONE, "");
        CloudflareCachePurger blankZone = purger("  ", TOKEN);

        assertThatCode(() -> {
            blankToken.purgeUrls(List.of(URL_A));
            blankZone.purgeUrls(List.of(URL_A));
        }).doesNotThrowAnyException();

        assertThat(blankToken.isEnabled()).isFalse();
        assertThat(blankZone.isEnabled()).isFalse();
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("should_warnAtStartup_when_purgeDisabledWhileR2Enabled")
    void should_warnAtStartup_when_purgeDisabledWhileR2Enabled() {
        new CloudflareCachePurger(props("", "", Duration.ofSeconds(5)), true, meters, RestClient.builder(), SHORT_RETRY, clock);

        assertThat(logAppender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("up to 7 d");
        });
    }

    @Test
    @DisplayName("should_retryOnceThenCountFailure_when_cloudflareReturns500")
    void should_retryOnceThenCountFailure_when_cloudflareReturns500() {
        stubStatus(500, "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"boom\"}]}");

        assertThatCode(() -> purger().purgeUrls(List.of(URL_A, URL_B))).doesNotThrowAnyException();

        wireMock.verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertThat(counter(CloudflareCachePurger.FAILED_METRIC)).isEqualTo(2.0);
        assertThat(counter(CloudflareCachePurger.PURGED_METRIC)).isZero();
        assertThat(logAppender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("urlCount=2").contains("errorCode=10000")
                    .doesNotContain(URL_A).doesNotContain("avatars/");
        });
    }

    @Test
    @DisplayName("should_retryOnceThenCountFailure_when_cloudflareReturns429")
    void should_retryOnceThenCountFailure_when_cloudflareReturns429() {
        stubStatus(429, "{\"success\":false,\"errors\":[{\"code\":971,\"message\":\"throttled\"}]}");

        assertThatCode(() -> purger().purgeUrls(List.of(URL_A))).doesNotThrowAnyException();

        wireMock.verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertThat(counter(CloudflareCachePurger.FAILED_METRIC)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("should_countSuccess_when_firstAttempt500AndRetrySucceeds")
    void should_countSuccess_when_firstAttempt500AndRetrySucceeds() {
        wireMock.stubFor(post(urlEqualTo(PATH)).inScenario("retry").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        wireMock.stubFor(post(urlEqualTo(PATH)).inScenario("retry").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(OK_BODY)));

        purger().purgeUrls(List.of(URL_A));

        wireMock.verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertThat(counter(CloudflareCachePurger.PURGED_METRIC)).isEqualTo(1.0);
        assertThat(counter(CloudflareCachePurger.FAILED_METRIC)).isZero();
    }

    @Test
    @Timeout(10)
    @DisplayName("should_notThrowAndCountFailure_when_readTimesOut")
    void should_notThrowAndCountFailure_when_readTimesOut() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(OK_BODY).withFixedDelay(2000)));
        CloudflareCachePurger shortRead = new CloudflareCachePurger(props(ZONE, TOKEN, Duration.ofMillis(200)), true,
                meters, RestClient.builder(), SHORT_RETRY, clock);

        assertThatCode(() -> shortRead.purgeUrls(List.of(URL_A))).doesNotThrowAnyException();

        assertThat(counter(CloudflareCachePurger.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(counter(CloudflareCachePurger.PURGED_METRIC)).isZero();
    }

    @Test
    @DisplayName("should_countFailureWithoutRetry_when_200ButSuccessFalse")
    void should_countFailureWithoutRetry_when_200ButSuccessFalse() {
        stubStatus(200, "{\"success\":false,\"errors\":[{\"code\":1012,\"message\":\"bad file\"}]}");

        purger().purgeUrls(List.of(URL_A));

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(counter(CloudflareCachePurger.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(counter(CloudflareCachePurger.PURGED_METRIC)).isZero();
    }

    @Test
    @DisplayName("should_neverLogToken_when_purgeSucceedsFailsOrIsDisabled")
    void should_neverLogToken_when_purgeSucceedsFailsOrIsDisabled() {
        stubStatus(403, "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Authentication error\"}]}");

        purger().purgeUrls(List.of(URL_A));
        purger(ZONE, "").purgeUrls(List.of(URL_A));
        stubStatus(200, OK_BODY);
        purger().purgeUrls(List.of(URL_B));

        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(TOKEN));
        assertThat(props(ZONE, TOKEN, Duration.ofSeconds(5)).toString()).doesNotContain(TOKEN);
    }
    @Test
    @DisplayName("should_notFollowRedirect_when_cloudflareReturns302")
    void should_notFollowRedirect_when_cloudflareReturns302() {
        String elsewhere = "/elsewhere/purge_cache";
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(302)
                .withHeader("Location", wireMock.baseUrl() + elsewhere)));
        wireMock.stubFor(post(urlEqualTo(elsewhere)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(OK_BODY)));
        wireMock.stubFor(get(urlEqualTo(elsewhere)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(OK_BODY)));

        purger().purgeUrls(List.of(URL_A));

        wireMock.verify(0, anyRequestedFor(urlEqualTo(elsewhere)));
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(counter(CloudflareCachePurger.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(counter(CloudflareCachePurger.PURGED_METRIC)).isZero();
    }

    @Test
    @DisplayName("should_skipHttpAndCountFailure_when_threeConsecutiveOutageFailures")
    void should_skipHttpAndCountFailure_when_threeConsecutiveOutageFailures() {
        stubStatus(503, OUTAGE_BODY);
        CloudflareCachePurger purger = purger();
        tripBreaker(purger);
        wireMock.resetRequests();
        logAppender.list.clear();

        purger.purgeUrls(List.of(URL_A, URL_B));
        purger.purgeUrls(List.of(URL_A));

        wireMock.verify(0, anyRequestedFor(anyUrl()));
        assertThat(counter(CloudflareCachePurger.FAILED_METRIC)).isEqualTo(3.0 + 3.0);
        assertThat(logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("circuit open")))
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).doesNotContain(URL_A).doesNotContain(URL_B)
                        .doesNotContain("avatars/").doesNotContain(TOKEN));
    }

    @Test
    @DisplayName("should_retryHttp_when_openWindowElapsedOnInjectedClock")
    void should_retryHttp_when_openWindowElapsedOnInjectedClock() {
        stubStatus(503, OUTAGE_BODY);
        CloudflareCachePurger purger = purger();
        tripBreaker(purger);
        stubStatus(200, OK_BODY);
        wireMock.resetRequests();
        clock.advance(PurgeCircuitBreaker.OPEN_DURATION);

        purger.purgeUrls(List.of(URL_A));

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(counter(CloudflareCachePurger.PURGED_METRIC)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("should_sendOneTrialPostAndReopen_when_trialAfterOpenWindowFails")
    void should_sendOneTrialPostAndReopen_when_trialAfterOpenWindowFails() {
        stubStatus(503, OUTAGE_BODY);
        CloudflareCachePurger purger = purger();
        tripBreaker(purger);
        purger.purgeUrls(List.of(URL_B));
        wireMock.resetRequests();
        clock.advance(PurgeCircuitBreaker.OPEN_DURATION);
        List<String> urls = IntStream.range(0, 150).mapToObj(i -> "https://media.beautica.app/k-" + i + ".jpg").toList();

        purger.purgeUrls(urls);

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(containing("k-0.jpg")).withRequestBody(containing("k-99.jpg")));
        wireMock.verify(1, anyRequestedFor(anyUrl()));
        assertThat(counter(CloudflareCachePurger.FAILED_METRIC)).isEqualTo(3.0 + 1.0 + 100.0 + 50.0);
        assertThat(counter(CloudflareCachePurger.PURGED_METRIC)).isZero();
        assertThat(logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("circuit open")))
                .hasSize(2)
                .allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("k-").doesNotContain(TOKEN));
    }

    @Test
    @DisplayName("should_keepCircuitClosed_when_successResetsFailureCount")
    void should_keepCircuitClosed_when_successResetsFailureCount() {
        CloudflareCachePurger purger = purger();
        stubStatus(503, OUTAGE_BODY);
        purger.purgeUrls(List.of(URL_A));
        purger.purgeUrls(List.of(URL_A));
        stubStatus(200, OK_BODY);
        purger.purgeUrls(List.of(URL_A));
        stubStatus(503, OUTAGE_BODY);
        purger.purgeUrls(List.of(URL_A));
        purger.purgeUrls(List.of(URL_A));
        stubStatus(200, OK_BODY);
        wireMock.resetRequests();

        purger.purgeUrls(List.of(URL_B));

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(counter(CloudflareCachePurger.PURGED_METRIC)).isEqualTo(2.0);
    }

    /** {@value PurgeCircuitBreaker#FAILURE_THRESHOLD} single-URL chunks, each failing twice (attempt + retry). */
    private void tripBreaker(CloudflareCachePurger purger) {
        for (int i = 0; i < PurgeCircuitBreaker.FAILURE_THRESHOLD; i++) {
            purger.purgeUrls(List.of(URL_A));
        }
        wireMock.verify(2 * PurgeCircuitBreaker.FAILURE_THRESHOLD, postRequestedFor(urlEqualTo(PATH)));
    }

    /** Test clock the breaker reads; advanced explicitly, never slept on. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
