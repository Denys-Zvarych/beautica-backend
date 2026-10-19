package com.beautica.config;

import com.beautica.notification.mail.BrevoApiProperties;
import com.beautica.notification.mail.BrevoRestClients;
import com.beautica.notification.mail.MailTransportCondition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Prod boot probe for the Brevo HTTPS API transport (Phase 374, D9). One {@code GET /v3/account} — the only
 * endpoint the Account=Read scope of the prod key covers (D9a) — with its own 3 s connect / 3 s read timeouts,
 * never the send timeouts. Brevo limits {@code /v3/account} to 100 requests/hour, so the result is computed once
 * and cached; it is never scheduled. WARN only, never fatal: a Brevo outage must not take the API down.
 *
 * <p>Logs the status or the exception class only — never the key, never a response body.
 */
@Slf4j
@Component
@Conditional(MailTransportCondition.OnBrevoApi.class)
public class BrevoApiConnectionProbe {

    static final String ACCOUNT_PATH = "/v3/account";
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    private static final String API_KEY_HEADER = "api-key";

    /** Outcome shown on the banner. */
    public enum Result { OK, REJECTED, UNREACHABLE }

    private final BrevoApiProperties properties;
    private final RestClient restClient;
    private Result cached;

    public BrevoApiConnectionProbe(BrevoApiProperties properties, ObjectProvider<RestClient.Builder> builder) {
        this.properties = properties;
        this.restClient = BrevoRestClients.build(builder.getIfAvailable(RestClient::builder), properties.baseUrl(),
                "brevo-api probe connect-timeout", PROBE_TIMEOUT, "brevo-api probe read-timeout", PROBE_TIMEOUT);
    }

    /** @return the probe outcome; the HTTP call is made at most once per application lifetime */
    public synchronized Result result() {
        if (cached == null) {
            cached = probe();
        }
        return cached;
    }

    private Result probe() {
        if (!properties.hasApiKey()) {
            log.warn("rule prod-brevo-api-auth-failed: BREVO_API_KEY is not configured");
            return Result.REJECTED;
        }
        try {
            HttpStatusCode status = restClient.get()
                    .uri(ACCOUNT_PATH)
                    .header(API_KEY_HEADER, properties.apiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((req, res) -> res.getStatusCode(), true);
            return classify(status);
        } catch (RuntimeException e) {
            // Network/timeout (incl. a bare CancellationException from the JDK client's read timeout) and any
            // other client failure are both UNREACHABLE — never REJECTED, which is reserved for an HTTP answer.
            if (BrevoRestClients.isTransportFailure(e)) {
                log.warn("rule prod-brevo-api-unreachable: the Brevo API did not answer: {}", rootCauseName(e));
            } else {
                log.warn("rule prod-brevo-api-unreachable: the Brevo API probe failed: {}", e.getClass().getSimpleName());
            }
            return Result.UNREACHABLE;
        }
    }

    private static Result classify(HttpStatusCode status) {
        int value = status.value();
        if (status.is2xxSuccessful()) {
            return Result.OK;
        }
        if (value == 400 || value == 401 || value == 403) {
            log.warn("rule prod-brevo-api-auth-failed: the Brevo API rejected the key: HTTP {}", value);
            return Result.REJECTED;
        }
        log.warn("rule prod-brevo-api-unreachable: the Brevo API answered HTTP {}", value);
        return Result.UNREACHABLE;
    }

    private static String rootCauseName(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
