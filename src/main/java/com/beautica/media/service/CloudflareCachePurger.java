package com.beautica.media.service;

import com.beautica.config.CloudflareCdnProperties;
import com.beautica.notification.mail.BrevoRestClients;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Phase 417 — purges deleted media URLs from the Cloudflare edge cache ({@code POST /zones/{zone}/purge_cache}).
 *
 * <p>Uploads carry {@code R2StorageService.IMMUTABLE_CACHE_CONTROL} (7-day edge TTL), so without a purge a deleted
 * object keeps being served from the edge for up to 7 days. {@code R2StorageService#deleteFiles} — the single
 * choke point for deleting once-served blobs — hands the public URLs of the keys it actually deleted here.
 *
 * <p><b>Disabled mode:</b> blank {@code app.cloudflare-cdn.zone-id} or {@code purge-token}. Never throws, never
 * fails boot; one startup line (WARN when R2 is enabled, since deleted media then stays edge-cached up to 7 d,
 * otherwise INFO) and {@link #purgeUrls} becomes a DEBUG-logged no-op.
 *
 * <p><b>Best effort, never throws.</b> At most {@value #MAX_URLS_PER_REQUEST} URLs per request. Success is HTTP 200
 * with {@code "success":true}. A 429, a 5xx or a transport failure is retried ONCE after the retry delay (1 s);
 * any remaining failure increments {@value #FAILED_METRIC} by the chunk size and logs a WARN carrying only the URL
 * count and the Cloudflare error code — never a URL (keys embed entity UUIDs), never the token. Purged URLs
 * increment {@value #PURGED_METRIC}.
 *
 * <p><b>Circuit breaker</b> ({@link PurgeCircuitBreaker}): after 3 consecutive chunks end in a transport / 5xx /
 * 429 failure, purges are skipped for 60 s — no HTTP, {@value #FAILED_METRIC} incremented by the skipped URL
 * count, at most one WARN per open window — so a Cloudflare outage cannot pin the 2-thread blob-purge pool. The
 * first chunk after the window (the trial) is sent once, without the retry; a failed trial re-opens the circuit.
 *
 * <p><b>Transport</b> ({@link BrevoRestClients}): Boot's customizers are bypassed (no logging/Sentry interceptor
 * can see the {@code Authorization} header), JDK client, HTTP/1.1, redirects never followed (a 3xx must not
 * re-send the Bearer token elsewhere), connect/read timeouts from {@link CloudflareCdnProperties}.
 */
@Slf4j
@Component
public class CloudflareCachePurger {

    static final String PURGED_METRIC = "beautica.cdn_purge.purged.urls";
    static final String FAILED_METRIC = "beautica.cdn_purge.failed.urls";
    static final int MAX_URLS_PER_REQUEST = 100;
    static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);

    private static final String PURGE_PATH = "/zones/{zoneId}/purge_cache";
    private static final String CONNECT_PROPERTY = "app.cloudflare-cdn.connect-timeout";
    private static final String READ_PROPERTY = "app.cloudflare-cdn.read-timeout";

    private final boolean enabled;
    private final String zoneId;
    private final String bearerHeader;
    private final RestClient restClient;
    private final Duration retryDelay;
    private final Counter purgedUrls;
    private final Counter failedUrls;
    private final PurgeCircuitBreaker breaker;

    @Autowired
    public CloudflareCachePurger(CloudflareCdnProperties props,
                                 @Value("${app.cloudflare-r2.enabled:false}") boolean r2Enabled,
                                 MeterRegistry meterRegistry,
                                 ObjectProvider<ObservationRegistry> observationRegistry,
                                 Clock clock) {
        this(props, r2Enabled, meterRegistry, BrevoRestClients.cleanBuilder(observationRegistry), DEFAULT_RETRY_DELAY,
                clock);
    }

    /** Test seam: a plain builder, a short retry delay (tests never sleep a full second) and a pinned clock. */
    CloudflareCachePurger(CloudflareCdnProperties props, boolean r2Enabled, MeterRegistry meterRegistry,
                          RestClient.Builder builder, Duration retryDelay, Clock clock) {
        this.enabled = props.purgeConfigured();
        this.zoneId = props.zoneId();
        this.bearerHeader = "Bearer " + props.purgeToken();
        this.retryDelay = retryDelay;
        this.breaker = new PurgeCircuitBreaker(clock);
        this.restClient = enabled
                ? BrevoRestClients.build(builder, props.apiBaseUrl(),
                        BrevoRestClients.httpClient(CONNECT_PROPERTY, props.connectTimeout()),
                        READ_PROPERTY, props.readTimeout())
                : null;
        this.purgedUrls = Counter.builder(PURGED_METRIC)
                .description("Media URLs purged from the Cloudflare edge cache")
                .baseUnit("urls")
                .register(meterRegistry);
        this.failedUrls = Counter.builder(FAILED_METRIC)
                .description("Media URLs the Cloudflare edge-cache purge failed for (stay cached up to 7 d)")
                .baseUnit("urls")
                .register(meterRegistry);
        logMode(r2Enabled);
    }

    /** {@code true} when both zone id and purge token are configured. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Purges {@code urls} from the edge cache, in chunks of {@value #MAX_URLS_PER_REQUEST}. No-op for an empty
     * input or in disabled mode. Never throws.
     */
    public void purgeUrls(Collection<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        if (!enabled) {
            log.debug("Cloudflare cache purge disabled; skipped {} URL(s)", urls.size());
            return;
        }
        List<String> distinct = urls.stream().distinct().toList();
        for (int i = 0; i < distinct.size(); i += MAX_URLS_PER_REQUEST) {
            purgeChunk(distinct.subList(i, Math.min(i + MAX_URLS_PER_REQUEST, distinct.size())));
        }
    }

    private void purgeChunk(List<String> chunk) {
        if (breaker.isOpen()) {
            skipWhileOpen(chunk.size());
            return;
        }
        boolean trial = breaker.isTrial();
        Outcome outcome = sendOnce(chunk);
        if (outcome.retryable() && !trial && pauseBeforeRetry()) {
            outcome = sendOnce(chunk);
        }
        if (outcome.success()) {
            breaker.recordSuccess();
            purgedUrls.increment(chunk.size());
            return;
        }
        if (outcome.retryable()) {
            breaker.recordOutageFailure();
        }
        failedUrls.increment(chunk.size());
        log.warn("Cloudflare cache purge failed (urlCount={}, errorCode={}, urls=[omitted])",
                chunk.size(), outcome.errorCode());
    }

    private void skipWhileOpen(int urlCount) {
        failedUrls.increment(urlCount);
        if (breaker.claimWarningForCurrentWindow()) {
            log.warn("Cloudflare cache purge circuit open after {} consecutive failures — skipping purges for up to "
                    + "{} s (urlCount={}, urls=[omitted])", PurgeCircuitBreaker.FAILURE_THRESHOLD,
                    PurgeCircuitBreaker.OPEN_DURATION.toSeconds(), urlCount);
        }
    }

    /**
     * One POST. The catch-all is deliberate: this runs after the DB commit on the blob-purge path, where an
     * escaping exception would only abort the caller's remaining cleanup — every failure is translated into an
     * {@link Outcome} (counted + logged by the caller), never swallowed silently.
     */
    private Outcome sendOnce(List<String> chunk) {
        try {
            return restClient.post()
                    .uri(PURGE_PATH, zoneId)
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PurgeRequest(chunk))
                    .exchange((request, response) -> classify(response.getStatusCode(), readBody(response)), true);
        } catch (RuntimeException ex) {
            boolean transport = BrevoRestClients.isTransportFailure(ex);
            return new Outcome(false, transport, ex.getClass().getSimpleName());
        }
    }

    private static PurgeResponse readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(PurgeResponse.class);
        } catch (RestClientException unreadable) {
            // Non-JSON error page (e.g. a 502 from an intermediary) — classified by status alone.
            return null;
        }
    }

    private static Outcome classify(HttpStatusCode status, PurgeResponse body) {
        String code = errorCode(status, body);
        if (status.value() == 429 || status.is5xxServerError()) {
            return new Outcome(false, true, code);
        }
        boolean success = status.value() == 200 && body != null && body.success();
        return new Outcome(success, false, code);
    }

    private static String errorCode(HttpStatusCode status, PurgeResponse body) {
        if (body != null && body.errors() != null && !body.errors().isEmpty()) {
            return String.valueOf(body.errors().get(0).code());
        }
        return "http_" + status.value();
    }

    /** @return {@code false} when interrupted (no retry; interrupt flag restored). */
    private boolean pauseBeforeRetry() {
        try {
            Thread.sleep(retryDelay);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void logMode(boolean r2Enabled) {
        if (enabled) {
            log.info("CloudflareCachePurger enabled — deleted media URLs are purged from the edge cache");
        } else if (r2Enabled) {
            log.warn("CloudflareCachePurger disabled (app.cloudflare-cdn.zone-id / purge-token blank) while R2 is "
                    + "enabled — deleted media stays edge-cached up to 7 d");
        } else {
            log.info("CloudflareCachePurger disabled — no zone-id / purge-token configured");
        }
    }

    private record Outcome(boolean success, boolean retryable, String errorCode) {}

    record PurgeRequest(List<String> files) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PurgeResponse(boolean success, List<CloudflareError> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CloudflareError(int code) {}
}
