package com.beautica.auth.appcheck;

import com.beautica.common.exception.IdentityException;
import com.beautica.config.appcheck.AppCheckMode;
import com.beautica.config.appcheck.AppCheckProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Clock;
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
 *
 * <p>Phone-first hardening (AUTH-D27 d) — a VALID token's {@code jti} is remembered until its {@code exp}
 * ({@link AppCheckReplayStore}); a second use is judged by its own mode {@code app.auth.app-check.replay-mode}:
 * OFF ignore, LOG_ONLY count {@code result=replay} + WARN and pass, ENFORCE 403 {@code APP_CHECK_FAILED}. In ENFORCE a
 * valid token without {@code jti} counts as invalid. Applies only while the main mode is not OFF.
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
    private final AppCheckReplayStore replayStore;
    private final ConcurrentMap<CounterKey, Counter> counters = new ConcurrentHashMap<>();

    private record CounterKey(AppCheckVerifier.Result result, String endpoint, AppCheckMode mode) {
    }

    /** Pre-hardening test constructor: a private replay store on the system clock. */
    public AppCheckGuard(AppCheckVerifier verifier, AppCheckProperties properties, MeterRegistry meterRegistry) {
        this(verifier, properties, meterRegistry, new AppCheckReplayStore(Clock.systemUTC()));
    }

    @Autowired
    public AppCheckGuard(AppCheckVerifier verifier, AppCheckProperties properties, MeterRegistry meterRegistry,
                         AppCheckReplayStore replayStore) {
        this.verifier = verifier;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.replayStore = replayStore;
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
        AppCheckVerifier.Verification verification = verifier.verifyToken(request.getHeader(HEADER));
        AppCheckVerifier.Result result = verification.result();
        count(result, endpoint, mode);
        if (result == AppCheckVerifier.Result.VALID) {
            checkReplay(verification, endpoint, mode);
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

    /** AUTH-D27 (d) — the replay rule for a VALID token, under {@code replay-mode}. */
    private void checkReplay(AppCheckVerifier.Verification verification, String endpoint, AppCheckMode mode) {
        AppCheckMode replayMode = properties.getReplayMode();
        if (replayMode == AppCheckMode.OFF) {
            return;
        }
        String jti = verification.jti();
        if (jti == null || jti.isBlank()) {
            if (replayMode == AppCheckMode.ENFORCE) {
                count(AppCheckVerifier.Result.INVALID, endpoint, mode);
                log.warn("app-check rejected reason=missing-jti endpoint={} mode={}", endpoint, replayMode);
                throw new IdentityException(IdentityException.Code.APP_CHECK_FAILED);
            }
            return;
        }
        if (replayStore.markFirstUse(jti, verification.expiresAt())) {
            return;
        }
        count(AppCheckVerifier.Result.REPLAY, endpoint, mode);
        log.warn("app-check rejected reason={} endpoint={} mode={}",
                AppCheckVerifier.Result.REPLAY.tag(), endpoint, replayMode);
        if (replayMode == AppCheckMode.ENFORCE) {
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
