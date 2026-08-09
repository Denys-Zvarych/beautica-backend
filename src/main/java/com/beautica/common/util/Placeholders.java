package com.beautica.common.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-pass {@code {name}} substitution for the configurable SMS templates.
 *
 * <p><b>Why this exists.</b> Both SMS builders previously chained
 * {@code template.replace("{serviceName}", name).replace("{date}", …).replace("{cancelUrl}", …)}.
 * Chained {@link String#replace} re-scans the ALREADY-SUBSTITUTED text on every subsequent call, so
 * a value substituted early is itself searched for the later placeholders. A provider free to name
 * a service — {@code "Манікюр {cancelUrl}"} — therefore steered the rendered message: the literal
 * {@code {cancelUrl}} that arrived as data was expanded by the next {@code replace}, duplicating the
 * one-time cancellation link into the middle of the text (and, with {@code {date}}/{@code {time}},
 * letting a provider rearrange the whole layout a guest reads as platform copy).
 *
 * <p>This pass walks the TEMPLATE once and copies each substituted value out verbatim — a value is
 * never re-examined, so data can never be promoted to markup. Values are escaped with
 * {@link Matcher#quoteReplacement} so a name containing {@code $1} or a backslash is emitted
 * literally instead of being read as a regex group reference (which would otherwise throw and fail
 * the send outright).
 *
 * <p>An unmapped placeholder is left in place verbatim, exactly as the chained-{@code replace} form
 * left one — a template referencing a variable the caller does not supply degrades to visible
 * {@code {foo}} rather than silently emptying the slot.
 */
public final class Placeholders {

    /** {@code {name}} — ASCII word characters only, so Cyrillic copy in braces is never touched. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private Placeholders() {
    }

    /**
     * Renders {@code template}, replacing each {@code {key}} with {@code values.get(key)}.
     *
     * @param template the configured template; {@code null} yields {@code null}
     * @param values   placeholder name → replacement; a {@code null} or absent value leaves the
     *                 placeholder untouched
     */
    public static String format(String template, Map<String, String> values) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(
                    rendered, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
