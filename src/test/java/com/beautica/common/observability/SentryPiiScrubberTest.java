package com.beautica.common.observability;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.SpanId;
import io.sentry.SpanStatus;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryId;
import io.sentry.protocol.SentrySpan;
import io.sentry.protocol.SentryTransaction;
import io.sentry.protocol.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SentryPiiScrubber — Phase 371 D4 canaries")
class SentryPiiScrubberTest {

    private static final String EMAIL_CANARY = "canary@x.ua";
    private static final String PHONE_INTL_CANARY = "+380501112233";
    private static final String PHONE_LOCAL_CANARY = "0501112233";
    private static final String JWT_CANARY =
            // JWT-shaped fixture split at the dots so secret scanners (gitleaks jwt) do not flag the source; runtime value unchanged.
            "eyJhbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxMjM0NTY3ODkwIiwicm9sZSI6IkNMSUVOVCJ9" + "." + "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String OTP_CANARY = "482913";
    private static final String BEARER_CANARY = "abc";
    private static final String NOTE_CANARY = "NOTE-CANARY-371";

    private final SentryPiiScrubber scrubber = new SentryPiiScrubber();

    private static SentryException exception(String type, String value) {
        SentryException exception = new SentryException();
        exception.setType(type);
        exception.setValue(value);
        return exception;
    }

    private static String allCanaries() {
        return "user " + EMAIL_CANARY + " phone " + PHONE_INTL_CANARY + " alt " + PHONE_LOCAL_CANARY
                + " token " + JWT_CANARY + " your code " + OTP_CANARY + " auth Bearer " + BEARER_CANARY;
    }

    private static void assertNoCanary(String text) {
        assertThat(text).doesNotContain(EMAIL_CANARY, PHONE_INTL_CANARY, PHONE_LOCAL_CANARY, "501112233",
                JWT_CANARY, "eyJhbGciOiJIUzI1NiJ9", OTP_CANARY, "Bearer " + BEARER_CANARY, NOTE_CANARY);
    }

    @Test
    @DisplayName("exception value carrying every canary — all redacted to markers")
    void should_redactEveryCanary_when_exceptionValueCarriesThem() {
        SentryEvent event = new SentryEvent();
        event.setExceptions(new ArrayList<>(List.of(exception("IllegalStateException", allCanaries()))));

        SentryEvent result = scrubber.execute(event, new Hint());

        String value = result.getExceptions().get(0).getValue();
        assertNoCanary(value);
        assertThat(value).contains(PiiRedactor.EMAIL, PiiRedactor.PHONE, PiiRedactor.JWT, PiiRedactor.OTP,
                PiiRedactor.BEARER);
    }

    @ParameterizedTest
    @ValueSource(strings = {PHONE_INTL_CANARY, PHONE_LOCAL_CANARY, "+380 50 111 22 33", "380501112233", "1234567890123"})
    @DisplayName("each phone shape alone — redacted to [phone]")
    void should_redactPhone_when_valueIsAPhoneShape(String phone) {
        String redacted = PiiRedactor.redact("call " + phone + " now");

        assertThat(redacted).isEqualTo("call " + PiiRedactor.PHONE + " now");
    }

    @ParameterizedTest
    @ValueSource(strings = {"code 482913", "OTP: 4829", "Ваш код 482913", "482913 is your code"})
    @DisplayName("OTP near otp/code/код — redacted to [otp]")
    void should_redactOtp_when_digitsNearKeyword(String text) {
        String redacted = PiiRedactor.redact(text);

        assertThat(redacted).contains(PiiRedactor.OTP).doesNotContainPattern("\\d{4,6}");
    }

    @Test
    @DisplayName("log message, formatted text and params — redacted")
    void should_redactMessageAndParams_when_logEntryCarriesPii() {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setMessage("Failed for {} with {}");
        message.setFormatted("Failed for " + EMAIL_CANARY + " with " + PHONE_INTL_CANARY);
        message.setParams(List.of(EMAIL_CANARY, PHONE_INTL_CANARY));
        event.setMessage(message);

        SentryEvent result = scrubber.execute(event, new Hint());

        assertNoCanary(result.getMessage().getFormatted());
        assertNoCanary(String.join(",", result.getMessage().getParams()));
        assertThat(result.getMessage().getMessage()).isEqualTo("Failed for {} with {}");
    }

    @Test
    @DisplayName("extra key clientComment with note text — dropped; harmless extra kept")
    void should_dropSensitiveExtraKeys_when_eventHasNoteText() {
        SentryEvent event = new SentryEvent();
        event.setExtra("clientComment", NOTE_CANARY);
        event.setExtra("passportAllergies", NOTE_CANARY);
        event.setExtra("thread_name", "http-nio-1");

        SentryEvent result = scrubber.execute(event, new Hint());

        assertThat(result.getExtras()).doesNotContainKeys("clientComment", "passportAllergies")
                .containsEntry("thread_name", "http-nio-1");
        assertThat(result.getExtras().toString()).doesNotContain(NOTE_CANARY);
    }

    @Test
    @DisplayName("tags with sensitive keys — dropped; request_id kept intact")
    void should_dropSensitiveTags_when_eventHasThem() {
        SentryEvent event = new SentryEvent();
        event.setTag("userEmail", EMAIL_CANARY);
        event.setTag("request_id", "req-12345678");

        SentryEvent result = scrubber.execute(event, new Hint());

        assertThat(result.getTags()).doesNotContainKey("userEmail").containsEntry("request_id", "req-12345678");
    }

    @Test
    @DisplayName("contexts — sensitive keys dropped, nested MDC map scrubbed")
    void should_scrubContexts_when_mdcContextCarriesPii() {
        SentryEvent event = new SentryEvent();
        event.getContexts().put("MDC", new HashMap<>(Map.of("phone", PHONE_INTL_CANARY, "requestId", "abcdefgh-1")));
        event.getContexts().put("passport", Map.of("allergies", NOTE_CANARY));

        SentryEvent result = scrubber.execute(event, new Hint());

        assertThat(result.getContexts().get("passport")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> mdc = (Map<String, Object>) result.getContexts().get("MDC");
        assertThat(mdc).doesNotContainKey("phone").containsEntry("requestId", "abcdefgh-1");
    }

    @Test
    @DisplayName("request — query string, cookies, body, env dropped; only User-Agent and X-Request-Id headers kept")
    void should_reduceRequest_when_eventCarriesHttpRequest() {
        Request request = new Request();
        request.setMethod("POST");
        request.setUrl("https://api.beautica.ua/api/v1/bookings?phone=" + PHONE_INTL_CANARY);
        request.setQueryString("phone=" + PHONE_INTL_CANARY);
        request.setCookies("session=" + JWT_CANARY);
        request.setData(Map.of("clientComment", NOTE_CANARY));
        request.setEnvs(Map.of("REMOTE_ADDR", "203.0.113.7"));
        request.setHeaders(Map.of("Authorization", "Bearer " + BEARER_CANARY, "Cookie", "session=x",
                "User-Agent", "okhttp/4", "X-Request-Id", "req-12345678"));
        SentryEvent event = new SentryEvent();
        event.setRequest(request);

        Request result = scrubber.execute(event, new Hint()).getRequest();

        assertThat(result.getMethod()).isEqualTo("POST");
        assertThat(result.getUrl()).isEqualTo("https://api.beautica.ua/api/v1/bookings");
        assertThat(result.getQueryString()).isNull();
        assertThat(result.getCookies()).isNull();
        assertThat(result.getData()).isNull();
        assertThat(result.getEnvs()).isNull();
        assertThat(result.getHeaders()).containsOnlyKeys("User-Agent", "X-Request-Id")
                .containsEntry("X-Request-Id", "req-12345678");
    }

    @Test
    @DisplayName("DataIntegrityViolationException with Detail: Key (email)=(…) — value is the class name only")
    void should_replaceValueWithClassName_when_persistenceExceptionInChain() {
        String detail = "ERROR: duplicate key value violates unique constraint \"ux_users_email\" "
                + "Detail: Key (email)=(" + EMAIL_CANARY + ") already exists. note " + NOTE_CANARY;
        DataIntegrityViolationException thrown = new DataIntegrityViolationException(detail, new SQLException(detail));
        SentryEvent event = new SentryEvent(thrown);
        event.setExceptions(new ArrayList<>(List.of(
                exception("DataIntegrityViolationException", detail),
                exception("SQLException", detail))));

        SentryEvent result = scrubber.execute(event, new Hint());

        assertThat(result.getExceptions()).extracting(SentryException::getValue)
                .containsExactly("DataIntegrityViolationException", "SQLException");
    }

    @Test
    @DisplayName("persistence exception only SUPPRESSED under a non-persistence primary — value is the class name")
    void should_replaceValueWithClassName_when_persistenceExceptionIsSuppressed() {
        RuntimeException primary = new RuntimeException("wrapped " + NOTE_CANARY);
        primary.addSuppressed(new SQLException("Detail: Key (note)=(" + NOTE_CANARY + ")"));
        SentryEvent event = new SentryEvent(primary);
        event.setExceptions(new ArrayList<>(List.of(exception("RuntimeException", "wrapped " + NOTE_CANARY))));

        SentryEvent result = scrubber.execute(event, new Hint());

        assertThat(result.getExceptions()).extracting(SentryException::getValue).containsExactly("RuntimeException");
    }

    @Test
    @DisplayName("persistence type detected from exception list alone (no throwable) — value is the class name")
    void should_replaceValueWithClassName_when_onlyExceptionTypeIsPersistence() {
        SentryEvent event = new SentryEvent();
        event.setExceptions(new ArrayList<>(List.of(
                exception("RuntimeException", "wrapped: " + NOTE_CANARY),
                exception("PSQLException", "Detail: Key (note)=(" + NOTE_CANARY + ")"))));

        SentryEvent result = scrubber.execute(event, new Hint());

        assertThat(result.getExceptions()).extracting(SentryException::getValue)
                .containsExactly("RuntimeException", "PSQLException");
    }

    @Test
    @DisplayName("user — always removed")
    void should_removeUser_when_eventHasUser() {
        SentryEvent event = new SentryEvent();
        User user = new User();
        user.setEmail(EMAIL_CANARY);
        user.setIpAddress("203.0.113.7");
        event.setUser(user);

        SentryEvent result = scrubber.execute(event, new Hint());

        assertThat(result.getUser()).isNull();
    }

    @Test
    @DisplayName("breadcrumb on an event — message and data redacted at send time; sensitive keys dropped; url path only")
    void should_redactBreadcrumb_when_eventIsSent() {
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setMessage("Sent OTP to " + PHONE_INTL_CANARY + " for " + EMAIL_CANARY);
        breadcrumb.setData("url", "https://api.turbosms.ua/send?recipient=" + PHONE_INTL_CANARY);
        breadcrumb.setData("clientComment", NOTE_CANARY);
        breadcrumb.setData("status", "200");
        SentryEvent event = new SentryEvent();
        event.setBreadcrumbs(new ArrayList<>(List.of(breadcrumb)));

        Breadcrumb result = scrubber.execute(event, new Hint()).getBreadcrumbs().get(0);

        assertNoCanary(result.getMessage());
        assertThat(result.getData()).doesNotContainKey("clientComment")
                .containsEntry("url", "https://api.turbosms.ua/send")
                .containsEntry("status", "200");
    }

    @Test
    @DisplayName("beforeBreadcrumb outside an HTTP request (startup / scheduler / pool) — dropped")
    void should_dropBreadcrumb_when_noRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setMessage("Started BeauticaApplication");

        assertThat(scrubber.execute(breadcrumb, new Hint())).isNull();
    }

    @Test
    @DisplayName("beforeBreadcrumb inside an HTTP request — kept unmodified (redacted once, at send time)")
    void should_keepBreadcrumbUntouched_when_insideRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setMessage("login " + EMAIL_CANARY);
        try {
            Breadcrumb result = scrubber.execute(breadcrumb, new Hint());

            assertThat(result).isSameAs(breadcrumb);
            assertThat(result.getMessage()).isEqualTo("login " + EMAIL_CANARY);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("guest cancel / review capability tokens in the request URL — masked")
    void should_redactCancelToken_when_requestUrlIsTokenPath() {
        String uuid = "3f2b8c1e-9a7d-4e21-8b6f-0c5d4e3a2b19";
        String opaque = "Zx9_kLmN0pQrStUvWxYz12345";
        Request cancel = new Request();
        cancel.setUrl("https://api.beautica.ua/api/v1/book/cancel/" + uuid);
        Request review = new Request();
        review.setUrl("https://api.beautica.ua/review/" + opaque);
        SentryEvent cancelEvent = new SentryEvent();
        cancelEvent.setRequest(cancel);
        cancelEvent.setTransaction("POST /api/v1/book/cancel/" + uuid);
        SentryEvent reviewEvent = new SentryEvent();
        reviewEvent.setRequest(review);

        SentryEvent cancelResult = scrubber.execute(cancelEvent, new Hint());
        SentryEvent reviewResult = scrubber.execute(reviewEvent, new Hint());

        assertThat(cancelResult.getRequest().getUrl()).isEqualTo("https://api.beautica.ua/api/v1/book/cancel/[token]");
        assertThat(cancelResult.getTransaction()).isEqualTo("POST /api/v1/book/cancel/[token]");
        assertThat(reviewResult.getRequest().getUrl()).isEqualTo("https://api.beautica.ua/review/[token]");
    }

    @Test
    @DisplayName("breadcrumbs attached to an event — redacted too")
    void should_redactEmbeddedBreadcrumbs_when_eventCarriesThem() {
        SentryEvent event = new SentryEvent();
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setMessage("login " + EMAIL_CANARY);
        event.setBreadcrumbs(new ArrayList<>(List.of(breadcrumb)));

        SentryEvent result = scrubber.execute(event, new Hint());

        assertNoCanary(result.getBreadcrumbs().get(0).getMessage());
    }

    private static SentryTransaction transaction(String name) {
        return new SentryTransaction(name, 0d, 1d, new ArrayList<>(), new HashMap<>(), null);
    }

    @Test
    @DisplayName("transaction — request reduced, user removed, breadcrumbs removed")
    void should_scrubTransaction_when_tracingIsOn() {
        SentryTransaction transaction = transaction("GET /api/v1/bookings");
        Request request = new Request();
        request.setUrl("/api/v1/bookings?email=" + EMAIL_CANARY);
        request.setHeaders(Map.of("Authorization", "Bearer " + BEARER_CANARY));
        transaction.setRequest(request);
        transaction.setUser(new User());
        transaction.setBreadcrumbs(new ArrayList<>(List.of(new Breadcrumb())));

        SentryTransaction result = scrubber.execute(transaction, new Hint());

        assertThat(result.getRequest().getUrl()).isEqualTo("/api/v1/bookings");
        assertThat(result.getRequest().getHeaders()).isEmpty();
        assertThat(result.getUser()).isNull();
        assertThat(result.getBreadcrumbs()).isNull();
    }

    @Test
    @DisplayName("transaction named by a raw token URL (no route template) — dropped; templated name kept")
    void should_dropTransaction_when_nameCarriesToken() {
        SentryTransaction raw = transaction("GET /api/v1/book/cancel/3f2b8c1e-9a7d-4e21-8b6f-0c5d4e3a2b19");
        SentryTransaction templated = transaction("GET /api/v1/book/cancel/{token}");

        assertThat(scrubber.execute(raw, new Hint())).isNull();
        assertThat(scrubber.execute(templated, new Hint())).isNotNull();
    }

    @Test
    @DisplayName("transaction spans — description and data redacted, sensitive data keys dropped")
    void should_redactSpans_when_transactionHasThem() {
        SentryTransaction transaction = transaction("POST /api/v1/bookings");
        transaction.getSpans().add(new SentrySpan(0d, 1d, new SentryId(), new SpanId(), null, "http.client",
                "POST https://api.turbosms.ua/send?to=" + PHONE_INTL_CANARY, SpanStatus.OK, "manual",
                new HashMap<>(), new HashMap<>(),
                new HashMap<>(Map.of("url", "https://x/" + EMAIL_CANARY, "phone", PHONE_INTL_CANARY))));

        SentrySpan span = scrubber.execute(transaction, new Hint()).getSpans().get(0);

        assertThat(span.getDescription()).isEqualTo("POST https://api.turbosms.ua/send");
        assertThat(span.getData()).doesNotContainKey("phone");
        assertNoCanary(span.getData().toString());
        assertThat(span.getOp()).isEqualTo("http.client");
    }

    @Test
    @DisplayName("beforeBreadcrumb — the request's own http breadcrumb (SentrySpringFilter hint) kept before request attributes exist")
    void should_keepRequestHttpBreadcrumb_when_addedBySentryFilter() {
        RequestContextHolder.resetRequestAttributes();
        Breadcrumb http = Breadcrumb.http("https://api.beautica.ua/api/v1/salons", "GET");
        Hint hint = new Hint();
        hint.set(io.sentry.TypeCheckHint.SPRING_REQUEST_FILTER_REQUEST, new MockHttpServletRequest());
        Breadcrumb outbound = Breadcrumb.http("https://api.turbosms.ua/send", "POST");

        assertThat(scrubber.execute(http, hint)).isSameAs(http);
        assertThat(scrubber.execute(outbound, new Hint())).as("http breadcrumb from a non-request thread").isNull();
    }

    @Test
    @DisplayName("non-http span (SQL) — full description kept (no path cut at '?'); PII still redacted")
    void should_keepSqlDescription_when_spanIsNotHttp() {
        SentryTransaction transaction = transaction("GET /api/v1/salons");
        transaction.getSpans().add(new SentrySpan(0d, 1d, new SentryId(), new SpanId(), null, "db.sql.query",
                "select * from users where email = ? and phone = '" + PHONE_INTL_CANARY + "'", SpanStatus.OK, "manual",
                new HashMap<>(), new HashMap<>(), new HashMap<>()));

        SentrySpan span = scrubber.execute(transaction, new Hint()).getSpans().get(0);

        assertThat(span.getDescription()).isEqualTo("select * from users where email = ? and phone = '[phone]'");
    }

    @Test
    @DisplayName("SqlExceptionHelper log event carrying a pgjdbc Detail: row — message replaced by a fixed placeholder")
    void should_dropDetail_when_sqlExceptionHelperLogsConstraintViolation() {
        String detail = "ERROR: null value in column \"note\" violates not-null constraint "
                + "Detail: Failing row contains (" + NOTE_CANARY + ", Olha Koval)";
        SentryEvent event = new SentryEvent();
        event.setLogger("org.hibernate.engine.jdbc.spi.SqlExceptionHelper");
        Message message = new Message();
        message.setFormatted(detail);
        message.setMessage(detail);
        message.setParams(new ArrayList<>(List.of(detail)));
        event.setMessage(message);

        SentryEvent result = scrubber.execute(event, new Hint());

        assertThat(result.getMessage().getFormatted()).isEqualTo(SentryPiiScrubber.SQL_EXCEPTION_HELPER_PLACEHOLDER);
        assertThat(result.getMessage().getMessage()).isEqualTo(SentryPiiScrubber.SQL_EXCEPTION_HELPER_PLACEHOLDER);
        assertThat(result.getMessage().getParams()).isNullOrEmpty();
    }

    @Test
    @DisplayName("other loggers keep their (redacted) message text")
    void should_keepRedactedMessage_when_loggerIsNotSqlExceptionHelper() {
        SentryEvent event = new SentryEvent();
        event.setLogger("com.beautica.booking.BookingService");
        Message message = new Message();
        message.setFormatted("hello canary@x.ua");
        event.setMessage(message);

        SentryEvent result = scrubber.execute(event, new Hint());

        assertThat(result.getMessage().getFormatted()).isEqualTo("hello [email]");
    }
}
