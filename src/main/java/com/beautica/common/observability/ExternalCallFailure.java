package com.beautica.common.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.http.converter.HttpMessageNotReadableException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Phase 441 — the single ERROR line for a failed outbound call to an external service (SMS, mail, Telegram, FCM,
 * R2, CDN purge, Turnstile). Sentry turns only ERROR log lines into events ({@code sentry.logging.minimum-event-level:
 * error}), so every failed attempt is logged exactly once, here, at the point closest to the call; the layers above
 * only re-map the exception and log at WARN or not at all.
 *
 * <p>The message template {@value #MESSAGE} is constant, so the fields stay searchable. While the line is logged,
 * {@code service} and the {@code reason} token sit in the MDC under {@value #TAG_SERVICE} / {@value #TAG_REASON};
 * {@code SentryOptionsConfig} promotes them to Sentry tags and {@link ExternalCallSentryThrottle} fingerprints the
 * event per (service, reason) — one Sentry issue each — and caps its volume (audit-fix cycle 1). {@code reason} is a normalised token ({@code [a-z0-9_]}); {@code detail} is provider free text (an
 * error {@code description}/{@code message}) passed through {@link PiiRedactor}, flattened to one line and cut at
 * {@value #MAX_DETAIL_CHARS} chars. Callers never pass a request body, a token, a message text or an OTP code.
 *
 * <p>Value object, built fluently: {@code ExternalCallFailure.of("smsclub", "send").http(400).reason("sender_invalid")
 * .to(masked).detail(text).log(log)}.
 */
public record ExternalCallFailure(String service, String op, Integer http, String reason, String to, String detail) {

    public static final String MESSAGE = "External call failed service={} op={} http={} reason={} to={} detail={}";
    public static final String NONE = "-";
    /** MDC key (and Sentry tag) carrying {@code service} while the ERROR line is logged. */
    public static final String TAG_SERVICE = "ext_call_service";
    /** MDC key (and Sentry tag) carrying the normalised {@code reason} token while the ERROR line is logged. */
    public static final String TAG_REASON = "ext_call_reason";
    public static final String TIMEOUT = "timeout";
    public static final String CONNECT = "connect";
    public static final String DNS = "dns";
    public static final String TLS = "tls";
    public static final String IO = "io";
    public static final String UNREADABLE_RESPONSE = "unreadable_response";
    public static final String UNEXPECTED = "unexpected";
    static final int MAX_DETAIL_CHARS = 200;
    /** Cause-chain walk bound; real chains are 2-4 deep, a cyclic one must not spin. */
    private static final int MAX_CAUSE_DEPTH = 16;
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}+");
    private static final Pattern NON_TOKEN = Pattern.compile("[^a-z0-9_]+");

    public static ExternalCallFailure of(String service, String op) {
        return new ExternalCallFailure(service, op, null, null, null, null);
    }

    public ExternalCallFailure http(int status) {
        return new ExternalCallFailure(service, op, status, reason, to, detail);
    }

    public ExternalCallFailure reason(String value) {
        return new ExternalCallFailure(service, op, http, value, to, detail);
    }

    /** @param masked an already masked recipient ({@code +380***1234}, {@code device:<id>}); never a raw one */
    public ExternalCallFailure to(String masked) {
        return new ExternalCallFailure(service, op, http, reason, masked, detail);
    }

    /** @param providerText raw provider free text; redacted and capped by {@link #sanitizeDetail} */
    public ExternalCallFailure detail(String providerText) {
        return new ExternalCallFailure(service, op, http, reason, to, providerText);
    }

    /** Logs the ERROR line without an exception. */
    public void log(Logger logger) {
        Object[] args = arguments();
        try (MDC.MDCCloseable s = MDC.putCloseable(TAG_SERVICE, String.valueOf(args[0]));
             MDC.MDCCloseable r = MDC.putCloseable(TAG_REASON, String.valueOf(args[3]))) {
            logger.error(MESSAGE, args);
        }
    }

    /** Logs the ERROR line with {@code cause} as the trailing throwable (stack trace for Sentry). */
    public void log(Logger logger, Throwable cause) {
        Object[] args = arguments();
        Object[] withCause = new Object[args.length + 1];
        System.arraycopy(args, 0, withCause, 0, args.length);
        withCause[args.length] = cause;
        try (MDC.MDCCloseable s = MDC.putCloseable(TAG_SERVICE, String.valueOf(args[0]));
             MDC.MDCCloseable r = MDC.putCloseable(TAG_REASON, String.valueOf(args[3]))) {
            logger.error(MESSAGE, withCause);
        }
    }

    private Object[] arguments() {
        return new Object[]{orNone(service), orNone(op), http == null ? NONE : http, reasonToken(reason), orNone(to),
                sanitizeDetail(detail)};
    }

    /** {@code null}/blank → {@value #NONE}; else lower-case with every run outside {@code [a-z0-9_]} as {@code _}. */
    public static String reasonToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        return NON_TOKEN.matcher(raw.strip().toLowerCase(Locale.ROOT)).replaceAll("_");
    }

    /** Redacted ({@link PiiRedactor}), one line, at most {@value #MAX_DETAIL_CHARS} chars; blank → {@value #NONE}. */
    public static String sanitizeDetail(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String flat = CONTROL_CHARS.matcher(raw).replaceAll(" ").strip();
        String redacted = PiiRedactor.redact(flat);
        return redacted.length() <= MAX_DETAIL_CHARS ? redacted : redacted.substring(0, MAX_DETAIL_CHARS);
    }

    /**
     * Stable reason token for a failure that produced no provider answer: {@value #TIMEOUT}, {@value #CONNECT},
     * {@value #DNS}, {@value #TLS}, {@value #IO}, {@value #UNREADABLE_RESPONSE} or {@value #UNEXPECTED}.
     */
    public static String transportReason(Throwable failure) {
        String fallback = UNEXPECTED;
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_CAUSE_DEPTH;
                t = t.getCause() == t ? null : t.getCause(), depth++) {
            String specific = specificReason(t);
            if (specific != null) {
                return specific;
            }
            if (t instanceof HttpMessageNotReadableException || t instanceof JsonProcessingException) {
                return UNREADABLE_RESPONSE;
            }
            if (t instanceof IOException) {
                fallback = IO;
            }
        }
        return fallback;
    }

    private static String specificReason(Throwable t) {
        if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException
                || t instanceof NoRouteToHostException) {
            return CONNECT;
        }
        if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException
                || t instanceof CancellationException || t instanceof TimeoutException) {
            return TIMEOUT;
        }
        if (t instanceof UnknownHostException) {
            return DNS;
        }
        if (t instanceof SSLException) {
            return TLS;
        }
        return null;
    }

    /** Simple class name of the innermost cause — a safe {@code detail} for a transport failure. */
    public static String rootCauseName(Throwable failure) {
        Throwable root = failure;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < MAX_CAUSE_DEPTH) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private static Object orNone(String value) {
        return value == null || value.isBlank() ? NONE : value;
    }
}
