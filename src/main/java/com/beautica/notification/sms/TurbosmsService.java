package com.beautica.notification.sms;

import com.beautica.config.TurbosmsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Turbosms ({@code api.turbosms.ua}) implementation of {@link SmsService} — the
 * cheapest Ukrainian SMS provider and the shared SMS layer for Phase 13 OTP and
 * notification messages (Phase 13.1).
 *
 * <p>Sends {@code POST {baseUrl}} with {@code Authorization: Bearer {token}} and body
 * <pre>{ "recipients": ["+380..."], "sms": { "sender": "Beautica", "text": "..." } }</pre>
 *
 * <p><b>Logging discipline (security-critical, Anti-Bug §I-3).</b> Only the send
 * status and a masked recipient ({@code +380***XXXX}, last 4 digits) are logged.
 * The message text (may contain an OTP) and the Bearer token are NEVER logged.
 * The masking rule itself moved to {@link PhoneMask} in Phase 22.7 so this class and
 * {@link NoOpSmsService} cannot drift into two different definitions of "masked".
 *
 * <p><b>Not a {@code @Service} since Phase 22.7.</b> This bean is now registered
 * conditionally by {@code SmsConfig}, and only when {@code app.booking.sms.enabled=true};
 * otherwise {@link NoOpSmsService} occupies the {@link SmsService} injection point. Restoring
 * a component-scan annotation here would register a SECOND {@code SmsService} bean and break
 * every injection point at boot. The behaviour below is otherwise unchanged — in particular a
 * blank token still throws, because "the gate is off" and "the credential is missing" are
 * different situations and only the second is a misconfiguration.
 */
@Slf4j
public class TurbosmsService implements SmsService {

    private static final String SUCCESS_STATUS = "OK";
    private static final String FAILURE_STATUS = "FAILED";
    /** Cap the TCP handshake so an unroutable Turbosms host fails fast (Anti-Bug §H / thread-pool starvation). */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    /** Cap the response wait so a hung Turbosms endpoint cannot pin the calling thread indefinitely. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String baseUrl;
    private final String token;
    private final String senderName;

    public TurbosmsService(RestClient.Builder restClientBuilder, TurbosmsProperties properties) {
        // Build the client once (java-skill / Anti-Bug §C analogue: never per-request).
        // Explicit connect/read timeouts: JDK defaults are unbounded, so a hung provider
        // would otherwise hold the request/notification thread until the OS gives up.
        //
        // THE FACTORY IS NAMED EXPLICITLY, NOT LEFT TO ClientHttpRequestFactoryBuilder.detect() —
        // two findings, one change (2026-08-18).
        //
        //  (1) A 503 was billed TWICE. detect() resolves the FIRST client on the classpath, and
        //      `httpclient5` is on the TEST classpath (build.gradle.kts testImplementation) but not
        //      the production one. Apache HC5 installs DefaultHttpRequestRetryStrategy, which treats
        //      429 and 503 as retryable — the two codes an overloaded or rate-limited SMS gateway is
        //      most likely to answer with. Turbosms bills per delivered message and this request
        //      carries no idempotency key, so the silent retry was a duplicate charge AND a
        //      duplicate message to a real client.
        //  (2) Worse, that behaviour was TEST-ONLY. Production, with no HC5 present, already fell
        //      back to the JDK client — so every assertion about retries, and about the
        //      connect/read timeouts above, was verified against a factory production never used.
        //      A defence that only exists under test is not a defence.
        //
        // Naming it fixes both: test and production now agree, the timeouts are verified where they
        // actually run, and the JDK client does not retry a POST (it retries only connection-level
        // failures on idempotent methods, and `jdk.httpclient.enableAllMethodRetry` is off by
        // default). Do NOT restore detect(), and do NOT promote httpclient5 to `implementation`
        // without re-pinning the retry strategy here — a billable send must never be repeated by
        // infrastructure the call site cannot see.
        //
        // HTTP_1_1 is pinned, not left at the JDK default. HttpClient defaults to HTTP_2, which
        // against a cleartext endpoint means an h2c upgrade attempt — and a server that does not
        // speak it (WireMock's Jetty, among others) drops the connection, surfacing as a bare
        // "EOF reached while reading". Turbosms is a plain JSON POST API and HTTP/1.1 is what this
        // client has always spoken, so pinning it changes nothing except removing a negotiation
        // that can only fail.
        //
        // Redirects are NEVER followed. A 3xx would make the client re-issue this exact billable
        // POST at a location the call site never chose — the same "infrastructure repeats a paid
        // send" class as the retry strategy documented above.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.baseUrl = properties.getBaseUrl();
        this.token = properties.getToken();
        this.senderName = properties.getSenderName();
    }

    @Override
    public void send(String phoneE164, String text) {
        if (token == null || token.isBlank()) {
            log.warn("SMS send status={} to={} (provider token not configured)",
                    FAILURE_STATUS, mask(phoneE164));
            throw new SmsDeliveryException("SMS provider is not configured");
        }

        Map<String, Object> body = Map.of(
                "recipients", List.of(phoneE164),
                "sms", Map.of("sender", senderName, "text", text));

        try {
            restClient.post()
                    .uri(baseUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    // 4xx/5xx → SmsDeliveryException. The error body is NOT surfaced to the
                    // message (could echo recipient/text); only the status code is recorded.
                    .onStatus(status -> status.isError(), (req, res) -> {
                        throw new SmsDeliveryException(
                                "Turbosms rejected the request with HTTP " + res.getStatusCode().value());
                    })
                    .toBodilessEntity();
        } catch (SmsDeliveryException e) {
            log.warn("SMS send status={} to={}", FAILURE_STATUS, mask(phoneE164));
            throw e;
        } catch (RuntimeException e) {
            // Transport / serialization failure. Log only the exception type — never the
            // message (may contain the URL with embedded data) or the SMS text/token.
            log.warn("SMS send status={} to={} cause={}",
                    FAILURE_STATUS, mask(phoneE164), e.getClass().getSimpleName());
            throw new SmsDeliveryException("SMS delivery failed", e);
        }

        log.info("SMS send status={} to={}", SUCCESS_STATUS, mask(phoneE164));
    }

    /** @see PhoneMask#mask(String) — the one masking rule, shared with {@link NoOpSmsService}. */
    private static String mask(String phone) {
        return PhoneMask.mask(phone);
    }
}
