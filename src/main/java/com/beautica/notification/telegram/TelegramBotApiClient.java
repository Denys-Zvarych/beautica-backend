package com.beautica.notification.telegram;

import com.beautica.config.TelegramProperties;
import com.beautica.notification.mail.BrevoRestClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Live Bot API {@link TelegramMessenger} (Phase 404, D2–D5): {@code POST {apiBaseUrl}/bot<token>/sendMessage} with
 * {@code {chat_id, text, link_preview_options:{is_disabled:true}}} — plain text, no {@code parse_mode}, so template
 * values never need escaping.
 *
 * <h2>Transport (D3)</h2>
 * Built through {@link BrevoRestClients} — the same rules as {@code TurbosmsService}: JDK client named explicitly
 * (never the test-only Apache HC5, which retries 429/503), HTTP/1.1 pinned, redirects never followed, connect 2 s /
 * read 4 s so the router's SMS fallback is fast. No retry: a failure is reported once and the router moves on. At most
 * {@value #MAX_RESPONSE_BYTES} bytes of a response are read; a larger body is a transient failure.
 *
 * <h2>Token never leaves this class (D5)</h2>
 * The token sits in the URL path, so the {@link RestClient} starts from a bare {@link RestClient#builder()}: none of
 * Boot's customizers (Sentry's span interceptor records the URL), no observation registry (its {@code http.url}
 * key value is the full URL), no logging interceptor. Exceptions carry status + Telegram {@code description} only
 * and chain no cause (Spring's I/O exception message embeds the URL). Log lines are exactly
 * {@code event=telegram_send outcome=<ok|gone|transient> status=<http|none>} — never chat id, never text.
 *
 * <h2>Before any request (D4 + audit)</h2>
 * {@link TelegramSendGate}: per-chat 429 back-off, 30-per-second-window global rate limit, {@value TelegramSendGate#MAX_CONCURRENT_SENDS}
 * concurrent requests. A refusal is {@link TelegramDeliveryException} with no HTTP call.
 *
 * <p>Not a {@code @Component}: registered by {@code config/TelegramConfig} only when enabled + live.
 */
@Slf4j
public class TelegramBotApiClient implements TelegramMessenger {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(4);
    static final int MAX_DESCRIPTION_CHARS = 200;
    static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHAT_NOT_FOUND = "chat not found";
    private static final String NO_STATUS = "none";
    private static final int TOO_MANY_REQUESTS = 429;
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}+");

    private final RestClient restClient;
    /** {@code /bot<token>/sendMessage}, built once; the token is format-checked so it cannot alter the path. */
    private final String sendMessagePath;
    private final TelegramSendGate gate;

    public TelegramBotApiClient(TelegramProperties properties, Clock clock) {
        this(properties, new TelegramSendGate(clock), READ_TIMEOUT);
    }

    /** Test seam: a shorter read timeout and a caller-owned concurrency semaphore. */
    TelegramBotApiClient(TelegramProperties properties, Clock clock, Duration readTimeout, Semaphore inFlight) {
        this(properties, new TelegramSendGate(clock, inFlight), readTimeout);
    }

    private TelegramBotApiClient(TelegramProperties properties, TelegramSendGate gate, Duration readTimeout) {
        if (!TelegramProperties.BOT_TOKEN.matcher(properties.botToken()).matches()) {
            throw new IllegalStateException("TelegramBotApiClient requires a well-formed app.telegram.bot-token");
        }
        this.restClient = BrevoRestClients.build(RestClient.builder(), properties.apiBaseUrl(),
                BrevoRestClients.httpClient("app.telegram connect timeout", CONNECT_TIMEOUT),
                "app.telegram read timeout", readTimeout);
        // Literal path, not a {token} URI variable: strict variable encoding would turn ':' into %3A.
        this.sendMessagePath = "/bot" + properties.botToken() + "/sendMessage";
        this.gate = gate;
    }

    @Override
    public void send(long chatId, String text) {
        TelegramMessenger.requireSendable(text);
        Optional<TelegramSendGate.Refusal> refusal = gate.tryAdmit(chatId);
        if (refusal.isPresent()) {
            String cause = refusal.get().name().toLowerCase(Locale.ROOT);
            log.warn("event=telegram_send outcome=transient status={} cause={}", NO_STATUS, cause);
            throw new TelegramDeliveryException("Telegram send refused before request: " + cause);
        }
        try {
            post(chatId, text);
        } finally {
            gate.release();
        }
    }

    private void post(long chatId, String text) {
        Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "text", text,
                "link_preview_options", Map.of("is_disabled", true));
        try {
            restClient.post()
                    .uri(sendMessagePath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        classify(chatId, response.getStatusCode().value(), readBounded(response.getBody()));
                        return null;
                    });
        } catch (TelegramRecipientGoneException | TelegramDeliveryException e) {
            throw e;
        } catch (RuntimeException e) {
            // Timeout / I/O / serialization. Only the simple class name: the message carries the URL (token).
            log.warn("event=telegram_send outcome=transient status={} cause={}", NO_STATUS,
                    e.getClass().getSimpleName());
            throw new TelegramDeliveryException("Telegram send failed: " + e.getClass().getSimpleName());
        }
    }

    /** D2 mapping. Returns normally only on a 2xx with {@code ok:true}. */
    private void classify(long chatId, int status, JsonNode json) {
        if (status >= 200 && status < 300 && json != null && json.path("ok").asBoolean(false)) {
            log.info("event=telegram_send outcome=ok status={}", status);
            return;
        }
        String description = description(json);
        if (status == 403 || (status == 400 && description.toLowerCase(Locale.ROOT).contains(CHAT_NOT_FOUND))) {
            log.warn("event=telegram_send outcome=gone status={}", status);
            throw new TelegramRecipientGoneException(
                    "Telegram recipient unreachable: HTTP " + status + " (" + description + ")");
        }
        if (status == TOO_MANY_REQUESTS && json != null) {
            // Per chat (audit cycle 2): Telegram also 429s one chat's own flood limit. Only an integral JSON number
            // counts — "5", 1.5 or garbage is ignored rather than coerced.
            JsonNode retryAfter = json.path("parameters").path("retry_after");
            gate.backOff(chatId, retryAfter.isIntegralNumber() ? retryAfter.asLong() : 0);
        }
        log.warn("event=telegram_send outcome=transient status={}", status);
        throw new TelegramDeliveryException("Telegram sendMessage failed: HTTP " + status + " (" + description + ")");
    }

    /**
     * Reads at most {@value #MAX_RESPONSE_BYTES} bytes. {@code null} when the body is empty or not JSON (a proxy's HTML
     * 502, a 3xx) — the status then decides. A larger body is refused outright as transient.
     */
    private static JsonNode readBounded(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            log.warn("event=telegram_send outcome=transient status={} cause=oversized_response", NO_STATUS);
            throw new TelegramDeliveryException("Telegram response exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
        if (bytes.length == 0) {
            return null;
        }
        try {
            return JSON.readTree(bytes);
        } catch (IOException e) {
            return null;
        }
    }

    /** Telegram's {@code description}, single-line and capped; {@code "no description"} when absent. */
    private static String description(JsonNode json) {
        String raw = json == null ? "" : json.path("description").asText("");
        String flat = CONTROL_CHARS.matcher(raw).replaceAll(" ").strip();
        if (flat.isEmpty()) {
            return "no description";
        }
        return flat.length() <= MAX_DESCRIPTION_CHARS ? flat : flat.substring(0, MAX_DESCRIPTION_CHARS);
    }
}
