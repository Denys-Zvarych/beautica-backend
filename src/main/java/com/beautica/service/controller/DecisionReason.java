package com.beautica.service.controller;

import java.util.regex.Pattern;

/**
 * Normalises the OPTIONAL free-text reason an admin types on a token-authenticated reject form
 * (phase 372, D4). The endpoints are {@code permitAll} and the value is later shown to the requester,
 * so it is cleaned at the boundary: line breaks/tabs fold to one space, every other control or
 * format character is removed, then the value is trimmed and a blank collapses to "no reason".
 * Output is HTML-escaped at render time ({@code th:text}), never here. The value is never logged.
 */
final class DecisionReason {

    /** Mirrors the {@code decision_reason VARCHAR(300)} column and the form's {@code maxlength}. */
    static final int MAX_LENGTH = 300;

    private static final Pattern LINE_BREAKS_AND_TABS = Pattern.compile("[\\r\\n\\t]+");
    private static final Pattern CONTROL_OR_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    /** Hard cap on RAW input: anything longer is rejected before any regex runs (bounded work on permitAll). */
    private static final int RAW_HARD_CAP = 4 * MAX_LENGTH;

    private DecisionReason() {
    }

    /**
     * @return the cleaned reason ({@code value == null} when absent/blank), or a result flagged
     *         {@code tooLong} when the cleaned value exceeds {@link #MAX_LENGTH} code points
     */
    static Parsed parse(String raw) {
        if (raw == null) {
            return Parsed.NONE;
        }
        if (raw.length() > RAW_HARD_CAP) {
            return Parsed.TOO_LONG;
        }
        String cleaned = CONTROL_OR_FORMAT.matcher(
                LINE_BREAKS_AND_TABS.matcher(raw).replaceAll(" ")).replaceAll("").strip();
        if (cleaned.isEmpty()) {
            return Parsed.NONE;
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_LENGTH) {
            return Parsed.TOO_LONG;
        }
        return new Parsed(cleaned, false);
    }

    /** {@code value} is null for "no reason"; {@code tooLong} marks a rejected input. */
    record Parsed(String value, boolean tooLong) {
        static final Parsed NONE = new Parsed(null, false);
        static final Parsed TOO_LONG = new Parsed(null, true);
    }
}
