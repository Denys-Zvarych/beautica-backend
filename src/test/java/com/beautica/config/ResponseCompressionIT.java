package com.beautica.config;

import com.beautica.AbstractIntegrationTest;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@code server.compression} (application.yml) as observable behaviour rather than an
 * inert config block: a mis-typed key or a {@code min-response-size} above the real payload
 * size would leave the whole setting silently doing nothing, and no other test would notice.
 *
 * <p><b>Why a bespoke HTTP client.</b> Every other integration test drives the app through
 * {@link org.springframework.boot.test.web.client.TestRestTemplate} over Apache HttpClient 5's
 * default exec chain, which includes {@code ContentCompressionExec} — it negotiates
 * {@code Accept-Encoding: gzip} and transparently decodes, then STRIPS the
 * {@code Content-Encoding} response header. That transparency is exactly what makes enabling
 * compression safe for the rest of the suite, and exactly what makes it unassertable there. So
 * this class builds one client with {@code disableContentCompression()} and sets the request
 * header by hand, leaving the raw wire bytes and headers intact.
 *
 * <p>{@code GET /api/v1/locations/oblasts} is the probe: {@code permitAll} (no auth fixture
 * needed), served from Flyway-seeded reference data (no {@code @BeforeEach} setup, unaffected
 * by {@code cleanDb()}), and its Ukrainian oblast names put it well above the 1 KB threshold.
 */
@DisplayName("server.compression — integration")
class ResponseCompressionIT extends AbstractIntegrationTest {

    private static final String PROBE_PATH = "/api/v1/locations/oblasts";
    private static final int GZIP_MAGIC_BYTE_0 = 0x1f;
    private static final int GZIP_MAGIC_BYTE_1 = 0x8b;
    /** server.compression.min-response-size in application.yml. */
    private static final int MIN_RESPONSE_SIZE_BYTES = 1024;

    /** Static per §M4 — one connection pool for the class, not one per test method. */
    private static CloseableHttpClient httpClient;
    private static RestTemplate rawRestTemplate;

    @LocalServerPort
    private int port;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @BeforeAll
    static void buildRawClient() {
        httpClient = HttpClients.custom().disableContentCompression().build();
        rawRestTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
        // Never throw on a 4xx: one probe below deliberately requests a missing resource to get a
        // short body, and the headers of that response are the thing under test.
        rawRestTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    @AfterAll
    static void closeRawClient() throws IOException {
        httpClient.close();
    }

    @Test
    @DisplayName("binds the configured compression properties")
    void should_bindCompressionProperties_when_contextStarts() {
        assertThat(environment.getProperty("server.compression.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("server.compression.min-response-size")).isEqualTo("1KB");
        assertThat(environment.getProperty("server.compression.mime-types"))
                .contains("application/json");
    }

    @Test
    @DisplayName("gzips a JSON response above the threshold when the client accepts gzip")
    void should_gzipResponse_when_clientAcceptsGzip() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip");

        ResponseEntity<byte[]> response = rawRestTemplate.exchange(
                url(PROBE_PATH), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        // Raw gzip member header — proves the bytes on the wire really are compressed and
        // not merely labelled as such.
        assertThat(body[0] & 0xff).isEqualTo(GZIP_MAGIC_BYTE_0);
        assertThat(body[1] & 0xff).isEqualTo(GZIP_MAGIC_BYTE_1);
    }

    @Test
    @DisplayName("leaves the response uncompressed when the client does not accept gzip")
    void should_notCompressResponse_when_clientDoesNotAcceptGzip() {
        ResponseEntity<byte[]> response = rawRestTemplate.exchange(
                url(PROBE_PATH), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), byte[].class);

        // Compression is negotiated, never forced — a client that cannot decode gzip must
        // still get plain JSON.
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isNull();
        assertThat(response.getBody()).isNotNull();
        // Guards the test itself: if the probe payload ever shrank below the configured
        // threshold, the gzip assertion above would start passing vacuously.
        assertThat(response.getBody().length)
                .as("probe payload must exceed server.compression.min-response-size")
                .isGreaterThan(MIN_RESPONSE_SIZE_BYTES);
    }

    /**
     * <b>{@code min-response-size} does not gate anything in this application.</b> Measured, not
     * inferred: this probe is a ~70-byte 404 body and it comes back gzipped.
     *
     * <p>Tomcat's {@code CompressionConfig.useCompression} compresses when
     * {@code contentLength == -1 || contentLength > compressionMinSize}. Spring MVC's Jackson
     * converter streams the body without setting {@code Content-Length}, so every JSON response
     * this app produces goes out {@code Transfer-Encoding: chunked} with {@code contentLength ==
     * -1} — the first arm of that OR short-circuits and the configured minimum is never consulted.
     * The threshold would only bite on a handler that sets {@code Content-Length} explicitly, and
     * there is none.
     *
     * <p>Two beliefs elsewhere rest on the opposite assumption, and both are false:
     * <ul>
     *   <li>application.yml's comment that a sub-threshold body "would miss the default entirely"
     *       and that going below 1KB risks gzip "growing a small body" — that growth is happening
     *       NOW, at every size, and the threshold is not what prevents it;</li>
     *   <li>the {@code backend-security} note that {@code AuthResponse}-family bodies are ~570
     *       bytes and therefore "never compressed". Measured at the same time as this test:
     *       {@code POST /api/v1/auth/login} returns 884 plain bytes and IS gzipped to 624 on the
     *       wire. Bearer-token responses are compressed today; the size argument never applied.</li>
     * </ul>
     *
     * <p>Kept as a characterization test rather than folded into the assertions above: the
     * inertness of the threshold is the surprising fact, and pinning it is what stops the next
     * reader re-deriving the same wrong conclusion from the config value. If {@code /auth/**} is
     * later excluded from compression, or handlers start setting {@code Content-Length}, this test
     * is the one that will notice.
     */
    @Test
    @DisplayName("gzips a JSON body far under min-response-size, because chunked responses bypass the threshold entirely")
    void should_gzipSubThresholdBody_when_responseIsChunked() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip");

        // permitAll GET on a salon id that does not exist -> a short JSON error envelope.
        ResponseEntity<byte[]> response = rawRestTemplate.exchange(
                url("/api/v1/salons/" + UUID.randomUUID()), HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class);

        assertThat(response.getHeaders().getFirst("Transfer-Encoding"))
                .as("the mechanism under test: no Content-Length means Tomcat never consults "
                        + "server.compression.min-response-size")
                .isEqualTo("chunked");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(-1);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(response.getBody().length)
                .as("probe must be well under the %d-byte configured minimum, or it proves nothing",
                        MIN_RESPONSE_SIZE_BYTES)
                .isLessThan(MIN_RESPONSE_SIZE_BYTES / 2);
    }

    /**
     * Pins the BREACH-hardening scope decision: {@code text/html} was REMOVED from
     * {@code server.compression.mime-types} (formerly
     * {@code application/json,application/problem+json,text/html,text/plain}). The Thymeleaf
     * review pages co-locate a 32-byte review token with an attacker-chosen {@code displayName}
     * in one compressible body — a textbook BREACH oracle — so they must ship uncompressed.
     *
     * <p>The probe is the permitAll token-review page hit with a well-formed but unknown token:
     * {@code loadForReview} returns an invalid view and the handler renders the {@code text/html}
     * neutral-status page — no auth fixture, no DB row, unaffected by {@code cleanDb()}. Because
     * the threshold is inert for chunked bodies (see the test above), size is irrelevant here: the
     * ONLY thing keeping {@code Content-Encoding} off this response is the mime-types narrowing.
     * Re-widen {@code mime-types} to include {@code text/html} and this test flips red — which is
     * the point.
     */
    @Test
    @DisplayName("does NOT gzip a text/html body — text/html is excluded from mime-types (BREACH scope)")
    void should_notCompressTextHtml_when_mimeTypesExcludesHtml() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip");

        ResponseEntity<byte[]> response = rawRestTemplate.exchange(
                url("/api/v1/service-categories/requests/review/unknown-review-token-probe"),
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

        assertThat(response.getHeaders().getContentType())
                .as("probe must actually be a text/html page for this pin to mean anything")
                .isNotNull()
                .matches(ct -> ct.isCompatibleWith(MediaType.TEXT_HTML));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING))
                .as("text/html is intentionally OUT of server.compression.mime-types; a re-widening "
                        + "reopens the BREACH oracle on the token-review pages")
                .isNull();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
