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
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

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

    private String renderLogs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }
}
