package com.beautica.common.observability;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.web.RequestIdFilter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.sentry.Sentry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 371 D3/D4/D5 end to end: Sentry enabled against a loopback WireMock DSN (allowed by guard rule
 * {@code sentry-local}). An unhandled error carrying canaries in the body, query, Authorization header and
 * exception message must produce exactly one event envelope tagged with environment, release and the
 * response's request ID — and none of the canaries.
 *
 * <p>Own context ({@code sentry.*} properties differ) and dirtied after the class, so the global Sentry hub
 * initialised here never outlives it.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("SentryCaptureIT — scrubbed event reaches the DSN (Phase 371)")
class SentryCaptureIT extends AbstractIntegrationTest {

    private static final String NOTE_CANARY = "NOTE-CANARY-371";
    private static final String NON_REQUEST_BREADCRUMB = "NON-REQUEST-BREADCRUMB-CANARY";
    private static final String PHONE_CANARY = "+380501112233";
    private static final String JWT_CANARY =
            // JWT-shaped fixture split at the dots so secret scanners (gitleaks jwt) do not flag the source; runtime value unchanged.
            "eyJhbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiJjYW5hcnktdXNlciJ9" + "." + "c2lnbmF0dXJlLWNhbmFyeS0zNzE";

    private static final WireMockServer SENTRY_STUB = startSentryStub();

    @Autowired
    private TestRestTemplate restTemplate;

    @Value("${app.internal-api-key}")
    private String internalApiKey;

    private static WireMockServer startSentryStub() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
        return server;
    }

    @DynamicPropertySource
    static void sentryProperties(DynamicPropertyRegistry registry) {
        registry.add("sentry.enabled", () -> "true");
        registry.add("sentry.dsn", () -> "http://k@localhost:" + SENTRY_STUB.port() + "/1");
        registry.add("sentry.send-client-reports", () -> "false");
    }

    @AfterAll
    static void stopSentryStub() {
        Sentry.close();
        SENTRY_STUB.stop();
    }

    private static String bodyOf(LoggedRequest request) {
        byte[] raw = request.getBody();
        boolean gzip = raw.length > 1 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b;
        if (!gzip) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> eventEnvelopes() {
        return SENTRY_STUB.findAll(com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(anyUrl()))
                .stream().map(SentryCaptureIT::bodyOf).filter(b -> b.contains("\"type\":\"event\"")).toList();
    }

    @Test
    @DisplayName("unhandled 500 — one event envelope with environment, release, request_id, the request's http breadcrumb; no canary, no non-request breadcrumb")
    void should_sendOneScrubbedEvent_when_unhandledExceptionOccurs() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", internalApiKey);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + JWT_CANARY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"clientComment\":\"" + NOTE_CANARY + "\"}";
        // Logged OUTSIDE any request (test thread): must never become a breadcrumb on the request's event.
        org.slf4j.LoggerFactory.getLogger("com.beautica.SentryCaptureProbe").info(NON_REQUEST_BREADCRUMB);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/internal/test-only/observability/boom?phone={phone}", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class, PHONE_CANARY);
        Sentry.flush(5_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String requestId = response.getHeaders().getFirst(RequestIdFilter.HEADER);
        assertThat(requestId).isNotBlank();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(eventEnvelopes()).filteredOn(e -> e.contains(requestId)).hasSize(1));
        String envelope = eventEnvelopes().stream().filter(e -> e.contains(requestId)).findFirst().orElseThrow();
        assertThat(envelope)
                .contains("\"environment\":\"local\"")
                .contains("\"release\":\"beautica-backend@")
                .contains("\"request_id\":\"" + requestId + "\"")
                .contains("[phone]")
                .contains("\"category\":\"http\"")
                .doesNotContain(NON_REQUEST_BREADCRUMB)
                .doesNotContain(NOTE_CANARY, PHONE_CANARY, "380501112233", JWT_CANARY, "eyJhbGciOiJIUzI1NiJ9",
                        "phone=", internalApiKey);
    }

    @Test
    @DisplayName("handled 4xx (403 business, 404 no route) — no Sentry event sent (D3: 4xx never burns the error quota)")
    void should_sendNoEvent_when_handledClientErrorOccurs() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", internalApiKey);
        String base = "/api/v1/internal/test-only/observability";

        ResponseEntity<String> forbidden = restTemplate.exchange(
                base + "/forbidden", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        ResponseEntity<String> notFound = restTemplate.exchange(
                base + "/does-not-exist", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        // Control in the same window: a 500 must still arrive, so "no 4xx envelope" is not a dead transport.
        ResponseEntity<String> boom = restTemplate.exchange(
                base + "/boom", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        Sentry.flush(5_000);

        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(boom.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String boomId = boom.getHeaders().getFirst(RequestIdFilter.HEADER);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(eventEnvelopes()).filteredOn(e -> e.contains(boomId)).hasSize(1));
        assertThat(eventEnvelopes())
                .as("no envelope tagged with a 4xx request ID")
                .noneMatch(e -> e.contains(forbidden.getHeaders().getFirst(RequestIdFilter.HEADER)))
                .noneMatch(e -> e.contains(notFound.getHeaders().getFirst(RequestIdFilter.HEADER)));
    }

    @Test
    @DisplayName("options from application.yml — actuator transactions ignored, max 30 breadcrumbs, scrubber wired")
    void should_applyBaseOptions_when_sentryEnabled() {
        io.sentry.SentryOptions options = Sentry.getCurrentScopes().getOptions();

        assertThat(options.getIgnoredTransactions()).extracting(io.sentry.FilterString::getFilterString)
                .contains(".*/actuator(/.*)?");
        assertThat(options.getIgnoredTransactions())
                .anyMatch(f -> f.matches("GET /actuator/health"))
                .noneMatch(f -> f.matches("GET /api/v1/salons"));
        assertThat(options.getMaxBreadcrumbs()).isEqualTo(30);
        assertThat(options.getBeforeSend()).isInstanceOf(SentryPiiScrubber.class);
        assertThat(options.getBeforeBreadcrumb()).isInstanceOf(SentryPiiScrubber.class);
        assertThat(options.getBeforeSendTransaction()).isInstanceOf(SentryPiiScrubber.class);
        assertThat(options.getInAppIncludes()).contains("com.beautica");
    }
}
