package com.beautica.notification.sms;

import com.beautica.config.OutboundTimeouts;
import com.beautica.config.SmsClubProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SMSClub ({@code im.smsclub.mobi}) implementation of {@link SmsService} (Phase 439), selected by
 * {@code beautica.sms.mode=smsclub}.
 *
 * <p>Sends {@code POST {baseUrl}} with {@code Authorization: Bearer {token}} and body
 * <pre>{"phone":["380XXXXXXXXX"],"src_addr":"BEAUTY","message":"...","lifetime":60}</pre>
 * Business errors arrive as HTTP 200, so the body is parsed: an {@code info} entry for the sent number is success,
 * an {@code add_info} entry is a definitive rejection mapped to a fixed {@link Reason} token, anything else is an
 * ambiguous outcome (the provider may have sent it).
 *
 * <p><b>Logging discipline.</b> Only status, provider, the masked recipient, the reason token and the HTTP code are
 * logged. The token, the message text, the sms id and the raw {@code add_info} text (it can echo the phone) are never
 * logged nor put into an exception message.
 *
 * <p><b>Not a {@code @Service}</b>: registered by {@code SmsConfig} like {@link TurbosmsService}, and built with the
 * same JDK client — HTTP/1.1, redirects never followed, explicit timeouts, no retries — so a billable send is never
 * repeated by infrastructure (see {@link TurbosmsService}'s constructor for the full rationale).
 */
@Slf4j
public class SmsClubService implements SmsService {

    private static final String PROVIDER = "smsclub";
    private static final Pattern UA_MOBILE_E164 = Pattern.compile("^\\+380\\d{9}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final String baseUrl;
    private final String token;
    private final String senderName;
    private final Integer lifetimeMinutes;
    /** {@code null} outside staging — no cap in prod, and capture mode never builds this service. */
    private final StagingSmsDailyCap stagingDailyCap;

    /**
     * @param lifetimeMinutes provider {@code lifetime} in minutes; {@code null} omits the field, otherwise positive
     */
    public SmsClubService(RestClient.Builder restClientBuilder, SmsClubProperties properties, Integer lifetimeMinutes) {
        this(restClientBuilder, properties, lifetimeMinutes, null);
    }

    /**
     * @param stagingDailyCap staging-only daily send ceiling (Phase 439 audit-fix); {@code null} means uncapped
     */
    public SmsClubService(RestClient.Builder restClientBuilder, SmsClubProperties properties, Integer lifetimeMinutes,
                          StagingSmsDailyCap stagingDailyCap) {
        if (lifetimeMinutes != null && lifetimeMinutes <= 0) {
            throw new IllegalStateException("app.sms.smsclub lifetime must be positive");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(OutboundTimeouts.requirePositive(
                        "app.sms.smsclub.connect-timeout", properties.getConnectTimeout()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(OutboundTimeouts.requirePositive(
                "app.sms.smsclub.read-timeout", properties.getReadTimeout()));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.baseUrl = properties.getBaseUrl();
        this.token = properties.getToken();
        this.senderName = properties.getSenderName();
        this.lifetimeMinutes = lifetimeMinutes;
        this.stagingDailyCap = stagingDailyCap;
    }

    @Override
    public void send(String phoneE164, String text) {
        String to = PhoneMask.mask(phoneE164);
        if (token == null || token.isBlank()) {
            log.warn("SMS send status=FAILED provider={} to={} (provider token not configured)", PROVIDER, to);
            throw new SmsDeliveryException("SMS provider is not configured");
        }
        if (phoneE164 == null || !UA_MOBILE_E164.matcher(phoneE164).matches()) {
            log.warn("SMS send status=FAILED provider={} to={} reason=RECIPIENT_NOT_UA_MOBILE", PROVIDER, to);
            throw new SmsDeliveryException("SMS recipient is not a UA mobile");
        }
        if (stagingDailyCap != null && !stagingDailyCap.tryAcquire()) {
            log.warn("SMS send status=FAILED provider={} to={} reason={}", PROVIDER, to, StagingSmsDailyCap.REASON);
            throw new SmsDeliveryException("SMS send refused reason=" + StagingSmsDailyCap.REASON);
        }
        String digits = phoneE164.substring(1);
        interpret(post(digits, text, to), digits, to);
        log.info("SMS send status=OK provider={} to={}", PROVIDER, to);
    }

    private String post(String digits, String text, String to) {
        try {
            return restClient.post()
                    .uri(baseUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody(digits, text))
                    .retrieve()
                    // Any non-2xx is a definite refusal. The body is never read into the message.
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw rejectedWithStatus(res.getStatusCode().value(), to);
                    })
                    .body(String.class);
        } catch (SmsDeliveryException e) {
            throw e;
        } catch (RuntimeException e) {
            // Exception type only — never its message (may carry the URL or payload fragments).
            log.warn("SMS send status=FAILED provider={} to={} cause={}", PROVIDER, to, e.getClass().getSimpleName());
            if (SmsTransportErrors.isOutcomeUnknownOrCancelled(e)) {
                throw new SmsDeliveryAmbiguousException("SMS delivery outcome unknown", e);
            }
            throw new SmsDeliveryException("SMS delivery failed", e);
        }
    }

    private Map<String, Object> requestBody(String digits, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", List.of(digits));
        body.put("src_addr", senderName);
        body.put("message", text);
        if (lifetimeMinutes != null) {
            body.put("lifetime", lifetimeMinutes);
        }
        return body;
    }

    private static SmsDeliveryException rejectedWithStatus(int code, String to) {
        if (code == HttpStatus.UNAUTHORIZED.value()) {
            // A wrong / revoked token is an ops alarm, not a per-message failure.
            log.error("SMS send status=FAILED provider={} to={} http={} reason=AUTH", PROVIDER, to, code);
        } else {
            log.warn("SMS send status=FAILED provider={} to={} http={}", PROVIDER, to, code);
        }
        return new SmsDeliveryException("SMSClub rejected the request with HTTP " + code);
    }

    /** Success returns; a definitive rejection or an unreadable verdict throws. */
    private static void interpret(String responseBody, String digits, String to) {
        JsonNode request = parse(responseBody, to).path("success_request");
        if (containsValue(request.path("info"), digits)) {
            return;
        }
        JsonNode addInfo = request.path("add_info");
        if (addInfo.isObject() && !addInfo.isEmpty()) {
            Reason reason = Reason.of(rejectionText(addInfo, digits));
            log.warn("SMS send status=FAILED provider={} to={} reason={}", PROVIDER, to, reason);
            throw new SmsDeliveryException("SMSClub rejected the message reason=" + reason);
        }
        throw ambiguous(to, null);
    }

    private static JsonNode parse(String responseBody, String to) {
        if (responseBody == null || responseBody.isBlank()) {
            throw ambiguous(to, null);
        }
        try {
            return JSON.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw ambiguous(to, e);
        }
    }

    private static SmsDeliveryAmbiguousException ambiguous(String to, Throwable cause) {
        log.warn("SMS send status=UNKNOWN provider={} to={} reason=UNREADABLE_RESPONSE", PROVIDER, to);
        return new SmsDeliveryAmbiguousException("SMS delivery outcome unknown", cause);
    }

    private static boolean containsValue(JsonNode info, String digits) {
        if (!info.isObject()) {
            return false;
        }
        for (Iterator<JsonNode> it = info.elements(); it.hasNext(); ) {
            if (digits.equals(it.next().asText())) {
                return true;
            }
        }
        return false;
    }

    /** The entry for the sent number if present, else the first one (a request-level reject such as {@code src_addr}). */
    private static String rejectionText(JsonNode addInfo, String digits) {
        JsonNode own = addInfo.get(digits);
        return (own != null ? own : addInfo.elements().next()).asText("");
    }

    /** Fixed reason tokens for the provider's {@code add_info} texts; the raw text never leaves this class. */
    enum Reason {
        PHONE_INVALID("incorrect phone number", "phone number cannot be blank", "incorrect phones array"),
        SENDER_INVALID("sender name cannot be blank", "sender name is incorrect", "sender name has state"),
        MESSAGE_BLANK("message cannot be blank"),
        BLACKLISTED("this number is in the black list"),
        NO_FUNDS("not enough money"),
        OTHER();

        private final List<String> prefixes;

        Reason(String... prefixes) {
            this.prefixes = List.of(prefixes);
        }

        static Reason of(String providerText) {
            String normalised = providerText.strip().toLowerCase(Locale.ROOT);
            for (Reason reason : values()) {
                if (reason.prefixes.stream().anyMatch(normalised::startsWith)) {
                    return reason;
                }
            }
            return OTHER;
        }
    }
}
