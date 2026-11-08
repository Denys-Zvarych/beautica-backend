package com.beautica.notification.mail;

import com.beautica.common.observability.ExternalCallFailure;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMailMessage;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * {@link JavaMailSender} over the Brevo transactional API (Phase 374, D1/D3/D5/D10): converts each fully built
 * {@link MimeMessage} with {@link MimeToBrevoPayloadMapper} and POSTs it to {@code /v3/smtp/email}. Callers keep
 * calling {@code mailSender.send(MimeMessage)}; only the transport changes.
 *
 * <p><b>One endpoint only (D9a).</b> The prod key is scoped to Transactional email + Account read; this class calls
 * nothing but {@code POST /v3/smtp/email}.
 *
 * <p><b>Error mapping (D5)</b>, by whose fault it is: 5xx, 429, 3xx, network/timeout, 401/402/403 and the
 * account-side codes → {@link BrevoTransientMailException} (retry + breaker); any other 4xx and local conversion
 * failures → {@link BrevoPermanentMailException}.
 *
 * <p><b>Redaction.</b> Exceptions read {@code "Brevo API HTTP <status> code=<whitelisted code>"}. Bodies, recipients
 * and the api-key never reach a log line or an exception. {@code messageId} is logged at DEBUG only.
 *
 * <p><b>Failures are ERROR (phase 441).</b> Every failed {@code POST} — non-2xx, timeout / I/O, unreadable answer —
 * logs ONE {@link ExternalCallFailure} line ({@code service=brevo-api op=send}). A non-2xx carries the whitelisted
 * {@code code} as {@code reason} and Brevo's {@code message} as {@code detail}, passed through
 * {@link com.beautica.common.observability.PiiRedactor} (it can echo the recipient address) and capped; the message
 * still never enters an exception or {@code last_error}. Callers above log WARN only. A local MIME conversion
 * failure is not an external call and stays WARN. Not a component: {@link MailTransportConfig} registers it
 * only when {@code app.mail.transport=brevo-api}.
 */
@Slf4j
public class BrevoApiMailSender implements JavaMailSender {

    static final String SEND_PATH = "/v3/smtp/email";
    static final String API_KEY_HEADER = "api-key";
    static final String METRIC = "notification.mail.brevo_api.requests";
    private static final int MAX_ERROR_BODY_BYTES = 4096;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int PAYMENT_REQUIRED = 402;
    /** Not a Brevo code, so {@link BrevoErrorCode#parse} maps it to {@link BrevoErrorCode#OTHER}. */
    private static final String UNREADABLE_CODE = "unreadable";
    private static final ObjectMapper ERROR_READER = new ObjectMapper();

    private final RestClient restClient;
    /** Same HTTP client, longer read budget: the JDK read timeout includes uploading the attachments. */
    private final RestClient attachmentRestClient;
    private final BrevoApiProperties properties;
    private final Session session = Session.getInstance(new Properties());
    private final Counter success;
    private final Counter transientFailure;
    private final Counter permanentFailure;

    public BrevoApiMailSender(RestClient.Builder restClientBuilder, BrevoApiProperties properties,
                              MeterRegistry meterRegistry) {
        this.properties = properties;
        java.net.http.HttpClient httpClient = BrevoRestClients.httpClient(
                "app.mail.brevo-api.connect-timeout", properties.connectTimeout());
        this.restClient = BrevoRestClients.build(restClientBuilder, properties.baseUrl(), httpClient,
                "app.mail.brevo-api.read-timeout", properties.readTimeout());
        this.attachmentRestClient = BrevoRestClients.build(restClientBuilder, properties.baseUrl(), httpClient,
                "app.mail.brevo-api.send-with-attachments-read-timeout", properties.sendWithAttachmentsReadTimeout());
        this.success = counter(meterRegistry, "success");
        this.transientFailure = counter(meterRegistry, "transient");
        this.permanentFailure = counter(meterRegistry, "permanent");
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder(METRIC).tag("outcome", outcome)
                .description("Brevo transactional API send attempts by outcome").register(registry);
    }

    @Override
    public MimeMessage createMimeMessage() {
        return new MimeMessage(session);
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) {
        try {
            return new MimeMessage(session, contentStream);
        } catch (MessagingException e) {
            throw new MailParseException("Could not parse raw MIME content", e);
        }
    }

    /** Sequential; stops at the first failure (nothing batches today). */
    @Override
    public void send(MimeMessage... mimeMessages) {
        for (MimeMessage message : mimeMessages) {
            sendOne(message);
        }
    }

    @Override
    public void send(SimpleMailMessage... simpleMessages) {
        for (SimpleMailMessage simple : simpleMessages) {
            MimeMessage mime = createMimeMessage();
            simple.copyTo(new MimeMailMessage(mime));
            sendOne(mime);
        }
    }

    private void sendOne(MimeMessage message) {
        BrevoEmailRequest request;
        try {
            request = MimeToBrevoPayloadMapper.map(message, properties.senderName());
        } catch (BrevoPermanentMailException e) {
            // Local conversion failure: no request was made, so this is not an external-call failure.
            permanentFailure.increment();
            log.warn("Brevo API send failed permanently: {}", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            // Unexpected local mapping failure — same outcome as before phase 441 (permanent), still not a call.
            permanentFailure.increment();
            log.warn("Brevo API send failed permanently: {}", e.getClass().getSimpleName());
            throw new BrevoPermanentMailException("Brevo API client failure " + e.getClass().getSimpleName());
        }
        try {
            SendResponse response = post(request);
            success.increment();
            log.debug("Brevo API accepted messageId={}", response == null ? null : response.messageId());
        } catch (BrevoPermanentMailException e) {
            // Already logged at ERROR by the status handler, with Brevo's redacted message.
            permanentFailure.increment();
            throw e;
        } catch (BrevoTransientMailException e) {
            transientFailure.increment();
            throw e;
        } catch (RuntimeException e) {
            // The URL carries no secret (api-key header) and RestClient never echoes the request body.
            failure().reason(ExternalCallFailure.transportReason(e)).detail(rootCauseName(e)).log(log, e);
            if (BrevoRestClients.isTransportFailure(e)) {
                // Network / timeout, however the JDK client surfaces it (ResourceAccessException, or a bare
                // CancellationException when the read timeout cancels the response future) — provider-side.
                transientFailure.increment();
                throw new BrevoTransientMailException("Brevo API I/O failure " + rootCauseName(e));
            }
            // Serialization / unexpected client error — local, not evidence the provider is down.
            permanentFailure.increment();
            throw new BrevoPermanentMailException("Brevo API client failure " + e.getClass().getSimpleName());
        }
    }

    private static ExternalCallFailure failure() {
        return ExternalCallFailure.of("brevo-api", "send");
    }

    /** Plain sends keep the 10 s budget; only a send carrying attachments gets the longer one. */
    RestClient clientFor(BrevoEmailRequest request) {
        return request.attachment() == null || request.attachment().isEmpty() ? restClient : attachmentRestClient;
    }

    private SendResponse post(BrevoEmailRequest request) {
        return clientFor(request).post()
                .uri(SEND_PATH)
                .header(API_KEY_HEADER, properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                    ErrorBody error = readError(res);
                    BrevoErrorCode code = BrevoErrorCode.parse(error.code());
                    failure().http(res.getStatusCode().value()).reason(code.token())
                            .detail(error.messageText()).log(log);
                    throw classify(res.getStatusCode(), code);
                })
                .body(SendResponse.class);
    }

    /** D5 table. Package-private for the probe and tests. */
    static RuntimeException classify(HttpStatusCode status, BrevoErrorCode code) {
        int value = status.value();
        String message = "Brevo API HTTP " + value + " code=" + code.token();
        boolean systemSide = status.is5xxServerError() || status.is3xxRedirection() || value == TOO_MANY_REQUESTS
                || value == 401 || value == PAYMENT_REQUIRED || value == 403 || code.systemSide();
        return systemSide ? new BrevoTransientMailException(message) : new BrevoPermanentMailException(message);
    }

    /**
     * Reads {@code code} and {@code message} from a size-capped body. {@code code} is mapped through the
     * {@link BrevoErrorCode} whitelist; {@code message} only ever reaches the redacted log {@code detail}.
     * An empty body yields a {@code null} code ({@link BrevoErrorCode#NONE}); an unreadable one {@code "unreadable"}
     * ({@link BrevoErrorCode#OTHER}).
     */
    static ErrorBody readError(ClientHttpResponse response) {
        try (InputStream body = response.getBody()) {
            byte[] bytes = body.readNBytes(MAX_ERROR_BODY_BYTES);
            if (bytes.length == 0) {
                return new ErrorBody(null, null);
            }
            return ERROR_READER.readValue(bytes, ErrorBody.class);
        } catch (IOException | RuntimeException e) {
            return new ErrorBody(UNREADABLE_CODE, null);
        }
    }

    private static String rootCauseName(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    /** {@code code} (whitelisted) and {@code message} (redacted log detail only); everything else is ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String code, JsonNode message) {

        /**
         * The message when it is a JSON scalar (decoded text). An object/array is never echoed — only its length
         * (audit-fix cycle 1, security: raw JSON can carry escaped secrets the redactor cannot see).
         */
        String messageText() {
            if (message == null || message.isNull()) {
                return null;
            }
            return message.isValueNode() ? message.asText() : "unparsed(len=" + message.toString().length() + ")";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendResponse(String messageId) {
    }
}
