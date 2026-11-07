package com.beautica.common.web;

import io.sentry.Sentry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request-ID propagation (Phase 371, D5).
 *
 * <p>Accepts an inbound {@value #HEADER} only when it matches {@link #VALID_ID} (no CR/LF, no spaces, bounded
 * length), otherwise generates a random UUID — an invalid value is never echoed (header / log injection).
 * The ID goes into the logging MDC under {@value #MDC_KEY} (read by {@code ApiResponse} error envelopes), the
 * Sentry scope as tag {@value #SENTRY_TAG}, and the response header — set before the chain runs, so it is on
 * every response, including the security entry point's body-less 401/403.
 *
 * <p><b>Order.</b> {@code HIGHEST_PRECEDENCE + 10}: after Sentry's request filter
 * ({@code HIGHEST_PRECEDENCE}) and tracing filter ({@code +1}), so the tag lands on the request's forked
 * Sentry scope; well before Spring Security ({@code -100}), so 401/403 responses carry the header. Boot
 * adapts a {@code Filter} bean into a registration with this {@code @Order}; {@code @WebMvcTest} slices pick
 * it up the same way, and it has no dependencies.
 *
 * <p><b>{@value #APP_VERSION_HEADER} (phase 437).</b> The app's {@code semver+build} (e.g. {@code 1.4.0+27}).
 * Accepted only when at most {@value #APP_VERSION_MAX_LENGTH} chars and matching {@link #VALID_APP_VERSION};
 * then it goes into the MDC under {@value #APP_VERSION_MDC_KEY} and the Sentry scope as tag
 * {@value #APP_VERSION_SENTRY_TAG}, both cleared in {@code finally}. Anything else is dropped silently and never
 * logged (attacker-controlled). The header never causes a rejection.
 */
@Component
@Order(RequestIdFilter.ORDER)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    static final String SENTRY_TAG = "request_id";
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");
    public static final String APP_VERSION_HEADER = "X-App-Version";
    public static final String APP_VERSION_MDC_KEY = "appVersion";
    static final String APP_VERSION_SENTRY_TAG = "app.version";
    static final int APP_VERSION_MAX_LENGTH = 32;
    static final Pattern VALID_APP_VERSION = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\+\\d{1,9}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        String appVersion = resolveAppVersion(request.getHeader(APP_VERSION_HEADER));
        MDC.put(MDC_KEY, requestId);
        try {
            response.setHeader(HEADER, requestId);
            Sentry.configureScope(scope -> scope.setTag(SENTRY_TAG, requestId));
            if (appVersion != null) {
                MDC.put(APP_VERSION_MDC_KEY, appVersion);
                Sentry.configureScope(scope -> scope.setTag(APP_VERSION_SENTRY_TAG, appVersion));
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            if (appVersion != null) {
                MDC.remove(APP_VERSION_MDC_KEY);
                Sentry.configureScope(scope -> scope.removeTag(APP_VERSION_SENTRY_TAG));
            }
        }
    }

    /** The inbound app version when well-formed, else {@code null} (dropped silently, never logged). */
    static String resolveAppVersion(String inbound) {
        if (inbound == null || inbound.length() > APP_VERSION_MAX_LENGTH) {
            return null;
        }
        return VALID_APP_VERSION.matcher(inbound).matches() ? inbound : null;
    }

    static String resolve(String inbound) {
        if (inbound != null && VALID_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
