package com.beautica.notification.mail;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("BrevoApiMailSender — WireMock-stubbed Brevo transactional API (Phase 374)")
class BrevoApiMailSenderTest {

    private static final String API_KEY = "xkeysib-super-secret-key-value";
    private static final String RECIPIENT = "victim-recipient@example.com";
    private static final String BODY_MARKER = "secret-body-otp-482913";
    private static final String BREVO_MESSAGE = "echoed " + RECIPIENT + " is invalid";
    private static final String PATH = "/v3/smtp/email";

    private WireMockServer wireMock;
    private SimpleMeterRegistry registry;
    private BrevoApiMailSender sender;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        registry = new SimpleMeterRegistry();
        sender = newSender(wireMock.baseUrl(), Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private BrevoApiMailSender newSender(String baseUrl, Duration readTimeout) {
        BrevoApiProperties props = new BrevoApiProperties(baseUrl, API_KEY, Duration.ofSeconds(1), readTimeout,
                "Beautica");
        return new BrevoApiMailSender(RestClient.builder(), props, registry);
    }

    private MimeMessage message() throws Exception {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom("noreply@beautica.app");
        helper.setTo(RECIPIENT);
        helper.setSubject("Нове бронювання");
        helper.setText("<p>" + BODY_MARKER + "</p>", true);
        return message;
    }

    private void stubStatus(int status, String body) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private double count(String outcome) {
        var counter = registry.find(BrevoApiMailSender.METRIC).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("201 — POSTs the JSON shape with the api-key header to /v3/smtp/email only; success counted")
    void should_postJsonWithApiKey_when_201() throws Exception {
        stubStatus(201, "{\"messageId\":\"<abc@smtp-relay.mailin.fr>\"}");

        sender.send(message());

        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("api-key", equalTo(API_KEY))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Accept", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"sender":{"email":"noreply@beautica.app","name":"Beautica"},
                         "to":[{"email":"%s"}],
                         "subject":"Нове бронювання",
                         "htmlContent":"<p>%s</p>"}
                        """.formatted(RECIPIENT, BODY_MARKER))));
        assertThat(count("success")).isEqualTo(1);
    }

    @Test
    @DisplayName("D9a — the sender calls ONLY POST /v3/smtp/email (no account, sender, contact or template calls)")
    void should_callOnlySendEndpoint_when_sending() throws Exception {
        stubStatus(201, "{\"messageId\":\"m\"}");

        sender.send(message());
        sender.send(new SimpleMailMessage(simple()));

        assertThat(wireMock.getAllServeEvents()).hasSize(2).allSatisfy(e -> {
            assertThat(e.getRequest().getMethod().getName()).isEqualTo("POST");
            assertThat(e.getRequest().getUrl()).isEqualTo(PATH);
        });
        wireMock.verify(0, anyRequestedFor(urlPathEqualTo("/v3/account")));
        wireMock.verify(2, anyRequestedFor(anyUrl()));
    }

    private static SimpleMailMessage simple() {
        SimpleMailMessage simple = new SimpleMailMessage();
        simple.setFrom("noreply@beautica.app");
        simple.setTo(RECIPIENT);
        simple.setSubject("plain");
        simple.setText("plain text");
        return simple;
    }

    @Test
    @DisplayName("SimpleMailMessage → textContent plus generated htmlContent (Brevo requires htmlContent)")
    void should_sendTextAndHtml_when_simpleMailMessage() {
        stubStatus(201, "{\"messageId\":\"m\"}");

        sender.send(simple());

        wireMock.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"sender":{"email":"noreply@beautica.app","name":"Beautica"},"to":[{"email":"%s"}],
                 "subject":"plain","textContent":"plain text","htmlContent":"plain text"}
                """.formatted(RECIPIENT))));
    }

    @Test
    @DisplayName("JavaMailSender interface default send(MimeMessagePreparator) works through this transport")
    void should_sendViaPreparatorDefault_when_called() {
        stubStatus(201, "{\"messageId\":\"m\"}");

        sender.send(mime -> {
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom("noreply@beautica.app");
            helper.setTo(RECIPIENT);
            helper.setSubject("prep");
            helper.setText("<b>x</b>", true);
        });

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("400 invalid_parameter → Permanent with a redacted message; permanent counted")
    void should_throwPermanent_when_400InvalidParameter() throws Exception {
        stubStatus(400, "{\"code\":\"invalid_parameter\",\"message\":\"" + BREVO_MESSAGE + "\"}");

        Throwable thrown = catchThrowable(() -> sender.send(message()));

        assertThat(thrown).isInstanceOf(BrevoPermanentMailException.class)
                .hasMessage("Brevo API HTTP 400 code=invalid_parameter");
        assertThat(count("permanent")).isEqualTo(1);
    }

    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = {404, 413, 422})
    @DisplayName("other 4xx → Permanent")
    void should_throwPermanent_when_otherClientError(int status) throws Exception {
        stubStatus(status, "{\"code\":\"document_not_found\",\"message\":\"x\"}");

        assertThatThrownBy(() -> sender.send(message())).isInstanceOf(BrevoPermanentMailException.class)
                .hasMessage("Brevo API HTTP " + status + " code=document_not_found");
    }

    @Test
    @DisplayName("400 unauthorized / permission_denied / not_enough_credits → Transient (system-side, trips the breaker)")
    void should_throwTransient_when_400AccountSideCode() throws Exception {
        for (String code : new String[]{"unauthorized", "permission_denied", "not_enough_credits"}) {
            wireMock.resetAll();
            stubStatus(400, "{\"code\":\"" + code + "\",\"message\":\"" + BREVO_MESSAGE + "\"}");

            assertThatThrownBy(() -> sender.send(message())).isInstanceOf(BrevoTransientMailException.class)
                    .hasMessage("Brevo API HTTP 400 code=" + code);
        }
        assertThat(count("transient")).isEqualTo(3);
    }

    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = {401, 402, 403, 429, 500, 503})
    @DisplayName("401 / 402 / 403 / 429 / 5xx → Transient")
    void should_throwTransient_when_authRateLimitOrServerError(int status) throws Exception {
        stubStatus(status, "{\"code\":\"unauthorized\",\"message\":\"" + BREVO_MESSAGE + "\"}");

        assertThatThrownBy(() -> sender.send(message())).isInstanceOf(BrevoTransientMailException.class)
                .hasMessageStartingWith("Brevo API HTTP " + status + " code=");
    }

    @Test
    @DisplayName("unknown code / non-JSON body → code=other / none, never echoed")
    void should_whitelistCode_when_unknownOrMissing() throws Exception {
        stubStatus(400, "{\"code\":\"" + RECIPIENT + "\",\"message\":\"x\"}");
        Throwable unknown = catchThrowable(() -> sender.send(message()));
        wireMock.resetAll();
        stubStatus(400, "");
        Throwable empty = catchThrowable(() -> sender.send(message()));

        assertThat(unknown).hasMessage("Brevo API HTTP 400 code=other");
        assertThat(empty).hasMessage("Brevo API HTTP 400 code=none");
    }

    @Test
    @Timeout(10)
    @DisplayName("read timeout (fixed delay > read timeout) → Transient")
    void should_throwTransient_when_readTimeout() throws Exception {
        BrevoApiMailSender slow = newSender(wireMock.baseUrl(), Duration.ofMillis(300));
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201).withFixedDelay(2000)));

        assertThatThrownBy(() -> slow.send(message())).isInstanceOf(BrevoTransientMailException.class)
                .hasMessageStartingWith("Brevo API I/O failure");
    }

    @Test
    @Timeout(10)
    @DisplayName("connection refused (server stopped) → Transient")
    void should_throwTransient_when_connectionRefused() throws Exception {
        String baseUrl = wireMock.baseUrl();
        wireMock.stop();
        BrevoApiMailSender refused = newSender(baseUrl, Duration.ofSeconds(1));

        assertThatThrownBy(() -> refused.send(message())).isInstanceOf(BrevoTransientMailException.class);
        assertThat(count("transient")).isEqualTo(1);
    }

    @Test
    @DisplayName("a bare CancellationException (JDK client read-timeout cancellation) → Transient, deterministic")
    void should_throwTransient_when_cancellationException() throws Exception {
        RestClient.Builder cancelling = RestClient.builder().requestInterceptor((req, body, exec) -> {
            throw new java.util.concurrent.CancellationException("read timeout cancelled the response future");
        });
        BrevoApiMailSender cancelled = new BrevoApiMailSender(cancelling, new BrevoApiProperties(wireMock.baseUrl(),
                API_KEY, Duration.ofSeconds(1), Duration.ofSeconds(1), "Beautica"), registry);

        assertThatThrownBy(() -> cancelled.send(message())).isInstanceOf(BrevoTransientMailException.class)
                .hasMessage("Brevo API I/O failure CancellationException");
        assertThat(count("transient")).isEqualTo(1);
        assertThat(count("permanent")).isZero();
    }

    @Test
    @DisplayName("IOException / HttpTimeoutException wrapped anywhere in the cause chain → Transient")
    void should_classifyTransportFailure_when_ioInCauseChain() {
        assertThat(BrevoRestClients.isTransportFailure(new IllegalStateException("x",
                new RuntimeException(new java.net.http.HttpTimeoutException("t"))))).isTrue();
        assertThat(BrevoRestClients.isTransportFailure(new java.util.concurrent.CancellationException())).isTrue();
        assertThat(BrevoRestClients.isTransportFailure(new IllegalArgumentException("serialization"))).isFalse();
    }

    @Test
    @DisplayName("a non-transport client failure (e.g. local serialization) stays Permanent")
    void should_throwPermanent_when_nonTransportClientFailure() throws Exception {
        RestClient.Builder failing = RestClient.builder().requestInterceptor((req, body, exec) -> {
            throw new IllegalStateException("local bug");
        });
        BrevoApiMailSender broken = new BrevoApiMailSender(failing, new BrevoApiProperties(wireMock.baseUrl(),
                API_KEY, Duration.ofSeconds(1), Duration.ofSeconds(1), "Beautica"), registry);

        assertThatThrownBy(() -> broken.send(message())).isInstanceOf(BrevoPermanentMailException.class);
    }

    @Test
    @DisplayName("connection reset mid-response → Transient")
    void should_throwTransient_when_connectionReset() throws Exception {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> sender.send(message())).isInstanceOf(BrevoTransientMailException.class);
    }

    @Test
    @DisplayName("an inline CID part is rejected before any HTTP call → Permanent")
    void should_throwPermanentWithoutCall_when_inlinePart() throws Exception {
        MimeMessage message = message();
        new MimeMessageHelper(message, true, "UTF-8").addInline("beauticaLogo",
                new org.springframework.core.io.ByteArrayResource(new byte[]{1}), "image/png");

        assertThatThrownBy(() -> sender.send(message)).isInstanceOf(BrevoPermanentMailException.class);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("several messages are sent sequentially and stop at the first failure")
    void should_stopAtFirstFailure_when_sendingSeveral() throws Exception {
        stubStatus(503, "{}");

        assertThatThrownBy(() -> sender.send(message(), message())).isInstanceOf(MailException.class);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("redaction — no api-key, recipient, body or Brevo message in logs or exception messages")
    void should_neverLeakSecretsOrPii_when_failing(CapturedOutput output) throws Exception {
        stubStatus(400, "{\"code\":\"invalid_parameter\",\"message\":\"" + BREVO_MESSAGE + "\"}");
        Throwable permanent = catchThrowable(() -> sender.send(message()));
        wireMock.resetAll();
        stubStatus(503, "{\"code\":\"x\",\"message\":\"" + BREVO_MESSAGE + "\"}");
        Throwable transientFailure = catchThrowable(() -> sender.send(message()));
        wireMock.resetAll();
        stubStatus(201, "{\"messageId\":\"m\"}");
        sender.send(message());

        for (Throwable t : new Throwable[]{permanent, transientFailure}) {
            assertThat(t.getMessage()).doesNotContain(API_KEY).doesNotContain(RECIPIENT)
                    .doesNotContain(BODY_MARKER).doesNotContain("echoed");
            assertThat(t.getCause()).isNull();
        }
        assertThat(output.getAll()).doesNotContain(API_KEY).doesNotContain(RECIPIENT)
                .doesNotContain(BODY_MARKER).doesNotContain("echoed");
    }

    @Test
    @DisplayName("BrevoApiProperties.toString masks the api key")
    void should_maskApiKey_when_propertiesPrinted() {
        BrevoApiProperties props = new BrevoApiProperties("https://api.brevo.com", API_KEY, Duration.ofSeconds(3),
                Duration.ofSeconds(10), "Beautica");

        assertThat(props.toString()).doesNotContain(API_KEY).contains("apiKey=***");
    }
}
