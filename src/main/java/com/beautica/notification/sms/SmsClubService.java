package com.beautica.notification.sms;

import com.beautica.common.observability.ExternalCallFailure;
import com.beautica.config.OutboundTimeouts;
import com.beautica.config.SmsClubProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
 * <pre>{"phone":["380XXXXXXXXX"],"src_addr":"Beauty","message":"...","lifetime":60}</pre>
 * {@code src_addr} is the account's registered alpha-name and is case-sensitive ({@code BEAUTY} is rejected).
 * Business errors arrive as HTTP 200, so the body is parsed: an {@code info} entry for the sent number is success,
 * an {@code add_info} entry is a definitive rejection mapped to a fixed {@link Reason} token, anything else is an
 * ambiguous outcome (the provider may have sent it).
 *
 * <p><b>Logging discipline (phase 441).</b> Every failed attempt — non-2xx, an {@code add_info} rejection in a 2xx,
 * an unreadable verdict, a timeout or I/O error, a missing token — is ONE {@link ExternalCallFailure} ERROR line
 * ({@code service=smsclub}) so it reaches Sentry; callers above only re-map and log at WARN. The provider's reason
 * text ({@code add_info} / error body) is logged as {@code detail} with the recipient digits and the message text
 * removed, then {@link com.beautica.common.observability.PiiRedactor redacted}, digit runs masked and capped. The
 * token, the message text, the OTP code and the sms id are never logged nor put into an exception message.
 *
 * <p><b>Not a {@code @Service}</b>: registered by {@code SmsConfig} like {@link TurbosmsService}, and built with the
 * same JDK client — HTTP/1.1, redirects never followed, explicit timeouts, no retries — so a billable send is never
 * repeated by infrastructure (see {@link TurbosmsService}'s constructor for the full rationale).
 */
@Slf4j
public class SmsClubService implements SmsService {

    private static final String PROVIDER = "smsclub";
    private static final String OP_SEND = "send";
    private static final String REASON_NOT_CONFIGURED = "not_configured";
    private static final String REASON_AUTH = "auth";
    private static final int MAX_ERROR_BODY_BYTES = 4096;
    /** 4–8 digit runs (an OTP code, a partial number) are masked in provider text on top of {@code PiiRedactor}. */
    private static final Pattern SHORT_DIGIT_RUN = Pattern.compile("\\d{4,8}");
    private static final List<String> ERROR_TEXT_FIELDS = List.of("message", "error", "detail", "description");
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
            failure(to).reason(REASON_NOT_CONFIGURED).log(log);
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
        interpret(post(digits, text, to), digits, text, to);
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
                        throw rejectedWithStatus(res.getStatusCode().value(), readErrorBody(res), digits, text, to);
                    })
                    .body(String.class);
        } catch (SmsDeliveryException e) {
            throw e;
        } catch (RuntimeException e) {
            // The URL carries no secret (Bearer header) and the body is never echoed by RestClient's I/O exceptions.
            failure(to).reason(ExternalCallFailure.transportReason(e))
                    .detail(ExternalCallFailure.rootCauseName(e)).log(log, e);
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

    private static ExternalCallFailure failure(String to) {
        return ExternalCallFailure.of(PROVIDER, OP_SEND).to(to);
    }

    /**
     * A non-2xx is a definite refusal. The provider's reason text (an {@code add_info} entry or an error field) is
     * logged as a sanitised {@code detail}; the exception message carries the status only.
     */
    private SmsDeliveryException rejectedWithStatus(int code, String errorBody, String digits, String text,
                                                         String to) {
        String providerText = providerErrorText(errorBody, digits);
        String reason;
        if (code == HttpStatus.UNAUTHORIZED.value()) {
            reason = REASON_AUTH;
        } else if (providerText.isEmpty()) {
            reason = "http_" + code;
        } else {
            reason = Reason.of(providerText).name();
        }
        String detail = providerText.isEmpty() ? unparsedDetail(errorBody) : scrub(providerText, digits, text);
        failure(to).http(code).reason(reason).detail(detail).log(log);
        return new SmsDeliveryException("SMSClub rejected the request with HTTP " + code);
    }

    /**
     * Audit-fix cycle 1 (security): a body in an unexpected shape is NEVER logged, not even scrubbed — JSON escaping
     * (escaped slashes, unicode escapes) defeats the replace-based removal, so a guest cancel link token could survive.
     * Only its length is recorded.
     */
    private static String unparsedDetail(String errorBody) {
        return errorBody == null || errorBody.isEmpty() ? "" : "unparsed(len=" + errorBody.length() + ")";
    }

    /** At most {@value #MAX_ERROR_BODY_BYTES} bytes of the error body; empty when it cannot be read. */
    private static String readErrorBody(ClientHttpResponse response) {
        try (InputStream body = response.getBody()) {
            return new String(body.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** The {@code add_info} entry, else the first known error field, of a JSON error body; empty when neither. */
    private static String providerErrorText(String errorBody, String digits) {
        if (errorBody == null || errorBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(errorBody);
            JsonNode addInfo = root.path("success_request").path("add_info");
            if (addInfo.isObject() && !addInfo.isEmpty()) {
                return rejectionText(addInfo, digits);
            }
            for (String field : ERROR_TEXT_FIELDS) {
                JsonNode value = root.path(field);
                if (value.isValueNode() && !value.asText("").isBlank()) {
                    return value.asText();
                }
            }
            return "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    /**
     * Provider text made log-safe before {@link ExternalCallFailure#sanitizeDetail}: the recipient digits and any echo
     * of the message text and the API token are removed, and 4–8 digit runs (an OTP code, a partial number) are masked.
     */
    private String scrub(String providerText, String digits, String text) {
        if (providerText == null || providerText.isBlank()) {
            return "";
        }
        String out = providerText;
        if (token != null && !token.isBlank()) {
            out = out.replace(token, "[token]");
        }
        if (text != null && !text.isBlank()) {
            out = out.replace(text, "[message]");
        }
        if (digits != null && !digits.isBlank()) {
            out = out.replace(digits, "[phone]");
        }
        return SHORT_DIGIT_RUN.matcher(out).replaceAll("[digits]");
    }

    /** Success returns; a definitive rejection or an unreadable verdict throws. */
    private void interpret(String responseBody, String digits, String text, String to) {
        JsonNode request = parse(responseBody, to).path("success_request");
        if (containsValue(request.path("info"), digits)) {
            return;
        }
        JsonNode addInfo = request.path("add_info");
        if (addInfo.isObject() && !addInfo.isEmpty()) {
            String providerText = rejectionText(addInfo, digits);
            Reason reason = Reason.of(providerText);
            failure(to).http(HttpStatus.OK.value()).reason(reason.name())
                    .detail(scrub(providerText, digits, text)).log(log);
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

    /** A 2xx whose verdict cannot be read: the provider may have sent it, so it is UNKNOWN (never retried). */
    private static SmsDeliveryAmbiguousException ambiguous(String to, Throwable cause) {
        failure(to).http(HttpStatus.OK.value()).reason(ExternalCallFailure.UNREADABLE_RESPONSE)
                .detail("outcome unknown").log(log);
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
