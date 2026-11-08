package com.beautica.config;

import com.beautica.common.observability.ExternalCallFailure;
import com.beautica.notification.mail.BrevoApiProperties;
import com.beautica.notification.mail.BrevoRestClients;
import com.beautica.notification.mail.MailTransportCondition;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Prod boot probe for the Brevo HTTPS API transport (Phase 374, D9). One {@code GET /v3/account} — the only
 * endpoint the Account=Read scope of the prod key covers (D9a) — with its own 3 s connect / 3 s read timeouts,
 * never the send timeouts. Brevo limits {@code /v3/account} to 100 requests/hour, so the result is computed once
 * and cached; it is never scheduled. Never fatal: a Brevo outage must not take the API down.
 *
 * <p>Logs the status or the exception class only — never the key, never a response body. Phase 441: a failed probe
 * call (non-2xx, timeout, I/O) is ONE {@link ExternalCallFailure} ERROR ({@code service=brevo-api op=probe}) with the
 * rule token as {@code detail}; a missing key (no call made) stays WARN.
 */
@Slf4j
@Component
@Conditional(MailTransportCondition.OnBrevoApi.class)
public class BrevoApiConnectionProbe {

    static final String ACCOUNT_PATH = "/v3/account";
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    private static final String API_KEY_HEADER = "api-key";
    private static final String RULE_AUTH_FAILED = "rule prod-brevo-api-auth-failed";
    private static final String RULE_UNREACHABLE = "rule prod-brevo-api-unreachable";

    /** Outcome shown on the banner. */
    public enum Result { OK, REJECTED, UNREACHABLE }

    private final BrevoApiProperties properties;
    private final RestClient.Builder builder;
    private Result cached;

    /** Uses a customizer-free builder (no Sentry interceptor / trace headers to a third party), see BrevoRestClients. */
    @Autowired
    public BrevoApiConnectionProbe(BrevoApiProperties properties, ObjectProvider<ObservationRegistry> observationRegistry) {
        this(properties, BrevoRestClients.cleanBuilder(observationRegistry));
    }

    /** Test seam: an explicit builder (e.g. with a failure-injecting interceptor). */
    BrevoApiConnectionProbe(BrevoApiProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.builder = builder;
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
            log.warn("{}: BREVO_API_KEY is not configured", RULE_AUTH_FAILED);
            return Result.REJECTED;
        }
        // One-shot: the HTTP client lives only for this call and is closed afterwards (no idle pool/selector
        // thread kept for the application lifetime).
        try (HttpClient httpClient = BrevoRestClients.httpClient("brevo-api probe connect-timeout", PROBE_TIMEOUT)) {
            RestClient restClient = BrevoRestClients.build(builder,
                    properties.baseUrl(), httpClient, "brevo-api probe read-timeout", PROBE_TIMEOUT);
            HttpStatusCode status = restClient.get()
                    .uri(ACCOUNT_PATH)
                    .header(API_KEY_HEADER, properties.apiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((req, res) -> res.getStatusCode(), true);
            return classify(status);
        } catch (RuntimeException e) {
            // Network/timeout (incl. a bare CancellationException from the JDK client's read timeout) and any
            // other client failure are both UNREACHABLE — never REJECTED, which is reserved for an HTTP answer.
            // Phase 441: a failed probe call is an external-call failure → ONE ERROR (Sentry). The URL carries no
            // secret (api-key header). The rule token stays in the detail for the banner/runbook grep.
            String cause = BrevoRestClients.isTransportFailure(e) ? rootCauseName(e) : e.getClass().getSimpleName();
            probeFailure().reason(ExternalCallFailure.transportReason(e))
                    .detail(RULE_UNREACHABLE + " " + cause).log(log, e);
            return Result.UNREACHABLE;
        }
    }

    private static Result classify(HttpStatusCode status) {
        int value = status.value();
        if (status.is2xxSuccessful()) {
            return Result.OK;
        }
        if (value == 400 || value == 401 || value == 403) {
            probeFailure().http(value).reason("auth_rejected").detail(RULE_AUTH_FAILED).log(log);
            return Result.REJECTED;
        }
        probeFailure().http(value).reason("http_" + value).detail(RULE_UNREACHABLE).log(log);
        return Result.UNREACHABLE;
    }

    private static ExternalCallFailure probeFailure() {
        return ExternalCallFailure.of("brevo-api", "probe");
    }

    private static String rootCauseName(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
