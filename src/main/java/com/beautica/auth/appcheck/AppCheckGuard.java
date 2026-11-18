package com.beautica.auth.appcheck;

import com.beautica.common.exception.IdentityException;
import com.beautica.config.appcheck.AppCheckMode;
import com.beautica.config.appcheck.AppCheckProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * B514 (AUTH-54, D5) — the ONE App Check gate for every endpoint that sends an SMS to a registered user or an
 * invitee; all logs and metrics live here. Reused by B516 (phone OTP send, forgot / change password SMS branch) and
 * B520 (staff invite create).
 *
 * <ul>
 *   <li>{@code OFF} — no-op, nothing counted.</li>
 *   <li>{@code LOG_ONLY} — verify, count, WARN on anything but valid, always pass.</li>
 *   <li>{@code ENFORCE} — missing / invalid → 403 {@code APP_CHECK_FAILED}; keys unavailable → 503
 *       {@code APP_CHECK_UNAVAILABLE}.</li>
 * </ul>
 * Counter {@value #METRIC}{@code {result, endpoint, mode}}; {@code endpoint} is the matched route pattern (bounded
 * cardinality), so the counters are cached per tag triple instead of rebuilt per request. Logs name the reason and endpoint only — never the token, never the phone.
 */
@Component
public class AppCheckGuard {

    public static final String HEADER = "X-Firebase-AppCheck";
    public static final String METRIC = "appcheck.verify";
    private static final String UNKNOWN_ENDPOINT = "unknown";

    private static final Logger log = LoggerFactory.getLogger(AppCheckGuard.class);

    private final AppCheckVerifier verifier;
    private final AppCheckProperties properties;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<CounterKey, Counter> counters = new ConcurrentHashMap<>();

    private record CounterKey(AppCheckVerifier.Result result, String endpoint, AppCheckMode mode) {
    }

    public AppCheckGuard(AppCheckVerifier verifier, AppCheckProperties properties, MeterRegistry meterRegistry) {
        this.verifier = verifier;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @throws IdentityException 403 {@code APP_CHECK_FAILED} / 503 {@code APP_CHECK_UNAVAILABLE}, ENFORCE only
     */
    public void check(HttpServletRequest request) {
        AppCheckMode mode = properties.getMode();
        if (mode == AppCheckMode.OFF) {
            return;
        }
        String endpoint = endpointOf(request);
        AppCheckVerifier.Result result = verifier.verify(request.getHeader(HEADER));
        count(result, endpoint, mode);
        if (result == AppCheckVerifier.Result.VALID) {
            return;
        }
        log.warn("app-check rejected reason={} endpoint={} mode={}", result.tag(), endpoint, mode);
        if (mode == AppCheckMode.LOG_ONLY) {
            return;
        }
        throw new IdentityException(result == AppCheckVerifier.Result.UNAVAILABLE
                ? IdentityException.Code.APP_CHECK_UNAVAILABLE
                : IdentityException.Code.APP_CHECK_FAILED);
    }

    /**
     * B516 (AUTH-54) — the guard for endpoints that send an SMS on ONE branch only (forgot-password by phone,
     * change-password by SMS): the service calls this right before choosing the SMS branch, so the controller needs
     * no servlet plumbing. Outside a request (no bound {@link HttpServletRequest}) the token counts as missing — fail
     * closed in ENFORCE.
     *
     * @throws IdentityException as {@link #check}
     */
    public void checkCurrentRequest() {
        if (properties.getMode() == AppCheckMode.OFF) {
            return;
        }
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest()
                : null;
        if (request != null) {
            check(request);
            return;
        }
        AppCheckMode mode = properties.getMode();
        AppCheckVerifier.Result result = verifier.verify(null);
        count(result, UNKNOWN_ENDPOINT, mode);
        log.warn("app-check rejected reason={} endpoint={} mode={}", result.tag(), UNKNOWN_ENDPOINT, mode);
        if (mode == AppCheckMode.ENFORCE) {
            throw new IdentityException(IdentityException.Code.APP_CHECK_FAILED);
        }
    }

    private void count(AppCheckVerifier.Result result, String endpoint, AppCheckMode mode) {
        counters.computeIfAbsent(new CounterKey(result, endpoint, mode), this::register).increment();
    }

    private Counter register(CounterKey key) {
        return Counter.builder(METRIC)
                .tag("result", key.result().tag())
                .tag("endpoint", key.endpoint())
                .tag("mode", key.mode().name())
                .register(meterRegistry);
    }

    private static String endpointOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String route ? request.getMethod() + " " + route : UNKNOWN_ENDPOINT;
    }
}
