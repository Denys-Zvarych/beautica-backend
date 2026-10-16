package com.beautica.common.web;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 371 D5 end to end: {@code X-Request-Id} on every status class, the same ID in the error body and on
 * a log line written from a real pooled {@code @Async}-style executor thread.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("RequestIdIT — X-Request-Id header, error body and async MDC (Phase 371)")
class RequestIdIT extends AbstractIntegrationTest {

    private static final String BASE = ObservabilityProbeTestController.BASE;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.internal-api-key}")
    private String internalApiKey;

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", internalApiKey);
        return headers;
    }

    private static String requestIdOf(ResponseEntity<String> response) {
        String id = response.getHeaders().getFirst(RequestIdFilter.HEADER);
        assertThat(id).as("X-Request-Id header").isNotBlank();
        return id;
    }

    private void assertBodyCarries(ResponseEntity<String> response, String id) throws Exception {
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("requestId").asText()).isEqualTo(id);
    }

    @Test
    @DisplayName("200 — header present, generated UUID; success body has no requestId key")
    void should_returnHeader_when_200() throws Exception {
        ResponseEntity<String> response = get(BASE + "/ok", internalHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(UUID.fromString(requestIdOf(response))).isNotNull();
        assertThat(objectMapper.readTree(response.getBody()).has("requestId")).isFalse();
    }

    @Test
    @DisplayName("valid inbound X-Request-Id — echoed on the response")
    void should_echoInboundId_when_valid() {
        HttpHeaders headers = internalHeaders();
        headers.set(RequestIdFilter.HEADER, "mobile-req-0001");

        ResponseEntity<String> response = get(BASE + "/ok", headers);

        assertThat(response.getHeaders().getFirst(RequestIdFilter.HEADER)).isEqualTo("mobile-req-0001");
    }

    @Test
    @DisplayName("401 from the security entry point (no token) — header present; existing empty body unchanged")
    void should_returnHeader_when_401FromSecurity() {
        ResponseEntity<String> response = get("/api/v1/users/me", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(UUID.fromString(requestIdOf(response))).isNotNull();
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("401 from InternalApiKeyFilter (ApiResponse) — header and body requestId match")
    void should_returnHeaderAndBodyId_when_401FromInternalKeyFilter() throws Exception {
        ResponseEntity<String> response = get(BASE + "/ok", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertBodyCarries(response, requestIdOf(response));
    }

    @Test
    @DisplayName("403 — header and ApiResponse requestId match")
    void should_returnHeaderAndBodyId_when_403() throws Exception {
        ResponseEntity<String> response = get(BASE + "/forbidden", internalHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertBodyCarries(response, requestIdOf(response));
    }

    @Test
    @DisplayName("400 bean-validation failure — header and validationError body requestId match, errors still listed")
    void should_returnHeaderAndBodyId_when_400Validation() throws Exception {
        HttpHeaders headers = internalHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(BASE + "/validated", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(java.util.Map.of("name", "")), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertBodyCarries(response, requestIdOf(response));
        assertThat(objectMapper.readTree(response.getBody()).path("errors").has("name"))
                .as("validationError body keeps its field errors, body=%s", response.getBody()).isTrue();
    }

    @Test
    @DisplayName("invalid inbound X-Request-Id over real HTTP — not echoed; a UUID is generated and used in the error body")
    void should_replaceInboundId_when_invalidOverHttp() throws Exception {
        HttpHeaders headers = internalHeaders();
        headers.set(RequestIdFilter.HEADER, "bad id!");

        ResponseEntity<String> response = get(BASE + "/forbidden", headers);

        String id = requestIdOf(response);
        assertThat(id).isNotEqualTo("bad id!");
        assertThat(UUID.fromString(id)).isNotNull();
        assertBodyCarries(response, id);
    }

    @Test
    @DisplayName("404 — header and ApiResponse requestId match")
    void should_returnHeaderAndBodyId_when_404() throws Exception {
        ResponseEntity<String> response = get(BASE + "/does-not-exist", internalHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertBodyCarries(response, requestIdOf(response));
    }

    @Test
    @DisplayName("500 — header and ApiResponse requestId match; ERROR log line carries the same ID")
    void should_returnHeaderAndBodyId_when_500(CapturedOutput output) throws Exception {
        ResponseEntity<String> response = get(BASE + "/boom", internalHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String id = requestIdOf(response);
        assertBodyCarries(response, id);
        assertThat(output.getAll()).containsPattern("ERROR .*\\[" + id + "\\] .*Unhandled exception");
    }

    @Test
    @DisplayName("@Async pool thread — log line carries the submitting request's ID")
    void should_carryRequestId_when_loggingFromAsyncPool(CapturedOutput output) {
        HttpHeaders headers = internalHeaders();
        headers.set(RequestIdFilter.HEADER, "async-probe-0001");

        ResponseEntity<String> response = get(BASE + "/async", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(output.getAll())
                .containsPattern("\\[push-[^\\]]*\\] \\[async-probe-0001\\] .*" + ObservabilityProbeTestController.ASYNC_LOG_LINE);
    }

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    @DisplayName("test profile (no sentry.dsn) — no Sentry auto-configuration, scrubber or options beans load")
    void should_loadNoSentryBeans_when_dsnPropertyAbsent() {
        assertThat(context.getBeanNamesForType(io.sentry.IScopes.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.beautica.common.observability.SentryPiiScrubber.class)).isEmpty();
        assertThat(context.containsBean("beauticaSentryOptions")).isFalse();
    }
}
