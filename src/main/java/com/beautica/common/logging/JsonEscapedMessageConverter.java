package com.beautica.common.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback {@code %jsonMsg}: the formatted log message escaped for use inside a JSON string literal (Phase 371,
 * audit-fix cycle 1, finding 7). The prod console pattern hand-builds one JSON object per line; a raw
 * {@code %msg} containing {@code "}, {@code \} or a newline broke the line or forged extra fields. Escapes
 * {@code "} and {@code \} and every control character below U+0020 (RFC 8259 §7); nothing else changes.
 */
public class JsonEscapedMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return escape(event.getFormattedMessage());
    }

    /** Shared with {@link JsonThrowableConverter}. Returns {@code value} itself when nothing needs escaping. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (!needsEscaping(value)) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static boolean needsEscaping(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) {
                return true;
            }
        }
        return false;
    }
}
