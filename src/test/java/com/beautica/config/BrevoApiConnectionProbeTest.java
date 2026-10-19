package com.beautica.config;

import com.beautica.notification.mail.BrevoApiProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("BrevoApiConnectionProbe — one GET /v3/account at boot (Phase 374 D9)")
class BrevoApiConnectionProbeTest {

    private static final String API_KEY = "xkeysib-probe-secret-key";
    private static final String PATH = "/v3/account";

    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private BrevoApiConnectionProbe probe(String key) {
        BrevoApiProperties props = new BrevoApiProperties(wireMock.baseUrl(), key, Duration.ofSeconds(3),
                Duration.ofSeconds(10), "Beautica");
        return new BrevoApiConnectionProbe(props,
                new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class));
    }

    private void stub(int status) {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"email\":\"owner@beautica.app\",\"message\":\"key " + API_KEY + "\"}")));
    }

    @Test
    @DisplayName("200 → OK, sends the api-key header, and ONLY GET /v3/account is ever requested (D9a)")
    void should_returnOk_when_200() {
        stub(200);

        BrevoApiConnectionProbe.Result result = probe(API_KEY).result();

        assertThat(result).isEqualTo(BrevoApiConnectionProbe.Result.OK);
        wireMock.verify(1, getRequestedFor(urlEqualTo(PATH)).withHeader("api-key", equalTo(API_KEY)));
        wireMock.verify(1, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("401 / 403 → REJECTED, logs rule prod-brevo-api-auth-failed with the status only, never the key")
    void should_returnRejected_when_401or403(CapturedOutput output) {
        stub(401);
        BrevoApiConnectionProbe.Result r401 = probe(API_KEY).result();
        wireMock.resetAll();
        stub(403);
        BrevoApiConnectionProbe.Result r403 = probe(API_KEY).result();

        assertThat(r401).isEqualTo(BrevoApiConnectionProbe.Result.REJECTED);
        assertThat(r403).isEqualTo(BrevoApiConnectionProbe.Result.REJECTED);
        assertThat(output.getAll()).contains("rule prod-brevo-api-auth-failed").contains("HTTP 401")
                .doesNotContain(API_KEY).doesNotContain("owner@beautica.app");
    }

    @Test
    @DisplayName("503 → UNREACHABLE with rule prod-brevo-api-unreachable")
    void should_returnUnreachable_when_5xx(CapturedOutput output) {
        stub(503);

        assertThat(probe(API_KEY).result()).isEqualTo(BrevoApiConnectionProbe.Result.UNREACHABLE);
        assertThat(output.getAll()).contains("rule prod-brevo-api-unreachable");
    }

    @Test
    @Timeout(10)
    @DisplayName("no answer → UNREACHABLE within about 3 s (probe timeouts, not the 10 s send timeout), class name only")
    void should_returnUnreachableWithinProbeTimeout_when_delayed(CapturedOutput output) {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(6000)));
        long start = System.nanoTime();

        BrevoApiConnectionProbe.Result result = probe(API_KEY).result();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result).isEqualTo(BrevoApiConnectionProbe.Result.UNREACHABLE);
        assertThat(elapsedMs).isLessThan(5000);
        assertThat(output.getAll()).contains("rule prod-brevo-api-unreachable").doesNotContain(API_KEY);
    }

    @Test
    @DisplayName("the result is cached — a second call makes no extra request (100 req/h limit on /v3/account)")
    void should_cacheResult_when_calledTwice() {
        stub(200);
        BrevoApiConnectionProbe probe = probe(API_KEY);

        probe.result();
        BrevoApiConnectionProbe.Result second = probe.result();

        assertThat(second).isEqualTo(BrevoApiConnectionProbe.Result.OK);
        wireMock.verify(1, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("blank key → REJECTED without any network call")
    void should_returnRejectedWithoutCall_when_keyBlank() {
        assertThat(probe("").result()).isEqualTo(BrevoApiConnectionProbe.Result.REJECTED);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }
}
