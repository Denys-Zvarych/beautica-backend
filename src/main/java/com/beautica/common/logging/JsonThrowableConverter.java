package com.beautica.common.logging;

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.beautica.common.observability.PiiRedactor;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Logback {@code %jsonEx} (Phase 371, audit-fix cycle 2, finding 5; rebuilt in cycle 4, L-NEW-2): renders the
 * event's stack trace as an extra {@code ,"exception":"…"} JSON member, or nothing when the event has no
 * throwable — so lines without an exception are byte-for-byte unchanged.
 *
 * <p>The output is built from the {@link IThrowableProxy} tree, never by parsing rendered text. Each throwable's
 * header ({@code className: message}) is redacted by {@link PiiRedactor} as ONE block — a message that forges a
 * {@code \tat a.b(C.java:1)} line or splits an OTP keyword from its code across a newline is still just message
 * text. Frames come from {@link StackTraceElementProxy} (code locations, never message text) and are emitted
 * unredacted, in logback's own layout ({@code Caused by:}, indented {@code Suppressed:}, {@code ... N common frames
 * omitted}). Cycles are cut by an identity set and the walk is depth-bounded. Being a
 * {@link ThrowableHandlingConverter}, it also stops logback from appending the raw multi-line trace after the JSON
 * object.
 */
public class JsonThrowableConverter extends ThrowableHandlingConverter {

    static final String FIELD_PREFIX = ",\"exception\":\"";
    private static final String CAUSED_BY = "Caused by: ";
    private static final String SUPPRESSED = "Suppressed: ";
    private static final int MAX_DEPTH = 32;
    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\R\\s*");

    @Override
    public String convert(ILoggingEvent event) {
        IThrowableProxy proxy = event.getThrowableProxy();
        if (proxy == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(512);
        Set<IThrowableProxy> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        append(out, null, 1, proxy, seen, 0);
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') {
            end--;
        }
        return FIELD_PREFIX + JsonEscapedMessageConverter.escape(out.substring(0, end)) + "\"";
    }

    private static void append(StringBuilder out, String prefix, int indent, IThrowableProxy proxy,
                               Set<IThrowableProxy> seen, int depth) {
        if (proxy == null) {
            return;
        }
        if (depth >= MAX_DEPTH || !seen.add(proxy)) {
            ThrowableProxyUtil.indent(out, indent - 1);
            out.append("[... truncated]").append('\n');
            return;
        }
        ThrowableProxyUtil.indent(out, indent - 1);
        if (prefix != null) {
            out.append(prefix);
        }
        out.append(redactedHeader(proxy)).append('\n');
        appendFrames(out, indent, proxy);
        for (IThrowableProxy suppressed : proxy.getSuppressed()) {
            append(out, SUPPRESSED, indent + 1, suppressed, seen, depth + 1);
        }
        append(out, CAUSED_BY, indent, proxy.getCause(), seen, depth + 1);
    }

    private static String redactedHeader(IThrowableProxy proxy) {
        String message = proxy.getMessage();
        String header = message == null ? proxy.getClassName() : proxy.getClassName() + ": " + message;
        if (proxy.isCyclic()) {
            header = "[CIRCULAR REFERENCE: " + header + "]";
        }
        // Line breaks flattened first: PiiRedactor's keyword/code windows do not cross '\n', so an OTP keyword and
        // its code split over two message lines would otherwise survive.
        return PiiRedactor.redact(LINE_BREAKS.matcher(header).replaceAll(" "));
    }

    private static void appendFrames(StringBuilder out, int indent, IThrowableProxy proxy) {
        StackTraceElementProxy[] frames = proxy.getStackTraceElementProxyArray();
        if (frames == null) {
            return;
        }
        int common = Math.max(0, Math.min(proxy.getCommonFrames(), frames.length));
        int printed = frames.length - common;
        for (int i = 0; i < printed; i++) {
            ThrowableProxyUtil.indent(out, indent);
            ThrowableProxyUtil.subjoinSTEP(out, frames[i]);
            out.append('\n');
        }
        if (common > 0) {
            ThrowableProxyUtil.indent(out, indent);
            out.append("... ").append(common).append(" common frames omitted").append('\n');
        }
    }
}
