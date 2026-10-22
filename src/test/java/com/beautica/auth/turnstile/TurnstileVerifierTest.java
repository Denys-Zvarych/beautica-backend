package com.beautica.auth.turnstile;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.common.exception.TurnstileRejectedException;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TurnstileVerifier} against a WireMock-stubbed siteverify endpoint (phase 382): the request contract,
 * every fail-closed outcome, the no-HTTP-call short circuits and the secret-free logs.
 */
@DisplayName("TurnstileVerifier — WireMock-stubbed siteverify")
class TurnstileVerifierTest {

    private static final String SECRET = "0x4AAAAAAA-test-secret-value-9xQ";
    private static final String TOKEN = "0.tok3n-value-from-the-widget";
    private static final String IP = "203.0.113.7";
    private static final String PATH = "/turnstile/v0/siteverify";
    private static final String SUCCESS = "{\"success\":true,\"hostname\":\"beautica.app\",\"action\":\"otp_send\","
            + "\"challenge_ts\":\"2026-10-22T10:00:00Z\",\"error-codes\":[]}";

    private WireMockServer wireMock;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger verifierLogger;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        verifierLogger = (Logger) LoggerFactory.getLogger(TurnstileVerifier.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        verifierLogger.addAppender(logAppender);
        verifierLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        verifierLogger.detachAppender(logAppender);
        wireMock.stop();
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private TurnstileProperties props(boolean enabled, List<String> hostnames, String action, Duration readTimeout) {
        TurnstileProperties props = new TurnstileProperties();
        props.setEnabled(enabled);
        props.setSecretKey(SECRET);
        props.setSiteverifyUrl(wireMock.baseUrl() + PATH);
        props.setExpectedHostnames(hostnames);
        props.setExpectedAction(action);
        props.setReadTimeout(readTimeout);
        return props;
    }

    private TurnstileVerifier prodLike() {
        return new TurnstileVerifier(RestClient.builder(),
                props(true, List.of("beautica.app", "www.beautica.app"), "otp_send", Duration.ofSeconds(3)));
    }

    private TurnstileVerifier lenient() {
        return new TurnstileVerifier(RestClient.builder(), props(true, List.of(), "", Duration.ofSeconds(3)));
    }

    private void stub(int status, String body) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static void assertRejected(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(TurnstileRejectedException.class)
                .hasMessage("Verification failed");
    }

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).isEqualTo(ServiceUnavailableMessages.TURNSTILE_UNAVAILABLE);
                });
    }

    @Test
    @DisplayName("should_pass_when_siteverifyReportsSuccess")
    void should_pass_when_siteverifyReportsSuccess() {
        stub(200, SUCCESS);

        assertThatCode(() -> prodLike().verify(TOKEN, IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should_postSecretResponseAndRemoteipAsForm_when_verifying")
    void should_postSecretResponseAndRemoteipAsForm_when_verifying() {
        stub(200, SUCCESS);

        prodLike().verify(TOKEN, IP);

        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("secret=" + SECRET))
                .withRequestBody(containing("response=" + TOKEN))
                .withRequestBody(containing("remoteip=" + IP)));
    }

    @Test
    @DisplayName("should_omitRemoteip_when_ipUnresolvable")
    void should_omitRemoteip_when_ipUnresolvable() {
        stub(200, SUCCESS);

        prodLike().verify(TOKEN, null);

        wireMock.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                .notMatching(".*remoteip.*")));
    }

    @Test
    @DisplayName("should_rejectWith400_when_successFalse_invalidInputResponse")
    void should_rejectWith400_when_successFalse_invalidInputResponse() {
        stub(200, "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");

        assertRejected(() -> prodLike().verify(TOKEN, IP));
    }

    @Test
    @DisplayName("should_rejectWith400_when_successFalse_timeoutOrDuplicate")
    void should_rejectWith400_when_successFalse_timeoutOrDuplicate() {
        stub(200, "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");

        assertRejected(() -> prodLike().verify(TOKEN, IP));
    }

    @Test
    @DisplayName("should_rejectWith400_when_hostnameNotExpected")
    void should_rejectWith400_when_hostnameNotExpected() {
        stub(200, "{\"success\":true,\"hostname\":\"evil.example\",\"action\":\"otp_send\"}");

        assertRejected(() -> prodLike().verify(TOKEN, IP));
    }

    @Test
    @DisplayName("should_rejectWith400_when_actionNotExpected")
    void should_rejectWith400_when_actionNotExpected() {
        stub(200, "{\"success\":true,\"hostname\":\"beautica.app\",\"action\":\"login\"}");

        assertRejected(() -> prodLike().verify(TOKEN, IP));
    }

    @Test
    @DisplayName("should_skipHostnameAndActionChecks_when_noneConfigured")
    void should_skipHostnameAndActionChecks_when_noneConfigured() {
        stub(200, "{\"success\":true,\"hostname\":\"example.com\",\"action\":\"test\"}");

        assertThatCode(() -> lenient().verify(TOKEN, IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should_failWith503_when_siteverifyAnswers500")
    void should_failWith503_when_siteverifyAnswers500() {
        stub(500, "{\"error\":\"boom\"}");

        assertUnavailable(() -> prodLike().verify(TOKEN, IP));
    }

    @Test
    @DisplayName("should_failWith503_when_siteverifyAnswers4xx")
    void should_failWith503_when_siteverifyAnswers4xx() {
        stub(403, "{}");

        assertUnavailable(() -> prodLike().verify(TOKEN, IP));
    }

    @Test
    @DisplayName("should_failWith503_when_responseExceedsReadTimeout")
    void should_failWith503_when_responseExceedsReadTimeout() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(SUCCESS).withFixedDelay(1500)));
        TurnstileVerifier slow = new TurnstileVerifier(RestClient.builder(),
                props(true, List.of(), "", Duration.ofMillis(300)));

        assertUnavailable(() -> slow.verify(TOKEN, IP));
    }

    @Test
    @DisplayName("should_failWith503_when_bodyIsMalformedJson")
    void should_failWith503_when_bodyIsMalformedJson() {
        stub(200, "{not json");

        assertUnavailable(() -> prodLike().verify(TOKEN, IP));
    }

    @Test
    @DisplayName("should_failWith503_when_connectionRefused")
    void should_failWith503_when_connectionRefused() {
        int closedPort = freePort();
        TurnstileProperties refused = props(true, List.of(), "", Duration.ofSeconds(1));
        refused.setSiteverifyUrl("http://localhost:" + closedPort + PATH);
        TurnstileVerifier verifier = new TurnstileVerifier(RestClient.builder(), refused);

        assertUnavailable(() -> verifier.verify(TOKEN, IP));
    }

    @Test
    @DisplayName("should_rejectWith400WithoutHttpCall_when_tokenIsBlankNullOrTooLong")
    void should_rejectWith400WithoutHttpCall_when_tokenIsBlankNullOrTooLong() {
        stub(200, SUCCESS);
        TurnstileVerifier verifier = prodLike();

        assertRejected(() -> verifier.verify(null, IP));
        assertRejected(() -> verifier.verify("", IP));
        assertRejected(() -> verifier.verify("   ", IP));
        assertRejected(() -> verifier.verify("a".repeat(TurnstileVerifier.MAX_TOKEN_LENGTH + 1), IP));

        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("should_acceptA2048CharToken_when_atTheLimit")
    void should_acceptA2048CharToken_when_atTheLimit() {
        stub(200, SUCCESS);

        assertThatCode(() -> prodLike().verify("a".repeat(TurnstileVerifier.MAX_TOKEN_LENGTH), IP))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should_makeNoHttpCallAndPass_when_disabled")
    void should_makeNoHttpCallAndPass_when_disabled() {
        TurnstileVerifier disabled = new TurnstileVerifier(RestClient.builder(),
                props(false, List.of(), "", Duration.ofSeconds(3)));

        assertThatCode(() -> disabled.verify(null, IP)).doesNotThrowAnyException();
        assertThatCode(() -> disabled.verify("whatever", null)).doesNotThrowAnyException();

        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("should_logOnlyCloudflareErrorCodes_andNeverTheSecretOrToken")
    void should_logOnlyCloudflareErrorCodes_andNeverTheSecretOrToken() {
        stub(200, "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");
        assertRejected(() -> prodLike().verify(TOKEN, IP));
        wireMock.resetAll();
        stub(500, "{}");
        assertUnavailable(() -> prodLike().verify(TOKEN, IP));

        List<String> lines = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).anyMatch(line -> line.equals("turnstile rejected codes=[invalid-input-response]"));
        assertThat(String.join("\n", lines)).doesNotContain(SECRET).doesNotContain(TOKEN);
        assertThat(logAppender.list).allSatisfy(event ->
                assertThat(event.getThrowableProxy()).as("no stack trace (it could carry the URL)").isNull());
    }
}
