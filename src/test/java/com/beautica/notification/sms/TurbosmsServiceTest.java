package com.beautica.notification.sms;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.config.TurbosmsProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TurbosmsService} with the Turbosms HTTP endpoint stubbed via
 * WireMock. Covers the request contract (body + Bearer header), failure mapping,
 * and the security-critical logging discipline (no token, no OTP text in logs).
 */
@DisplayName("TurbosmsService — WireMock-stubbed Turbosms endpoint")
class TurbosmsServiceTest {

    private static final String TOKEN = "super-secret-token-value";
    private static final String OTP_TEXT = "Your Beautica code is 482913";
    private static final String PHONE = "+380671234567";
    private static final String PATH = "/message/send.json";

    private WireMockServer wireMock;
    private TurbosmsService service;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();

        TurbosmsProperties props = new TurbosmsProperties();
        props.setToken(TOKEN);
        props.setSenderName("Beautica");
        props.setBaseUrl(wireMock.baseUrl() + PATH);
        service = new TurbosmsService(RestClient.builder(), props);

        serviceLogger = (Logger) LoggerFactory.getLogger(TurbosmsService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
        serviceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
        wireMock.stop();
    }

    @Test
    @DisplayName("should_postCorrectBodyAndBearerHeader_when_sending")
    void should_postCorrectBodyAndBearerHeader_when_sending() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"response_status\":\"OK\"}")));

        service.send(PHONE, OTP_TEXT);

        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson("""
                        { "recipients": ["+380671234567"],
                          "sms": { "sender": "Beautica", "text": "Your Beautica code is 482913" } }
                        """)));
    }

    @Test
    @DisplayName("should_throwSmsDeliveryException_when_responseIs4xx")
    void should_throwSmsDeliveryException_when_responseIs4xx() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"unauthorized\"}")));

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class);
    }

    @Test
    @DisplayName("should_throwSmsDeliveryException_when_responseIs5xx")
    void should_throwSmsDeliveryException_when_responseIs5xx() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class);
    }

    @Test
    @DisplayName("should_throwWithoutCallingProvider_when_tokenIsBlank")
    void should_throwWithoutCallingProvider_when_tokenIsBlank() {
        TurbosmsProperties blank = new TurbosmsProperties();
        blank.setToken("");
        blank.setBaseUrl(wireMock.baseUrl() + PATH);
        TurbosmsService unconfigured = new TurbosmsService(RestClient.builder(), blank);

        assertThatThrownBy(() -> unconfigured.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class);

        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    // ── outbound timeouts ───────────────────────────────────────────────────

    @Test
    @Timeout(5)
    @DisplayName("should_failWithinReadTimeout_when_turbosmsDelaysResponse")
    void should_failWithinReadTimeout_when_turbosmsDelaysResponse() {
        TurbosmsProperties props = new TurbosmsProperties();
        props.setToken(TOKEN);
        props.setBaseUrl(wireMock.baseUrl() + PATH);
        props.setReadTimeout(Duration.ofMillis(300));
        TurbosmsService shortTimeout = new TurbosmsService(RestClient.builder(), props);
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000)
                        .withBody("{\"response_status\":\"OK\"}")));

        long start = System.nanoTime();
        assertThatThrownBy(() -> shortTimeout.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    @Timeout(5)
    @DisplayName("read timeout after the request was written → AMBIGUOUS outcome (the provider may have sent it)")
    void should_throwAmbiguous_when_turbosmsReadTimesOut() {
        TurbosmsProperties props = new TurbosmsProperties();
        props.setToken(TOKEN);
        props.setBaseUrl(wireMock.baseUrl() + PATH);
        props.setReadTimeout(Duration.ofMillis(300));
        TurbosmsService shortTimeout = new TurbosmsService(RestClient.builder(), props);
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000)
                        .withBody("{\"response_status\":\"OK\"}")));

        assertThatThrownBy(() -> shortTimeout.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryAmbiguousException.class);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("an HTTP error status is a DEFINITE refusal, not an ambiguous outcome")
    void should_throwDefiniteFailure_when_responseIs5xx() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .isNotInstanceOf(SmsDeliveryAmbiguousException.class);
    }

    @Test
    @Timeout(10)
    @DisplayName("connection refused (request never sent) is a DEFINITE failure, not an ambiguous outcome")
    void should_throwDefiniteFailure_when_connectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        TurbosmsProperties props = new TurbosmsProperties();
        props.setToken(TOKEN);
        props.setBaseUrl("http://127.0.0.1:" + closedPort + PATH);
        TurbosmsService refused = new TurbosmsService(RestClient.builder(), props);

        assertThatThrownBy(() -> refused.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .isNotInstanceOf(SmsDeliveryAmbiguousException.class);
    }

    @Test
    @DisplayName("should_failFast_when_turbosmsTimeoutIsNullZeroOrNegative")
    void should_failFast_when_turbosmsTimeoutIsNullZeroOrNegative() {
        for (Duration bad : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            TurbosmsProperties connect = new TurbosmsProperties();
            connect.setConnectTimeout(bad);
            TurbosmsProperties read = new TurbosmsProperties();
            read.setReadTimeout(bad);

            assertThatThrownBy(() -> new TurbosmsService(RestClient.builder(), connect))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("connect-timeout");
            assertThatThrownBy(() -> new TurbosmsService(RestClient.builder(), read))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("read-timeout");
        }
    }

    @Test
    @DisplayName("should_defaultToThreeAndFiveSeconds_when_propertiesUnset")
    void should_defaultToThreeAndFiveSeconds_when_propertiesUnset() {
        TurbosmsProperties props = new TurbosmsProperties();

        assertThat(props.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    // ── logging discipline (security-critical) ──────────────────────────────

    @Test
    @DisplayName("should_neverLogTokenOrOtpText_andMaskPhone_when_sendingSucceeds")
    void should_neverLogTokenOrOtpText_andMaskPhone_when_sendingSucceeds() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody("{\"response_status\":\"OK\"}")));

        service.send(PHONE, OTP_TEXT);

        String allLogs = renderLogs();
        assertThat(allLogs).doesNotContain(TOKEN);
        assertThat(allLogs).doesNotContain(OTP_TEXT);
        assertThat(allLogs).doesNotContain("482913");
        assertThat(allLogs).doesNotContain(PHONE);
        // Masked recipient: last 4 digits only.
        assertThat(allLogs).contains("+380***4567");
        assertThat(allLogs).contains("status=OK");
    }

    @Test
    @DisplayName("should_neverLogTokenOrOtpText_when_sendFails")
    void should_neverLogTokenOrOtpText_when_sendFails() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class);

        String allLogs = renderLogs();
        assertThat(allLogs).doesNotContain(TOKEN);
        assertThat(allLogs).doesNotContain(OTP_TEXT);
        assertThat(allLogs).doesNotContain("482913");
        assertThat(allLogs).doesNotContain(PHONE);
        assertThat(allLogs).contains("+380***4567");
    }

    @Test
    @Timeout(5)
    @DisplayName("isOutcomeUnknown — a cyclic cause chain (A -> B -> A) terminates and reads as UNKNOWN")
    void should_terminateAsUnknown_when_causeChainIsCyclic() {
        java.io.IOException first = new java.io.IOException("first");
        java.io.IOException second = new java.io.IOException("second", first);
        first.initCause(second);
        ResourceAccessException e = new ResourceAccessException("I/O error", second);

        boolean unknown = TurbosmsService.isOutcomeUnknown(e);

        assertThat(unknown).isTrue();
    }

    @Test
    @DisplayName("isOutcomeUnknown — a pre-send cause inside the depth cap is definite; one past it is UNKNOWN")
    void should_honourDepthCap_when_preSendCauseIsDeep() {
        ResourceAccessException shallow = new ResourceAccessException("I/O error",
                wrap(new java.net.ConnectException("refused"), TurbosmsService.MAX_CAUSE_DEPTH - 2));
        ResourceAccessException deep = new ResourceAccessException("I/O error",
                wrap(new java.net.ConnectException("refused"), TurbosmsService.MAX_CAUSE_DEPTH));

        boolean shallowUnknown = TurbosmsService.isOutcomeUnknown(shallow);
        boolean deepUnknown = TurbosmsService.isOutcomeUnknown(deep);

        assertThat(shallowUnknown).as("ConnectException at depth %s", TurbosmsService.MAX_CAUSE_DEPTH - 1).isFalse();
        assertThat(deepUnknown).as("ConnectException past the cap").isTrue();
    }

    /** {@code root} under {@code layers} IOException wrappers. */
    private static java.io.IOException wrap(java.io.IOException root, int layers) {
        java.io.IOException t = root;
        for (int i = 0; i < layers; i++) {
            t = new java.io.IOException("layer " + i, t);
        }
        return t;
    }

    private String renderLogs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }
}
