package com.beautica.notification.telegram;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.config.TelegramMode;
import com.beautica.config.TelegramProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Phase 404 — {@link TelegramBotApiClient} against a WireMock Bot API: the D2 error mapping, the D3 request shape
 * and redirect/timeout rules, the D4 limiter, and the D5 no-token / no-chat-id / no-text logging discipline.
 */
@DisplayName("TelegramBotApiClient — WireMock-stubbed Bot API")
class TelegramBotApiClientTest {

    /** Obviously fake, low-entropy (built, not literal) so gitleaks generic-api-key does not fire. */
    private static final String TOKEN = "123456789:" + "x".repeat(35);
    private static final long CHAT_ID = 987654321123L;
    private static final String TEXT = "Нагадування: манікюр завтра о 10:00 у Beautica";
    private static final String PATH = "/bot" + TOKEN + "/sendMessage";
    private static final String OK_BODY = "{\"ok\":true,\"result\":{\"message_id\":1}}";

    private WireMockServer wireMock;
    private MutableClock clock;
    private TelegramBotApiClient client;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger rootLogger;
    private Level previousRootLevel;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        client = newClient(Duration.ofSeconds(4));

        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        previousRootLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logAppender);
        rootLogger.setLevel(previousRootLevel);
        wireMock.stop();
    }

    // ── success + request shape ────────────────────────────────────────────

    @Test
    @DisplayName("200 ok:true → returns normally")
    void should_return_when_okTrue() {
        stub(200, OK_BODY);

        client.send(CHAT_ID, TEXT);

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("request body: chat_id, text, link previews disabled, no parse_mode")
    void should_postPlainTextWithPreviewsDisabled_when_sending() {
        stub(200, OK_BODY);

        client.send(CHAT_ID, TEXT);

        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(equalToJson("""
                        { "chat_id": 987654321123,
                          "text": "Нагадування: манікюр завтра о 10:00 у Beautica",
                          "link_preview_options": { "is_disabled": true } }
                        """))
                .withRequestBody(notContaining("parse_mode")));
    }

    @Test
    @DisplayName("text over 4096 chars → IllegalArgumentException, no request")
    void should_rejectWithoutRequest_when_textTooLong() {
        String tooLong = "a".repeat(TelegramMessenger.MAX_TEXT_LENGTH + 1);

        assertThatThrownBy(() -> client.send(CHAT_ID, tooLong)).isInstanceOf(IllegalArgumentException.class);

        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    // ── D2: permanent ──────────────────────────────────────────────────────

    @Test
    @DisplayName("403 bot was blocked by the user → gone")
    void should_throwGone_when_botBlocked() {
        stub(403, error(403, "Forbidden: bot was blocked by the user"));

        Throwable thrown = catchThrowable(() -> client.send(CHAT_ID, TEXT));

        assertThat(thrown).isInstanceOf(TelegramRecipientGoneException.class)
                .hasMessageContaining("403").hasMessageContaining("bot was blocked by the user");
        assertNoSecrets(thrown);
    }

    @Test
    @DisplayName("403 user is deactivated → gone")
    void should_throwGone_when_userDeactivated() {
        stub(403, error(403, "Forbidden: user is deactivated"));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT)).isInstanceOf(TelegramRecipientGoneException.class);
    }

    @Test
    @DisplayName("400 chat not found → gone")
    void should_throwGone_when_chatNotFound() {
        stub(400, error(400, "Bad Request: chat not found"));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT)).isInstanceOf(TelegramRecipientGoneException.class);
    }

    // ── D2: transient ──────────────────────────────────────────────────────

    @Test
    @DisplayName("400 other description → transient")
    void should_throwTransient_when_badRequestOther() {
        stub(400, error(400, "Bad Request: message text is empty"));

        Throwable thrown = catchThrowable(() -> client.send(CHAT_ID, TEXT));

        assertThat(thrown).isInstanceOf(TelegramDeliveryException.class).hasMessageContaining("400");
        assertNoSecrets(thrown);
    }

    @Test
    @DisplayName("429 with retry_after → transient")
    void should_throwTransient_when_rateLimited() {
        stub(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 5\","
                + "\"parameters\":{\"retry_after\":5}}");

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT))
                .isInstanceOf(TelegramDeliveryException.class).hasMessageContaining("429");
    }

    @Test
    @DisplayName("500 → transient")
    void should_throwTransient_when_serverError() {
        stub(500, error(500, "Internal Server Error"));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT)).isInstanceOf(TelegramDeliveryException.class);
    }

    @Test
    @DisplayName("200 ok:false → transient")
    void should_throwTransient_when_okFalse() {
        stub(200, "{\"ok\":false}");

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT)).isInstanceOf(TelegramDeliveryException.class);
    }

    @Test
    @Timeout(5)
    @DisplayName("read timeout → transient, no URL in the exception")
    void should_throwTransient_when_readTimeout() {
        TelegramBotApiClient shortTimeout = newClient(Duration.ofMillis(300));
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(OK_BODY).withFixedDelay(2000)));

        Throwable thrown = catchThrowable(() -> shortTimeout.send(CHAT_ID, TEXT));

        assertThat(thrown).isInstanceOf(TelegramDeliveryException.class).hasNoCause();
        assertNoSecrets(thrown);
    }

    @Test
    @DisplayName("302 → transient, redirect not followed")
    void should_throwTransientWithoutFollowing_when_redirected() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(302)
                .withHeader("Location", wireMock.baseUrl() + "/elsewhere")));
        wireMock.stubFor(post(urlEqualTo("/elsewhere")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(OK_BODY)));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT))
                .isInstanceOf(TelegramDeliveryException.class).hasMessageContaining("302");

        wireMock.verify(0, postRequestedFor(urlEqualTo("/elsewhere")));
    }

    // ── D4: limiter (audit cycle 1: burst 5 + greedy 25/s) ────────────────

    @Test
    @DisplayName("6th call at the same instant → transient, no HTTP request made")
    void should_throwTransientWithoutRequest_when_burstExhausted() {
        stub(200, OK_BODY);
        for (int i = 0; i < TelegramSendGate.BURST_CAPACITY; i++) {
            client.send(CHAT_ID, TEXT);
        }

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT))
                .isInstanceOf(TelegramDeliveryException.class).hasMessageContaining("rate_limited");

        wireMock.verify(TelegramSendGate.BURST_CAPACITY, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("limiter refills greedily: one token per 40 ms")
    void should_sendAgain_when_refillIntervalElapsed() {
        stub(200, OK_BODY);
        for (int i = 0; i < TelegramSendGate.BURST_CAPACITY; i++) {
            client.send(CHAT_ID, TEXT);
        }
        clock.advance(Duration.ofMillis(1000 / TelegramSendGate.SENDS_PER_SECOND));

        client.send(CHAT_ID, TEXT);

        wireMock.verify(TelegramSendGate.BURST_CAPACITY + 1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("hammering across refill boundaries: no 1 s window admits more than 30 sends")
    void should_admitAtMost30_when_anyOneSecondWindow() {
        // 10 attempts every 5 ms for 3 s, frozen clock stepped by hand; a closed 1 s window [t, t+1000] is checked
        // from every admitted instant. The old capacity-25 + interval-refill bucket admitted 50 in [0, 1000].
        TelegramSendGate gate = new TelegramSendGate(clock);
        List<Long> admittedAtMillis = new ArrayList<>();
        long start = clock.millis();
        for (int step = 0; step < 600; step++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                if (gate.tryAdmit(attempt).isEmpty()) {
                    admittedAtMillis.add(clock.millis() - start);
                    gate.release();
                }
            }
            clock.advance(Duration.ofMillis(5));
        }

        int maxInWindow = 0;
        for (long from : admittedAtMillis) {
            int n = (int) admittedAtMillis.stream().filter(t -> t >= from && t <= from + 1000).count();
            maxInWindow = Math.max(maxInWindow, n);
        }

        assertThat(maxInWindow).isLessThanOrEqualTo(30);
        assertThat(admittedAtMillis).hasSizeGreaterThanOrEqualTo(70);
    }

    // ── audit cycle 1: 429 back-off, body cap, concurrency cap ────────────

    @Test
    @DisplayName("429 retry_after=5 → sends fail fast without HTTP until 5 s have passed")
    void should_failFastWithoutRequest_when_withinRetryAfter() {
        stub(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 5\","
                + "\"parameters\":{\"retry_after\":5}}");
        catchThrowable(() -> client.send(CHAT_ID, TEXT));
        wireMock.resetAll();
        stub(200, OK_BODY);
        clock.advance(Duration.ofMillis(4999));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT))
                .isInstanceOf(TelegramDeliveryException.class).hasMessageContaining("backoff");
        wireMock.verify(0, anyRequestedFor(anyUrl()));

        clock.advance(Duration.ofMillis(1));
        client.send(CHAT_ID, TEXT);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("response body over 16 KiB → transient, even on 200 ok:true")
    void should_throwTransient_when_responseBodyOversized() {
        String padding = "x".repeat(TelegramBotApiClient.MAX_RESPONSE_BYTES);
        stub(200, "{\"ok\":true,\"pad\":\"" + padding + "\"}");

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT))
                .isInstanceOf(TelegramDeliveryException.class).hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("body exactly at the 16 KiB cap is still parsed")
    void should_return_when_responseBodyAtCap() {
        String head = "{\"ok\":true,\"pad\":\"";
        String tail = "\"}";
        String padding = "x".repeat(TelegramBotApiClient.MAX_RESPONSE_BYTES - head.length() - tail.length());
        stub(200, head + padding + tail);

        client.send(CHAT_ID, TEXT);

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("no free connection slot → transient, no HTTP request; slot released after each send")
    void should_throwTransientWithoutRequest_when_concurrencyExhausted() throws Exception {
        Semaphore slots = new Semaphore(TelegramSendGate.MAX_CONCURRENT_SENDS);
        TelegramBotApiClient capped = newClient(Duration.ofSeconds(4), slots);
        stub(200, OK_BODY);
        capped.send(CHAT_ID, TEXT);
        assertThat(slots.availablePermits()).isEqualTo(TelegramSendGate.MAX_CONCURRENT_SENDS);
        slots.acquire(TelegramSendGate.MAX_CONCURRENT_SENDS);
        clock.advance(Duration.ofSeconds(1));

        assertThatThrownBy(() -> capped.send(CHAT_ID, TEXT))
                .isInstanceOf(TelegramDeliveryException.class).hasMessageContaining("concurrency");

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("429 on chat A blocks A only; chat B keeps sending; expiry lifts A's block")
    void should_blockOnlyThatChat_when_429WithRetryAfter() {
        long chatB = CHAT_ID + 1;
        wireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.chat_id", equalTo(Long.toString(CHAT_ID))))
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                        .withBody(rateLimited("30"))));
        wireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.chat_id", equalTo(Long.toString(chatB))))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(OK_BODY)));
        catchThrowable(() -> client.send(CHAT_ID, TEXT));
        clock.advance(Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT)).hasMessageContaining("backoff");
        client.send(chatB, TEXT);

        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(2);
        clock.advance(Duration.ofSeconds(29));
        wireMock.resetAll();
        stub(200, OK_BODY);
        client.send(CHAT_ID, TEXT);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("retry_after of 10 days is capped at one hour, per chat")
    void should_capBackoffAtOneHour_when_retryAfterHuge() {
        stub(429, rateLimited("864000"));
        catchThrowable(() -> client.send(CHAT_ID, TEXT));
        wireMock.resetAll();
        stub(200, OK_BODY);
        clock.advance(TelegramSendGate.MAX_BACKOFF.minusMillis(1));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT)).hasMessageContaining("backoff");
        client.send(CHAT_ID + 1, TEXT);

        clock.advance(Duration.ofMillis(1));
        client.send(CHAT_ID, TEXT);
        wireMock.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("retry_after 0, negative, string, fractional or garbage → no back-off for that chat")
    void should_ignoreBackoff_when_retryAfterNonPositiveOrNonNumeric() {
        for (String retryAfter : new String[]{"0", "-5", "\"5\"", "1.5", "\"soon\"", "null", "{}"}) {
            TelegramBotApiClient fresh = newClient(Duration.ofSeconds(4));
            wireMock.resetAll();
            stub(429, rateLimited(retryAfter));
            catchThrowable(() -> fresh.send(CHAT_ID, TEXT));
            wireMock.resetAll();
            stub(200, OK_BODY);

            fresh.send(CHAT_ID, TEXT);

            wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        }
    }

    @Test
    @DisplayName("a concurrency refusal spends no rate-limit token")
    void should_keepTokens_when_refusedForConcurrency() throws Exception {
        Semaphore slots = new Semaphore(TelegramSendGate.MAX_CONCURRENT_SENDS);
        TelegramSendGate gate = new TelegramSendGate(clock, slots);
        long before = gate.availableTokens();
        slots.acquire(TelegramSendGate.MAX_CONCURRENT_SENDS);

        assertThat(gate.tryAdmit(CHAT_ID)).contains(TelegramSendGate.Refusal.CONCURRENCY);

        assertThat(gate.availableTokens()).isEqualTo(before);
    }

    @Test
    @DisplayName("a rate refusal gives the connection slot back")
    void should_releaseSlot_when_refusedForRate() {
        Semaphore slots = new Semaphore(TelegramSendGate.MAX_CONCURRENT_SENDS);
        TelegramSendGate gate = new TelegramSendGate(clock, slots);
        for (int i = 0; i < TelegramSendGate.BURST_CAPACITY; i++) {
            assertThat(gate.tryAdmit(CHAT_ID)).isEmpty();
            gate.release();
        }

        assertThat(gate.tryAdmit(CHAT_ID)).contains(TelegramSendGate.Refusal.RATE_LIMITED);

        assertThat(slots.availablePermits()).isEqualTo(TelegramSendGate.MAX_CONCURRENT_SENDS);
    }

    @Test
    @DisplayName("slot released after a failed send too")
    void should_releaseSlot_when_sendFails() {
        Semaphore slots = new Semaphore(TelegramSendGate.MAX_CONCURRENT_SENDS);
        TelegramBotApiClient capped = newClient(Duration.ofSeconds(4), slots);
        stub(500, error(500, "Internal Server Error"));

        catchThrowable(() -> capped.send(CHAT_ID, TEXT));

        assertThat(slots.availablePermits()).isEqualTo(TelegramSendGate.MAX_CONCURRENT_SENDS);
    }

    // ── QA 404: per-chat back-off merge (security LOW) ────────────────────

    @Test
    @DisplayName("back-off 30 s then a later 5 s for the same chat → the 30 s block survives (still BACKOFF at +6 s)")
    void should_keepLongerBackoff_when_shorterRetryAfterArrivesLater() {
        TelegramSendGate gate = new TelegramSendGate(clock);
        gate.backOff(CHAT_ID, 30);
        gate.backOff(CHAT_ID, 5);

        clock.advance(Duration.ofSeconds(6));

        assertThat(gate.tryAdmit(CHAT_ID)).contains(TelegramSendGate.Refusal.BACKOFF);
    }

    @Test
    @DisplayName("back-off 30 s then 5 s → admitted once the longer 30 s block has elapsed")
    void should_admit_when_longerBackoffElapsed() {
        TelegramSendGate gate = new TelegramSendGate(clock);
        gate.backOff(CHAT_ID, 30);
        gate.backOff(CHAT_ID, 5);

        clock.advance(Duration.ofSeconds(30));

        assertThat(gate.tryAdmit(CHAT_ID)).isEmpty();
    }

    @Test
    @DisplayName("back-off 5 s then a later 30 s for the same chat → extended to 30 s")
    void should_extendBackoff_when_longerRetryAfterArrivesLater() {
        TelegramSendGate gate = new TelegramSendGate(clock);
        gate.backOff(CHAT_ID, 5);
        gate.backOff(CHAT_ID, 30);

        clock.advance(Duration.ofSeconds(29));

        assertThat(gate.tryAdmit(CHAT_ID)).contains(TelegramSendGate.Refusal.BACKOFF);
    }

    // ── QA 404: remaining D2 / D3 edges ────────────────────────────────────

    @Test
    @DisplayName("401 Unauthorized (revoked token) → transient, NOT gone — the recipient is fine, our bot is not")
    void should_throwTransientNotGone_when_unauthorized() {
        stub(401, error(401, "Unauthorized"));

        Throwable thrown = catchThrowable(() -> client.send(CHAT_ID, TEXT));

        assertThat(thrown).isExactlyInstanceOf(TelegramDeliveryException.class).hasMessageContaining("401");
        assertNoSecrets(thrown);
    }

    @Test
    @DisplayName("404 (wrong bot path) → transient")
    void should_throwTransient_when_notFound() {
        stub(404, error(404, "Not Found"));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT))
                .isExactlyInstanceOf(TelegramDeliveryException.class).hasMessageContaining("404");
    }

    @Test
    @DisplayName("403 with an empty body → gone (status alone decides, any description)")
    void should_throwGone_when_forbiddenWithoutBody() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT))
                .isInstanceOf(TelegramRecipientGoneException.class).hasMessageContaining("no description");
    }

    @Test
    @DisplayName("400 'Chat Not Found' in other casing → gone (match is case-insensitive)")
    void should_throwGone_when_chatNotFoundDifferentCase() {
        stub(400, error(400, "Bad Request: Chat Not Found"));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT)).isInstanceOf(TelegramRecipientGoneException.class);
    }

    @Test
    @DisplayName("502 with a proxy HTML body (not JSON) → transient")
    void should_throwTransient_when_badGatewayHtml() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(502)
                .withHeader("Content-Type", "text/html").withBody("<html><body>Bad Gateway</body></html>")));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT))
                .isExactlyInstanceOf(TelegramDeliveryException.class).hasMessageContaining("502");
    }

    @Test
    @DisplayName("200 with an empty body → transient (ok:true is required)")
    void should_throwTransient_when_successBodyEmpty() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.send(CHAT_ID, TEXT)).isExactlyInstanceOf(TelegramDeliveryException.class);
    }

    @Test
    @DisplayName("connection refused (I/O) → transient, no cause chained, no token/URL in the message")
    void should_throwTransientWithoutUrl_when_connectionRefused() {
        TelegramBotApiClient unreachable = newClient(Duration.ofSeconds(4));
        wireMock.stop();

        Throwable thrown = catchThrowable(() -> unreachable.send(CHAT_ID, TEXT));

        assertThat(thrown).isExactlyInstanceOf(TelegramDeliveryException.class).hasNoCause()
                .hasMessageStartingWith("Telegram send failed: ").hasMessageNotContaining("/bot");
        assertNoSecrets(thrown);
    }

    @Test
    @DisplayName("null or blank text → IllegalArgumentException, no request and no limiter token spent")
    void should_rejectWithoutRequest_when_textNullOrBlank() {
        for (String bad : new String[]{null, "", " \n\t "}) {
            assertThatThrownBy(() -> client.send(CHAT_ID, bad)).isInstanceOf(IllegalArgumentException.class);
        }
        stub(200, OK_BODY);

        for (int i = 0; i < TelegramSendGate.BURST_CAPACITY; i++) {
            client.send(CHAT_ID, TEXT);
        }

        wireMock.verify(TelegramSendGate.BURST_CAPACITY, postRequestedFor(urlEqualTo(PATH)));
    }

    // ── D5: log capture ────────────────────────────────────────────────────

    @Test
    @Timeout(10)
    @DisplayName("no token, chat id or text in any log line — success and every failure path")
    void should_neverLogSecrets_when_anyOutcome() {
        List<Throwable> failures = new ArrayList<>();
        stub(200, OK_BODY);
        client.send(CHAT_ID, TEXT);
        for (int status : new int[]{403, 400, 429, 500, 302}) {
            wireMock.resetAll();
            stub(status, error(status, status == 400 ? "Bad Request: chat not found" : "x"));
            clock.advance(Duration.ofSeconds(1));
            failures.add(catchThrowable(() -> client.send(CHAT_ID, TEXT)));
        }
        wireMock.resetAll();
        stub(200, "{\"ok\":false}");
        clock.advance(Duration.ofSeconds(1));
        failures.add(catchThrowable(() -> client.send(CHAT_ID, TEXT)));
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(2000)));
        failures.add(catchThrowable(() -> newClient(Duration.ofMillis(300)).send(CHAT_ID, TEXT)));
        TelegramBotApiClient exhausted = newClient(Duration.ofSeconds(4));
        wireMock.resetAll();
        stub(200, OK_BODY);
        for (int i = 0; i <= TelegramSendGate.BURST_CAPACITY; i++) {
            Throwable t = catchThrowable(() -> exhausted.send(CHAT_ID, TEXT));
            if (t != null) {
                failures.add(t);
            }
        }

        assertThat(failures).hasSize(8).allSatisfy(TelegramBotApiClientTest::assertNoSecrets);
        // The stub server's own loggers (Jetty, WireMock) print the request line it RECEIVED — that is the fake
        // Telegram, not our process' output, so it is excluded; every other logger in the JVM is scanned.
        List<ILoggingEvent> ours = logAppender.list.stream()
                .filter(e -> !e.getLoggerName().startsWith("org.eclipse.jetty")
                        && !e.getLoggerName().startsWith("wiremock")
                        && !e.getLoggerName().startsWith("com.github.tomakehurst"))
                .toList();
        assertThat(ours).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(TOKEN, Long.toString(CHAT_ID), TEXT);
            for (IThrowableProxy p = event.getThrowableProxy(); p != null; p = p.getCause()) {
                assertThat(String.valueOf(p.getMessage())).doesNotContain(TOKEN, Long.toString(CHAT_ID), TEXT);
            }
        });
        assertThat(ours).extracting(ILoggingEvent::getFormattedMessage)
                .filteredOn(m -> m.startsWith("event=telegram_send"))
                .contains("event=telegram_send outcome=ok status=200",
                        "event=telegram_send outcome=gone status=403",
                        "event=telegram_send outcome=transient status=429");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private TelegramBotApiClient newClient(Duration readTimeout) {
        return newClient(readTimeout, new Semaphore(TelegramSendGate.MAX_CONCURRENT_SENDS));
    }

    private TelegramBotApiClient newClient(Duration readTimeout, Semaphore slots) {
        TelegramProperties properties = new TelegramProperties(false, TelegramMode.LIVE, TOKEN, "beautica_app_bot",
                "a".repeat(64), wireMock.baseUrl(), Duration.ofMinutes(30));
        return new TelegramBotApiClient(properties, clock, readTimeout, slots);
    }

    private void stub(int status, String body) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static String rateLimited(String retryAfterJson) {
        return "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\","
                + "\"parameters\":{\"retry_after\":" + retryAfterJson + "}}";
    }

    private static String error(int code, String description) {
        return "{\"ok\":false,\"error_code\":" + code + ",\"description\":\"" + description + "\"}";
    }

    private static void assertNoSecrets(Throwable thrown) {
        assertThat(thrown).isNotNull();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(TOKEN, Long.toString(CHAT_ID), TEXT);
        }
    }

    /** Frozen unless {@link #advance} is called, so the limiter never refills by accident. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
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
