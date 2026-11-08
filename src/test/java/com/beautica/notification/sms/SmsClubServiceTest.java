package com.beautica.notification.sms;

import com.beautica.common.observability.ExternalCallFailure;
import com.beautica.common.observability.SentryEventCapture;
import io.sentry.SentryEvent;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.config.SmsClubProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.lessThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 439 — {@link SmsClubService} against a WireMock-stubbed SMSClub endpoint: the request contract, every row of
 * the provider response table, the no-retry rule (request count = 1) and the logging discipline.
 */
@DisplayName("SmsClubService — WireMock-stubbed SMSClub endpoint")
class SmsClubServiceTest {

    private static final String TOKEN = "smsclub-secret-token-value";
    private static final String OTP_TEXT = "Ваш код Beautica 482913";
    private static final String PHONE = "+380671234567";
    private static final String DIGITS = "380671234567";
    private static final String PATH = "/sms/send";
    private static final int LIFETIME = 60;
    private static final String SUCCESS = "{\"success_request\":{\"info\":{\"106\":\"" + DIGITS + "\"}}}";

    private WireMockServer wireMock;
    private SmsClubService service;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        service = new SmsClubService(RestClient.builder(), props(wireMock.baseUrl() + PATH), LIFETIME);

        serviceLogger = (Logger) LoggerFactory.getLogger(SmsClubService.class);
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

    private static SmsClubProperties props(String baseUrl) {
        SmsClubProperties props = new SmsClubProperties();
        props.setToken(TOKEN);
        props.setBaseUrl(baseUrl);
        return props;
    }

    private void stub(int status, String body) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private String addInfo(String key, String text) {
        return "{\"success_request\":{\"add_info\":{\"" + key + "\":\"" + text + "\"}}}";
    }

    // ── request contract ────────────────────────────────────────────────────

    @Test
    @DisplayName("a send posts the Bearer token, JSON headers and the phone/src_addr/message/lifetime body exactly once")
    void should_postContractBodyAndHeaders_when_sending() {
        stub(200, SUCCESS);

        service.send(PHONE, OTP_TEXT);

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Accept", containing("application/json"))
                .withRequestBody(equalToJson("""
                        {"phone":["380671234567"],"src_addr":"Beauty","message":"Ваш код Beautica 482913","lifetime":60}
                        """)));
    }

    @Test
    @DisplayName("a null lifetime leaves the lifetime field out of the request body")
    void should_omitLifetime_when_lifetimeIsNull() {
        stub(200, SUCCESS);
        SmsClubService noLifetime = new SmsClubService(RestClient.builder(), props(wireMock.baseUrl() + PATH), null);

        noLifetime.send(PHONE, OTP_TEXT);

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"phone":["380671234567"],"src_addr":"Beauty","message":"Ваш код Beautica 482913"}
                """)));
    }

    @Test
    @DisplayName("a zero lifetime is refused at construction with an IllegalStateException")
    void should_rejectNonPositiveLifetime_when_constructing() {
        SmsClubProperties props = props(wireMock.baseUrl() + PATH);

        assertThatThrownBy(() -> new SmsClubService(RestClient.builder(), props, 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lifetime");
    }

    static Stream<Arguments> badTimeouts() {
        return Stream.of(null, Duration.ZERO, Duration.ofSeconds(-1)).flatMap(bad -> Stream.of(
                Arguments.of("app.sms.smsclub.connect-timeout", bad),
                Arguments.of("app.sms.smsclub.read-timeout", bad)));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("badTimeouts")
    @DisplayName("a null, zero or negative connect or read timeout fails construction, naming the property")
    void should_failFast_when_timeoutIsNullZeroOrNegative(String property, Duration bad) {
        SmsClubProperties props = props("http://unused");
        if (property.endsWith("connect-timeout")) {
            props.setConnectTimeout(bad);
        } else {
            props.setReadTimeout(bad);
        }

        assertThatThrownBy(() -> new SmsClubService(RestClient.builder(), props, LIFETIME))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(property);
    }

    // ── 200 bodies ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("an info entry naming the sent number is success, logged as OK with the masked recipient and no sms id")
    void should_succeed_when_infoCarriesTheSentNumber() {
        stub(200, SUCCESS);

        service.send(PHONE, OTP_TEXT);

        assertThat(renderLogs()).contains("status=OK").contains("provider=smsclub").contains("+380***4567")
                .doesNotContain("106");
    }

    @ParameterizedTest(name = "add_info ''{0}'' -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Incorrect phone number (380671234567)|PHONE_INVALID",
            "Phone number cannot be blank|PHONE_INVALID",
            "Incorrect phones array format|PHONE_INVALID",
            "This number is in the black list|BLACKLISTED",
            "THIS NUMBER IS IN THE BLACK LIST|BLACKLISTED",
            "Not enough money. It is necessary to refill the balance|NO_FUNDS",
            "Message cannot be blank|MESSAGE_BLANK",
            "Something new from the provider 380671234567|OTHER"})
    @DisplayName("an add_info entry for the sent number is a definite rejection: the exception carries only the reason "
            + "token, the ERROR log adds the provider text with the number masked (phase 441)")
    void should_throwDefiniteRejectionWithReasonToken_when_addInfoNamesTheNumber(String text, String reason) {
        stub(200, addInfo(DIGITS, text));

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .isNotInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMSClub rejected the message reason=" + reason);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(logAppender.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("service=smsclub op=send http=200 reason=" + reason.toLowerCase(java.util.Locale.ROOT))
                        .containsIgnoringCase(text.substring(0, 12)).doesNotContain(DIGITS));
    }

    @ParameterizedTest(name = "src_addr ''{0}''")
    @ValueSource(strings = {"Sender name is incorrect", "Sender Name cannot be blank",
            "Sender Name has state \\\"moderation\\\" and not available"})
    @DisplayName("a request-level src_addr add_info is a definite SENDER_INVALID rejection")
    void should_throwSenderInvalid_when_requestLevelAddInfoRejectsTheSender(String text) {
        stub(200, addInfo("src_addr", text));

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .isNotInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMSClub rejected the message reason=SENDER_INVALID");
    }

    @ParameterizedTest(name = "body ''{0}''")
    @ValueSource(strings = {"", "{}", "not json", "{\"success_request\":{}}",
            "{\"success_request\":{\"info\":{\"106\":\"380000000000\"}}}"})
    @DisplayName("a 200 body with no verdict for the sent number is an ambiguous outcome, sent once")
    void should_throwAmbiguous_when_bodyCarriesNoVerdictForTheNumber(String body) {
        stub(200, body);

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMS delivery outcome unknown");
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    // ── HTTP errors ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 401, 429, 453, 503, 307})
    @DisplayName("a non-2xx status is a definite failure, never retried, with no phone or text in the log")
    void should_throwDefiniteFailureWithoutRetry_when_statusIsNot2xx(int status) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Location", PATH).withBody("{\"echo\":\"" + DIGITS + " " + OTP_TEXT + "\"}")));

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .isNotInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMSClub rejected the request with HTTP " + status);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(renderLogs()).contains("http=" + status).doesNotContain(DIGITS).doesNotContain(OTP_TEXT);
    }

    @Test
    @DisplayName("HTTP 401 raises the auth alarm at ERROR level")
    void should_logAuthAlarmAtError_when_statusIs401() {
        stub(401, "{}");

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT)).isInstanceOf(SmsDeliveryException.class);

        assertThat(logAppender.list).anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("http=401 reason=auth"));
    }

    @Test
    @DisplayName("phase 441: HTTP 400 with a wrong-sender body is ONE ERROR with the stable template, the provider "
            + "reason text as detail and the masked recipient — never the token, text, OTP or full number")
    void should_logOneErrorWithProviderReason_when_status400CarriesSenderRejection() {
        stub(400, "{\"success_request\":{\"add_info\":{\"src_addr\":\"Sender name is incorrect: BEAUTY for "
                + DIGITS + "\"}}}");

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT)).isInstanceOf(SmsDeliveryException.class)
                .hasMessage("SMSClub rejected the request with HTTP 400");

        assertThat(logAppender.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement()
                .satisfies(e -> {
                    assertThat(e.getMessage()).isEqualTo(ExternalCallFailure.MESSAGE);
                    assertThat(e.getFormattedMessage())
                            .isEqualTo("External call failed service=smsclub op=send http=400 reason=sender_invalid "
                                    + "to=+380***4567 detail=Sender name is incorrect: BEAUTY for [phone]");
                });
        assertThat(renderLogs()).doesNotContain(TOKEN).doesNotContain(OTP_TEXT).doesNotContain("482913")
                .doesNotContain(DIGITS);
    }

    @Test
    @DisplayName("phase 441: a 4xx body echoing the token, the message text and the OTP is scrubbed before logging")
    void should_scrubTokenTextAndOtp_when_errorBodyEchoesThem() {
        stub(422, "{\"message\":\"bad request " + TOKEN + " " + OTP_TEXT + " code 482913 to " + DIGITS + "\"}");

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT)).isInstanceOf(SmsDeliveryException.class);

        assertThat(logAppender.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("http=422 reason=other")
                        .contains("detail=bad request").doesNotContain(TOKEN).doesNotContain(OTP_TEXT)
                        .doesNotContain("482913").doesNotContain(DIGITS));
    }

    @Test
    @DisplayName("audit-fix 441: a non-2xx body in an unknown JSON shape is never logged — only its length — so an "
            + "escaped guest cancel link (\\/c\\/<token>) reaches neither the log nor the Sentry event")
    void should_logOnlyBodyLength_when_errorBodyHasUnexpectedShape() {
        String body = "{\"result\":{\"link\":\"https:\\/\\/beautica.app\\/c\\/AbCdEfGh12345678\"}}";
        stub(400, body);
        List<SentryEvent> events;

        try (SentryEventCapture sentry = SentryEventCapture.attach(serviceLogger)) {
            assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT)).isInstanceOf(SmsDeliveryException.class);
            events = sentry.forwarded();
        }

        assertThat(logAppender.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("http=400 reason=http_400")
                        .contains("detail=unparsed(len=" + body.length() + ")"));
        assertThat(renderLogs()).doesNotContain("AbCdEfGh").doesNotContain("beautica.app");
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.getMessage().getFormatted()).doesNotContain("AbCdEfGh");
            assertThat(String.join(" ", e.getMessage().getParams())).doesNotContain("AbCdEfGh");
        });
    }

    @Test
    @DisplayName("audit-fix 441: a visitor-driven PHONE_INVALID flood stays ERROR on every attempt but reaches "
            + "Sentry exactly 5 times (the window budget), under one smsclub/phone_invalid fingerprint")
    void should_capSentryEvents_when_manyPhoneInvalidRejections() {
        stub(200, addInfo(DIGITS, "incorrect phone number"));
        int attempts = 30;
        List<SentryEvent> events;

        try (SentryEventCapture sentry = SentryEventCapture.attach(serviceLogger)) {
            for (int i = 0; i < attempts; i++) {
                assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT)).isInstanceOf(SmsDeliveryException.class);
            }
            events = sentry.forwarded();
        }

        assertThat(logAppender.list).filteredOn(e -> e.getLevel() == Level.ERROR).hasSize(attempts)
                .allSatisfy(e -> assertThat(e.getFormattedMessage()).contains("reason=phone_invalid"));
        assertThat(events).hasSize(5).allSatisfy(e -> assertThat(e.getFingerprints())
                .containsExactly("external-call", "smsclub", "phone_invalid"));
    }

    // ── transport ───────────────────────────────────────────────────────────

    @Test
    @Timeout(10)
    @DisplayName("a refused connection fails before the write, so it is definite rather than ambiguous")
    void should_throwDefiniteFailure_when_connectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        SmsClubService refused = new SmsClubService(RestClient.builder(),
                props("http://127.0.0.1:" + closedPort + PATH), LIFETIME);

        assertThatThrownBy(() -> refused.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .isNotInstanceOf(SmsDeliveryAmbiguousException.class);
    }

    @Test
    @Timeout(5)
    @DisplayName("a read timeout after the write is ambiguous and the request is not repeated")
    void should_throwAmbiguousWithoutRetry_when_readTimesOut() {
        SmsClubProperties props = props(wireMock.baseUrl() + PATH);
        props.setReadTimeout(Duration.ofMillis(300));
        // Attempts are counted client-side: Spring's JDK read-timeout clock starts at sendAsync, so on a cold JVM the
        // 300 ms can expire before the request reaches WireMock — a server-side "exactly 1" count races. The client
        // count is deterministic and is the invariant under test (the send is never repeated).
        AtomicInteger attempts = new AtomicInteger();
        RestClient.Builder counting = RestClient.builder().requestInterceptor((request, body, execution) -> {
            attempts.incrementAndGet();
            return execution.execute(request, body);
        });
        SmsClubService shortTimeout = new SmsClubService(counting, props, LIFETIME);
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(2000).withBody(SUCCESS)));

        assertThatThrownBy(() -> shortTimeout.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryAmbiguousException.class);
        assertThat(attempts).hasValue(1);
        wireMock.verify(lessThanOrExactly(1), postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @Timeout(5)
    @DisplayName("a bare CancellationException from the client call (JDK-client read-timeout race) is ambiguous")
    void should_throwAmbiguous_when_clientCallThrowsBareCancellation() {
        RestClient.Builder cancelling = RestClient.builder().requestInterceptor((request, body, execution) -> {
            throw new CancellationException();
        });
        SmsClubService cancelled = new SmsClubService(cancelling, props(wireMock.baseUrl() + PATH), LIFETIME);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> cancelled.send(PHONE, OTP_TEXT));

        assertThat(thrown).isInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMS delivery outcome unknown")
                .hasCauseInstanceOf(CancellationException.class);
    }

    // ── pre-send refusals ───────────────────────────────────────────────────

    @ParameterizedTest(name = "recipient ''{0}''")
    @ValueSource(strings = {"+48123456789", "380671234567", "+3806712345", "+3806712345678", " "})
    @DisplayName("a non-UA or malformed recipient is refused before any HTTP call")
    void should_throwWithoutCallingProvider_when_recipientIsNotUaMobile(String phone) {
        stub(200, SUCCESS);

        assertThatThrownBy(() -> service.send(phone, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessage("SMS recipient is not a UA mobile");
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("a null recipient is refused before any HTTP call")
    void should_throwWithoutCallingProvider_when_recipientIsNull() {
        assertThatThrownBy(() -> service.send(null, OTP_TEXT))
                .hasMessage("SMS recipient is not a UA mobile");
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("a blank token reports 'not configured' and makes no HTTP call")
    void should_throwNotConfiguredWithoutCallingProvider_when_tokenIsBlank() {
        SmsClubProperties blank = props(wireMock.baseUrl() + PATH);
        blank.setToken(" ");
        SmsClubService unconfigured = new SmsClubService(RestClient.builder(), blank, LIFETIME);

        assertThatThrownBy(() -> unconfigured.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessage("SMS provider is not configured");
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    // ── staging daily cap (phase 439 audit-fix) ─────────────────────────────

    @Test
    @DisplayName("with a staging daily cap of 2, the third send is refused with the fixed STAGING_DAILY_CAP reason, "
            + "no HTTP call and no phone in the log")
    void should_refuseWithoutCallingProvider_when_stagingDailyCapIsReached() {
        stub(200, SUCCESS);
        Clock fixed = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
        SmsClubService capped = new SmsClubService(RestClient.builder(), props(wireMock.baseUrl() + PATH), LIFETIME,
                new StagingSmsDailyCap(fixed, 2));
        capped.send(PHONE, OTP_TEXT);
        capped.send(PHONE, OTP_TEXT);

        assertThatThrownBy(() -> capped.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .isNotInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMS send refused reason=STAGING_DAILY_CAP");
        wireMock.verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertThat(renderLogs()).contains("reason=STAGING_DAILY_CAP").doesNotContain(DIGITS).doesNotContain(OTP_TEXT);
    }

    @Test
    @DisplayName("without a staging cap (prod wiring) sends are never counted or refused")
    void should_neverRefuse_when_noStagingDailyCapIsWired() {
        stub(200, SUCCESS);

        for (int i = 0; i < 60; i++) {
            service.send(PHONE, OTP_TEXT);
        }

        wireMock.verify(60, postRequestedFor(urlEqualTo(PATH)));
    }

    // ── logging discipline ──────────────────────────────────────────────────

    @Test
    @DisplayName("neither a success nor a rejection logs the token, the message text, the full phone or the raw add_info")
    void should_neverLogTokenTextPhoneOrRawAddInfo_when_sendingSucceedsAndFails() {
        stub(200, SUCCESS);
        service.send(PHONE, OTP_TEXT);
        stub(200, addInfo(DIGITS, "This number is in the black list"));
        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT)).isInstanceOf(SmsDeliveryException.class);
        stub(500, "{}");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.send(PHONE, OTP_TEXT));

        String allLogs = renderLogs();
        // Phase 441: the provider's add_info text ("black list") IS logged now — sanitised, never the number.
        assertThat(allLogs).isNotBlank().doesNotContain(TOKEN).doesNotContain(OTP_TEXT).doesNotContain("482913")
                .doesNotContain(DIGITS).doesNotContain("671234567").containsIgnoringCase("black list")
                .contains("+380***4567");
        assertThat(thrown.getMessage()).doesNotContain(DIGITS).doesNotContain(TOKEN);
    }

    // ── phase 439 QA additions ──────────────────────────────────────────────

    @Test
    @DisplayName("src_addr carries the configured sender name, not the Beauty default, when SMSCLUB_SENDER_NAME is overridden")
    void should_sendConfiguredSenderName_when_senderNameIsOverridden() {
        stub(200, SUCCESS);
        SmsClubProperties own = props(wireMock.baseUrl() + PATH);
        own.setSenderName("BeauticaUA");
        SmsClubService ownSender = new SmsClubService(RestClient.builder(), own, LIFETIME);

        ownSender.send(PHONE, OTP_TEXT);

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"phone":["380671234567"],"src_addr":"BeauticaUA","message":"Ваш код Beautica 482913","lifetime":60}
                """)));
    }

    @Test
    @DisplayName("multipart Cyrillic text with newlines, quotes and an emoji reaches the provider byte-for-byte")
    void should_passMessageThroughUnchanged_when_textIsLongMultipartCyrillic() throws Exception {
        stub(200, SUCCESS);
        String text = "Оксано, ваш запис підтверджено: «Манікюр» 12.11.2026 о 12:00.\n"
                + "Майстер: Ірина \"Nail\" Коваль 💅 — скасувати: https://beautica.app/b/abc?x=1&y=2";

        service.send(PHONE, text);

        String sent = wireMock.findAll(postRequestedFor(urlEqualTo(PATH))).get(0).getBodyAsString();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(sent).path("message").asText())
                .as("no truncation, transliteration or escaping drift").isEqualTo(text);
    }

    @ParameterizedTest(name = "recipient ''{0}''")
    @ValueSource(strings = {"+380 67 123 4567", "+380-67-123-4567", " +380671234567", "+380671234567 ",
            "+380671234567\n", "0671234567", "+3800671234567", "+38067123456a", "+38067１234567"})
    @DisplayName("spaced, dashed, padded, national, over-long and non-ASCII-digit forms are refused before any HTTP call")
    void should_throwWithoutCallingProvider_when_recipientIsNotStrictE164UaMobile(String phone) {
        stub(200, SUCCESS);

        assertThatThrownBy(() -> service.send(phone, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessage("SMS recipient is not a UA mobile");
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
        assertThat(renderLogs()).contains("reason=RECIPIENT_NOT_UA_MOBILE").doesNotContain("671234567");
    }

    @Test
    @DisplayName("info naming the sent number wins even when add_info rejects another number in the same reply")
    void should_succeed_when_infoCarriesTheNumberAlongsideAnUnrelatedAddInfo() {
        stub(200, "{\"success_request\":{\"info\":{\"106\":\"" + DIGITS + "\"},"
                + "\"add_info\":{\"380000000000\":\"This number is in the black list\"}}}");

        service.send(PHONE, OTP_TEXT);

        assertThat(renderLogs()).contains("status=OK").doesNotContain("reason=");
    }

    @Test
    @DisplayName("the add_info entry for the sent number is the reason, not the first (request-level) entry")
    void should_reportTheOwnNumbersReason_when_addInfoHasSeveralEntries() {
        stub(200, "{\"success_request\":{\"add_info\":{\"src_addr\":\"Sender name is incorrect\","
                + "\"" + DIGITS + "\":\"This number is in the black list\"}}}");

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessage("SMSClub rejected the message reason=BLACKLISTED");
    }

    @Test
    @DisplayName("an add_info text with leading whitespace still maps to its reason token")
    void should_mapReason_when_addInfoTextHasLeadingWhitespace() {
        stub(200, addInfo(DIGITS, "   Not enough money. It is necessary to refill the balance"));

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .hasMessage("SMSClub rejected the message reason=NO_FUNDS");
    }

    @Test
    @DisplayName("per-number Sender name add_info maps to SENDER_INVALID")
    void should_throwSenderInvalid_when_perNumberAddInfoRejectsTheSender() {
        stub(200, addInfo(DIGITS, "Sender name is incorrect"));

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .isNotInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMSClub rejected the message reason=SENDER_INVALID");
    }

    @Test
    @DisplayName("a non-string add_info value is a definite OTHER rejection, not a crash")
    void should_throwOther_when_addInfoValueIsNotAString() {
        stub(200, "{\"success_request\":{\"add_info\":{\"" + DIGITS + "\":{\"code\":17}}}}");

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .isNotInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMSClub rejected the message reason=OTHER");
    }

    @ParameterizedTest(name = "body ''{0}''")
    @ValueSource(strings = {"{\"success_request\":{\"add_info\":{}}}", "{\"success_request\":{\"info\":{}}}",
            "{\"success_request\":{\"add_info\":\"blocked\"}}", "{\"success_request\":{\"info\":[\"380671234567\"]}}",
            "[]", "null", "   "})
    @DisplayName("empty add_info, empty/array info, scalar add_info, a JSON array, null or whitespace are ambiguous")
    void should_throwAmbiguous_when_verdictShapeIsUnexpected(String body) {
        stub(200, body);

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMS delivery outcome unknown");
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(logAppender.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("http=200 reason=unreadable_response"));
    }

    @Test
    @DisplayName("an unparseable body echoing the phone and text never reaches the log or the exception")
    void should_neverLeakEchoedBody_when_responseIsNotJson() {
        stub(200, "<html>" + DIGITS + " " + OTP_TEXT + " " + TOKEN + "</html>");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.send(PHONE, OTP_TEXT));

        assertThat(thrown).isInstanceOf(SmsDeliveryAmbiguousException.class);
        assertThat(thrown.getMessage()).doesNotContain(DIGITS).doesNotContain(TOKEN).doesNotContain("482913");
        assertThat(renderLogs()).doesNotContain(DIGITS).doesNotContain(TOKEN).doesNotContain("482913");
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {301, 302, 404, 413, 500, 502, 504})
    @DisplayName("other 3xx/4xx/5xx codes are definite, never retried and never read into the message")
    void should_throwDefiniteFailureWithoutRetry_when_statusIsAnyOtherNon2xx(int status) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Location", PATH).withBody("{\"echo\":\"" + DIGITS + "\"}")));

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .isNotInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMSClub rejected the request with HTTP " + status);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("phase 441: 401 and 429 are each ONE ERROR; the 401 echoing the token never logs it")
    void should_keepAuthAlarmFreeOfSecrets_when_statusIs401() {
        stub(401, "{\"token\":\"" + TOKEN + "\"}");
        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT)).isInstanceOf(SmsDeliveryException.class);
        stub(429, "{}");
        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT)).isInstanceOf(SmsDeliveryException.class);

        assertThat(logAppender.list).filteredOn(e -> e.getLevel() == Level.ERROR)
                .extracting(ILoggingEvent::getFormattedMessage)
                .hasSize(2)
                .anySatisfy(m -> assertThat(m).contains("http=401 reason=auth"))
                .anySatisfy(m -> assertThat(m).contains("http=429 reason=http_429"))
                .allSatisfy(m -> assertThat(m).doesNotContain(TOKEN).doesNotContain(DIGITS));
        assertThat(logAppender.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @ParameterizedTest(name = "fault {0}")
    @org.junit.jupiter.params.provider.EnumSource(value = Fault.class,
            names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE", "RANDOM_DATA_THEN_CLOSE"})
    @Timeout(10)
    @DisplayName("a connection dropped after the request was written is ambiguous and never retried")
    void should_throwAmbiguousWithoutRetry_when_connectionDropsAfterTheWrite(Fault fault) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(fault)));

        assertThatThrownBy(() -> service.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMS delivery outcome unknown");
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(renderLogs()).doesNotContain(DIGITS).doesNotContain(TOKEN);
    }

    @Test
    @Timeout(15)
    @DisplayName("an unresolvable host fails before the write — definite, so the caller may safely report not-sent")
    void should_throwDefiniteFailure_when_hostDoesNotResolve() {
        SmsClubService unknownHost = new SmsClubService(RestClient.builder(),
                props("http://smsclub-qa-439.invalid" + PATH), LIFETIME);

        assertThatThrownBy(() -> unknownHost.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .isNotInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMS delivery failed");
        assertThat(renderLogs()).doesNotContain("smsclub-qa-439").doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("a read timeout's exception and log carry no phone, text or token")
    @Timeout(5)
    void should_keepTimeoutFailureFreeOfPii_when_readTimesOut() {
        SmsClubProperties props = props(wireMock.baseUrl() + PATH);
        props.setReadTimeout(Duration.ofMillis(300));
        SmsClubService shortTimeout = new SmsClubService(RestClient.builder(), props, LIFETIME);
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(2000).withBody(SUCCESS)));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> shortTimeout.send(PHONE, OTP_TEXT));

        assertThat(thrown).isInstanceOf(SmsDeliveryAmbiguousException.class)
                .hasMessage("SMS delivery outcome unknown");
        assertThat(logAppender.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement()
                .satisfies(e -> {
                    assertThat(e.getFormattedMessage()).contains("service=smsclub op=send http=- reason=timeout")
                            .contains("to=+380***4567");
                    assertThat(e.getThrowableProxy()).as("exception attached for the Sentry stack trace").isNotNull();
                });
        assertThat(renderLogs()).doesNotContain(DIGITS).doesNotContain(TOKEN).doesNotContain(wireMock.baseUrl());
    }

    @Test
    @DisplayName("a null token is 'not configured', like a blank one, with no HTTP call")
    void should_throwNotConfigured_when_tokenIsNull() {
        SmsClubProperties nullToken = props(wireMock.baseUrl() + PATH);
        nullToken.setToken(null);
        SmsClubService unconfigured = new SmsClubService(RestClient.builder(), nullToken, LIFETIME);

        assertThatThrownBy(() -> unconfigured.send(PHONE, OTP_TEXT))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessage("SMS provider is not configured");
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("a negative lifetime is refused at construction, like zero")
    void should_rejectNegativeLifetime_when_constructing() {
        SmsClubProperties props = props(wireMock.baseUrl() + PATH);

        assertThatThrownBy(() -> new SmsClubService(RestClient.builder(), props, -1))
                .isInstanceOf(IllegalStateException.class).hasMessage("app.sms.smsclub lifetime must be positive");
    }

    private String renderLogs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }
}
