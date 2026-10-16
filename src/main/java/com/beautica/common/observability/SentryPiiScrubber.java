package com.beautica.common.observability;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryBaseEvent;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.TypeCheckHint;
import io.sentry.protocol.Contexts;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentrySpan;
import io.sentry.protocol.SentryTransaction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Last line of PII defence before anything leaves for Sentry (Phase 371, D4). Registered by the Sentry
 * starter as the {@code beforeSend}, {@code beforeSendTransaction} and {@code beforeBreadcrumb} callback.
 *
 * <ul>
 *   <li>request: method + URL path only, capability-token segments masked ({@link PiiRedactor#redactPath});
 *       headers reduced to {@link #ALLOWED_HEADERS}; no query string, cookies, body, env or other data</li>
 *   <li>event transaction name: {@link PiiRedactor#redactPath}. A transaction (trace) whose name would change
 *       under redaction (an unmatched raw URL carrying a token) is dropped — its name has no setter</li>
 *   <li>transaction spans: description and data redacted; transaction breadcrumbs removed</li>
 *   <li>user: always removed</li>
 *   <li>exception values, message, log-entry params and breadcrumb messages/data: {@link PiiRedactor}</li>
 *   <li>persistence exceptions anywhere in the chain: every exception value becomes its class name, because
 *       a Postgres {@code Detail: Key (x)=(value)} echoes free text no regex can recognise</li>
 *   <li>extra / contexts / tags / breadcrumb data keys matching {@link PiiRedactor#isSensitiveKey}: dropped</li>
 * </ul>
 *
 * <p><b>Breadcrumbs</b> are redacted once, at send time (the event path). The {@code beforeBreadcrumb} callback
 * only drops breadcrumbs recorded outside an HTTP request (startup, schedulers, pooled tasks): those would
 * otherwise pile up in the shared root scope and be copied into every request's scope (audit-fix cycle 1,
 * perf 8). Request-thread breadcrumbs live in the request's forked scope and die with it.
 *
 * <p>Pure and stateless; never throws a business exception (a failing scrubber would drop the event).
 * Present only when the Sentry auto-configuration is (a {@code sentry.dsn} property exists).
 */
@Component
@ConditionalOnProperty(name = "sentry.dsn")
public class SentryPiiScrubber implements SentryOptions.BeforeSendCallback,
        SentryOptions.BeforeSendTransactionCallback, SentryOptions.BeforeBreadcrumbCallback {

    static final Set<String> ALLOWED_HEADERS = Set.of("user-agent", "x-request-id");
    static final String HTTP_SPAN_OP_PREFIX = "http";

    static final String SQL_EXCEPTION_HELPER_LOGGER = "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";
    static final String SQL_EXCEPTION_HELPER_PLACEHOLDER = "[sql exception detail dropped]";

    private static final Set<String> PERSISTENCE_TYPE_NAMES = Set.of(
            "DataIntegrityViolationException", "SQLException", "PSQLException",
            "ConstraintViolationException", "JDBCException", "PersistenceException");

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        scrubBase(event);
        event.setTransaction(PiiRedactor.redactPath(event.getTransaction()));
        List<Breadcrumb> breadcrumbs = event.getBreadcrumbs();
        if (breadcrumbs != null) {
            breadcrumbs.forEach(SentryPiiScrubber::scrubBreadcrumb);
        }
        if (SQL_EXCEPTION_HELPER_LOGGER.equals(event.getLogger())) {
            dropSqlExceptionHelperMessage(event);
        } else {
            scrubMessage(event.getMessage());
        }
        scrubExceptions(event);
        return event;
    }

    @Override
    public SentryTransaction execute(SentryTransaction transaction, Hint hint) {
        String name = transaction.getTransaction();
        if (!Objects.equals(name, PiiRedactor.redactPath(name))) {
            return null;
        }
        scrubBase(transaction);
        transaction.setBreadcrumbs(null);
        transaction.getSpans().replaceAll(SentryPiiScrubber::scrubSpan);
        return transaction;
    }

    /**
     * Drop-only: redaction happens once, at send time. Kept when recorded on a request thread: either inside
     * {@code RequestContextFilter}'s scope, or the request's own {@code http} breadcrumb, which
     * {@code SentrySpringFilter} adds (with its request hint, into the request's forked scope) before request
     * attributes are bound (audit-fix cycle 2, perf N1).
     */
    @Override
    public Breadcrumb execute(Breadcrumb breadcrumb, Hint hint) {
        boolean requestFilterBreadcrumb = hint != null && hint.get(TypeCheckHint.SPRING_REQUEST_FILTER_REQUEST) != null;
        return requestFilterBreadcrumb || RequestContextHolder.getRequestAttributes() != null ? breadcrumb : null;
    }

    private void scrubBase(SentryBaseEvent event) {
        event.setUser(null);
        event.setRequest(scrubRequest(event.getRequest()));
        scrubTags(event);
        scrubExtras(event);
        scrubContexts(event.getContexts());
    }

    /**
     * Hibernate logs {@code sqlException.getMessage()} (no throwable) through this logger; pgjdbc can put the
     * server's {@code Detail:} row values into it. Backstop to {@code logServerErrorDetail=false}.
     */
    private static void dropSqlExceptionHelperMessage(SentryEvent event) {
        Message message = event.getMessage();
        if (message == null) {
            return;
        }
        message.setFormatted(SQL_EXCEPTION_HELPER_PLACEHOLDER);
        message.setMessage(SQL_EXCEPTION_HELPER_PLACEHOLDER);
        message.setParams(null);
    }

    /**
     * HTTP spans describe a URL (path redaction, query dropped); every other span (e.g. SQL) has its description
     * run through {@link PiiRedactor#redact} (pattern-based, capped), not kept verbatim.
     */
    private static String scrubSpanDescription(SentrySpan span) {
        String op = span.getOp();
        return op != null && op.startsWith(HTTP_SPAN_OP_PREFIX)
                ? PiiRedactor.redactPath(span.getDescription())
                : PiiRedactor.redact(span.getDescription());
    }

    private static SentrySpan scrubSpan(SentrySpan span) {
        Map<String, Object> data = span.getData() == null ? null : scrubMap(span.getData());
        SentrySpan clean = new SentrySpan(span.getStartTimestamp(), span.getTimestamp(), span.getTraceId(),
                span.getSpanId(), span.getParentSpanId(), span.getOp(), scrubSpanDescription(span),
                span.getStatus(), span.getOrigin(), span.getTags(), span.getMeasurements(), data);
        clean.setUnknown(span.getUnknown());
        return clean;
    }

    private static Request scrubRequest(Request original) {
        if (original == null) {
            return null;
        }
        Request clean = new Request();
        clean.setMethod(original.getMethod());
        clean.setUrl(PiiRedactor.redactPath(original.getUrl()));
        Map<String, String> headers = original.getHeaders();
        if (headers != null) {
            Map<String, String> allowed = new HashMap<>();
            headers.forEach((name, value) -> {
                if (name != null && ALLOWED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    allowed.put(name, PiiRedactor.redact(value));
                }
            });
            clean.setHeaders(allowed);
        }
        return clean;
    }

    private static void scrubTags(SentryBaseEvent event) {
        Map<String, String> tags = event.getTags();
        if (tags == null) {
            return;
        }
        for (String key : new ArrayList<>(tags.keySet())) {
            if (PiiRedactor.isSensitiveKey(key)) {
                event.removeTag(key);
            }
        }
    }

    private static void scrubExtras(SentryBaseEvent event) {
        Map<String, Object> extras = event.getExtras();
        if (extras == null) {
            return;
        }
        for (String key : new ArrayList<>(extras.keySet())) {
            if (PiiRedactor.isSensitiveKey(key)) {
                event.removeExtra(key);
            } else {
                event.setExtra(key, scrubValue(extras.get(key)));
            }
        }
    }

    private static void scrubContexts(Contexts contexts) {
        if (contexts == null) {
            return;
        }
        for (String key : Collections.list(contexts.keys())) {
            if (PiiRedactor.isSensitiveKey(key)) {
                contexts.remove(key);
            } else if (contexts.get(key) instanceof Map<?, ?> map) {
                contexts.put(key, scrubMap(map));
            }
        }
    }

    private static Map<String, Object> scrubMap(Map<?, ?> source) {
        Map<String, Object> clean = new HashMap<>();
        source.forEach((k, v) -> {
            String key = String.valueOf(k);
            if (!PiiRedactor.isSensitiveKey(key)) {
                clean.put(key, scrubValue(v));
            }
        });
        return clean;
    }

    private static Object scrubValue(Object value) {
        if (value instanceof String s) {
            return PiiRedactor.redact(s);
        }
        if (value instanceof Map<?, ?> map) {
            return scrubMap(map);
        }
        return value;
    }

    private static void scrubMessage(Message message) {
        if (message == null) {
            return;
        }
        message.setFormatted(PiiRedactor.redact(message.getFormatted()));
        message.setMessage(PiiRedactor.redact(message.getMessage()));
        List<String> params = message.getParams();
        if (params != null) {
            message.setParams(params.stream().map(PiiRedactor::redact).toList());
        }
    }

    private static void scrubExceptions(SentryEvent event) {
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions == null) {
            return;
        }
        boolean persistence = isPersistence(event.getThrowable()) || exceptions.stream()
                .anyMatch(e -> e.getType() != null && PERSISTENCE_TYPE_NAMES.contains(e.getType()));
        for (SentryException exception : exceptions) {
            exception.setValue(persistence ? exception.getType() : PiiRedactor.redact(exception.getValue()));
        }
    }

    private static final int MAX_CAUSE_DEPTH = 32;

    private static boolean isPersistence(Throwable throwable) {
        return isPersistence(throwable, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
    }

    /** Walks causes AND suppressed throwables; identity set guards cycles, depth bounds the walk. */
    private static boolean isPersistence(Throwable current, java.util.Set<Throwable> seen, int depth) {
        if (current == null || depth >= MAX_CAUSE_DEPTH || !seen.add(current)) {
            return false;
        }
        if (current instanceof DataAccessException || current instanceof SQLException
                || PERSISTENCE_TYPE_NAMES.contains(current.getClass().getSimpleName())) {
            return true;
        }
        if (isPersistence(current.getCause(), seen, depth + 1)) {
            return true;
        }
        for (Throwable suppressed : current.getSuppressed()) {
            if (isPersistence(suppressed, seen, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static void scrubBreadcrumb(Breadcrumb breadcrumb) {
        breadcrumb.setMessage(PiiRedactor.redact(breadcrumb.getMessage()));
        Map<String, Object> data = breadcrumb.getData();
        if (data == null) {
            return;
        }
        for (String key : new ArrayList<>(data.keySet())) {
            if (PiiRedactor.isSensitiveKey(key)) {
                breadcrumb.removeData(key);
            } else if ("url".equals(key) && data.get(key) instanceof String url) {
                breadcrumb.setData(key, PiiRedactor.redactPath(url));
            } else {
                breadcrumb.setData(key, scrubValue(data.get(key)));
            }
        }
    }
}
