package com.beautica.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsonThrowableConverter — message lines redacted, frame lines left alone")
class JsonThrowableConverterTest {

    private final JsonThrowableConverter converter = new JsonThrowableConverter();

    private static String convert(JsonThrowableConverter converter, Throwable throwable) {
        Logger logger = new LoggerContext().getLogger("com.beautica.Probe");
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.ERROR, "boom", null, null);
        event.setThrowableProxy(new ThrowableProxy(throwable));
        return converter.convert(event);
    }

    private static RuntimeException withFrames(String message, Throwable cause, StackTraceElement... frames) {
        RuntimeException exception = new RuntimeException(message, cause);
        exception.setStackTrace(frames);
        return exception;
    }

    @Test
    @DisplayName("no throwable — empty string")
    void should_returnEmpty_when_eventHasNoThrowable() {
        Logger logger = new LoggerContext().getLogger("com.beautica.Probe");

        assertThat(converter.convert(new LoggingEvent("fqcn", logger, Level.ERROR, "boom", null, null))).isEmpty();
    }

    @Test
    @DisplayName("frame line with 'code' near a 4-digit line number keeps its line number (no [otp])")
    void should_keepFrameLineNumbers_when_frameHasCodeNearDigits() {
        RuntimeException failure = withFrames("failed", null,
                new StackTraceElement("org.springframework.web.util.UriUtils", "encode", "UriUtils.java", 1234),
                new StackTraceElement("com.beautica.Foo", "run", "Foo.java", 42));

        String out = convert(converter, failure);

        assertThat(out).contains("UriUtils.encode(UriUtils.java:1234)").doesNotContain("[otp]");
        assertThat(out).contains("com.beautica.Foo.run(Foo.java:42)");
    }

    @Test
    @DisplayName("'... N common frames omitted' line is left as it is")
    void should_keepMoreLine_when_traceHasCommonFrames() {
        RuntimeException cause = withFrames("inner", null,
                new StackTraceElement("com.beautica.A", "a", "A.java", 1));
        RuntimeException failure = withFrames("outer", cause,
                new StackTraceElement("com.beautica.A", "a", "A.java", 1));

        String out = convert(converter, failure);

        assertThat(out).contains("... 1 common frames omitted");
    }

    @Test
    @DisplayName("Caused by: line carrying an e-mail is still redacted")
    void should_redactCausedByLine_when_causeMessageHasEmail() {
        RuntimeException failure = withFrames("outer", new IllegalStateException("lookup failed for canary@x.ua"),
                new StackTraceElement("com.beautica.A", "a", "A.java", 1));

        String out = convert(converter, failure);

        assertThat(out).contains("Caused by: java.lang.IllegalStateException: lookup failed for [email]")
                .doesNotContain("canary@x.ua");
    }

    @Test
    @DisplayName("Suppressed: line carrying an e-mail is redacted")
    void should_redactSuppressedLine_when_suppressedMessageHasEmail() {
        RuntimeException failure = withFrames("outer", null,
                new StackTraceElement("com.beautica.A", "a", "A.java", 1));
        failure.addSuppressed(new IllegalStateException("close failed for canary@x.ua"));

        String out = convert(converter, failure);

        assertThat(out).contains("Suppressed: java.lang.IllegalStateException: close failed for [email]")
                .doesNotContain("canary@x.ua");
    }

    @Test
    @DisplayName("crafted message line 'at foo@bar.com' is not mistaken for a frame — still redacted")
    void should_redactMessageLine_when_lineMerelyStartsWithAt() {
        RuntimeException failure = withFrames("first line\nat foo@bar.com", null,
                new StackTraceElement("com.beautica.A", "a", "A.java", 1));

        String out = convert(converter, failure);

        assertThat(out).doesNotContain("foo@bar.com").contains("at [email]");
    }

    @Test
    @DisplayName("crafted message line shaped like a frame but with an OTP is not skipped without a file:line tail")
    void should_redactMessageLine_when_atLineHasNoFileTail() {
        RuntimeException failure = withFrames("x\nat canary@x.ua(1)", null,
                new StackTraceElement("com.beautica.A", "a", "A.java", 1));

        String out = convert(converter, failure);

        assertThat(out).doesNotContain("canary@x.ua");
    }

    private static String forgedFrameOutput(String message) {
        return convert(new JsonThrowableConverter(), withFrames(message, null,
                new StackTraceElement("com.beautica.A", "a", "A.java", 1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "x\n\tat a.b(C.java:1) ~[v@mail.com]",
            "\n\tat +380501112233(X.java:1)",
            // JWT-shaped fixture split at the dots so secret scanners (gitleaks jwt) do not flag the source; runtime value unchanged.
            "x\n\tat a.b(C.java:1) ~[eyJhbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxIn0" + "." + "c2lnbmF0dXJl]",
            "x\n\tat a.b(C.java:1) code 482913"})
    @DisplayName("message forging a frame line is redacted as message text")
    void should_redactPii_when_messageForgesFrameLine(String message) {
        String out = forgedFrameOutput(message);

        assertThat(out).doesNotContain("v@mail.com").doesNotContain("380501112233")
                .doesNotContain("eyJhbGci").doesNotContain("482913");
    }

    @Test
    @DisplayName("OTP keyword and code on different lines of one message is redacted")
    void should_redactOtp_when_keywordAndCodeSplitByNewline() {
        String out = forgedFrameOutput("verify otp\n482913 failed");

        assertThat(out).doesNotContain("482913");
    }

    @Test
    @DisplayName("null message — header is the class name only, no ': null'")
    void should_renderClassNameOnly_when_messageIsNull() {
        RuntimeException failure = withFrames(null, null,
                new StackTraceElement("com.beautica.Foo", "run", "Foo.java", 42));

        String out = convert(converter, failure);

        assertThat(out).startsWith(JsonThrowableConverter.FIELD_PREFIX + "java.lang.RuntimeException\\n");
        assertThat(out).doesNotContain("null");
    }

    @Test
    @DisplayName("circular cause chain terminates and marks the cycle")
    void should_terminate_when_causeIsCyclic() {
        RuntimeException a = new RuntimeException("a canary@x.ua");
        a.setStackTrace(new StackTraceElement[] {new StackTraceElement("com.beautica.A", "a", "A.java", 1)});
        RuntimeException b = withFrames("b", a, new StackTraceElement("com.beautica.B", "b", "B.java", 2));
        a.initCause(b);

        String out = convert(converter, b);

        assertThat(out).contains("[CIRCULAR REFERENCE:").doesNotContain("canary@x.ua");
    }

    @Test
    @DisplayName("cause chain deeper than the bound is cut with a marker")
    void should_truncate_when_chainExceedsDepthBound() {
        Throwable chain = new RuntimeException("leaf");
        for (int i = 0; i < 100; i++) {
            chain = new RuntimeException("level" + i, chain);
        }

        String out = convert(converter, chain);

        assertThat(out).contains("[... truncated]").contains("level99");
    }

    @Test
    @DisplayName("suppressed entry is indented one tab with its frames one tab deeper")
    void should_indentSuppressed_when_throwableHasSuppressed() {
        RuntimeException failure = withFrames("outer", null, new StackTraceElement("com.beautica.A", "a", "A.java", 1));
        failure.addSuppressed(withFrames("closing", null, new StackTraceElement("com.beautica.C", "c", "C.java", 3)));

        String out = convert(converter, failure);

        assertThat(out).contains("\\n\\tSuppressed: java.lang.RuntimeException: closing")
                .contains("\\n\\t\\tat com.beautica.C.c(C.java:3)");
    }

    @Test
    @DisplayName("common frames are summarised, not repeated")
    void should_summariseCommonFrames_when_causeSharesTrailingFrames() {
        StackTraceElement shared = new StackTraceElement("com.beautica.Shared", "run", "Shared.java", 9);
        RuntimeException cause = withFrames("inner", null,
                new StackTraceElement("com.beautica.In", "in", "In.java", 5), shared);
        RuntimeException failure = withFrames("outer", cause, shared);

        String out = convert(converter, failure);

        assertThat(out).contains("In.in(In.java:5)").contains("... 1 common frames omitted");
        assertThat(out.split("Shared\\.run\\(Shared\\.java:9\\)", -1)).hasSize(2);
    }

    @Test
    @DisplayName("generated / hidden-class frames pass through verbatim")
    void should_keepFrame_when_frameIsGeneratedOrHiddenClass() {
        RuntimeException failure = withFrames("x", null,
                new StackTraceElement("com.beautica.Foo$$SpringCGLIB$$0", "m", null, -1),
                new StackTraceElement("jdk.internal.reflect.GeneratedMethodAccessor12", "invoke", null, -1),
                new StackTraceElement("app", "my.module", "1.0", "com.beautica.X", "y", "X.java", 7));

        String out = convert(converter, failure);

        assertThat(out).contains("com.beautica.Foo$$SpringCGLIB$$0.m(Unknown Source)")
                .contains("GeneratedMethodAccessor12.invoke(Unknown Source)")
                .contains("com.beautica.X.y(X.java:7)");
    }
}
