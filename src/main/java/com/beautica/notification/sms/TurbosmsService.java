package com.beautica.notification.sms;

import com.beautica.config.TurbosmsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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
 */
@Slf4j
@Service
public class TurbosmsService implements SmsService {

    private static final String SUCCESS_STATUS = "OK";
    private static final String FAILURE_STATUS = "FAILED";
    private static final int VISIBLE_TAIL_DIGITS = 4;
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
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
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

    /**
     * Masks a phone to {@code +380***XXXX} — keeps the leading {@code +380} (when
     * present) and the last {@value #VISIBLE_TAIL_DIGITS} digits, hides the middle.
     * Defensive against null/short input so logging can never NPE.
     */
    private static String mask(String phone) {
        if (phone == null || phone.isBlank()) {
            return "+380***????";
        }
        String tail = phone.length() <= VISIBLE_TAIL_DIGITS
                ? phone
                : phone.substring(phone.length() - VISIBLE_TAIL_DIGITS);
        return "+380***" + tail;
    }
}
