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

    /** Three or more consecutive line breaks — what dropping a paragraph leaves behind. */
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\n{3,}");

    /**
     * Phase 435 — {@link #format} for templates with OPTIONAL lines: every template line holding a placeholder in
     * {@code optional} whose value is {@code null} or blank is removed before rendering (so it never renders as a
     * literal {@code {key}} or a dangling label such as «Адреса: »), then the blank-line runs that removal leaves
     * are collapsed to one blank line and leading/trailing line breaks are trimmed. Every other placeholder behaves
     * exactly as in {@link #format} — still ONE pass, so a substituted value never becomes markup.
     *
     * <p>Values are copied verbatim: a caller substituting free text that may hold line breaks must flatten it
     * first, or the value could forge extra lines in a platform-branded message.
     *
     * @param optional placeholder names (without braces) whose line is dropped when the value is absent
     */
    public static String formatDroppingEmptyLines(String template, Map<String, String> values,
                                                  java.util.Set<String> optional) {
        if (template == null) {
            return null;
        }
        StringBuilder kept = new StringBuilder(template.length());
        for (String line : template.split("\n", -1)) {
            if (!holdsAbsentOptional(line, values, optional)) {
                kept.append(line).append('\n');
            }
        }
        String rendered = format(kept.substring(0, Math.max(0, kept.length() - 1)), values);
        return trimLineBreaks(EXTRA_BLANK_LINES.matcher(rendered).replaceAll("\n\n"));
    }

    private static boolean holdsAbsentOptional(String line, Map<String, String> values, java.util.Set<String> optional) {
        Matcher matcher = PLACEHOLDER.matcher(line);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = values.get(key);
            if (optional.contains(key) && (value == null || value.isBlank())) {
                return true;
            }
        }
        return false;
    }

    private static String trimLineBreaks(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '\n') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '\n') {
            end--;
        }
        return text.substring(start, end);
    }

    /**
     * Unicode format characters (zero-width, soft hyphen, bidi overrides such as U+202E) — removed, so none reaches an
     * SMS (audit-fix cycle 2).
     */
    private static final Pattern FORMAT_CHARS = Pattern.compile("\\p{Cf}");

    /** Control characters, line/paragraph separators and whitespace runs. */
    private static final Pattern CONTROL_OR_SPACE = Pattern.compile("[\\p{Cntrl}\\s\\u0085\\u2028\\u2029]+");

    /**
     * Phase 435 — provider free text (salon name, address) flattened to one line for an SMS value: every control
     * character / line break / whitespace run becomes one space, every {@code \p{Cf}} format character is removed,
     * trimmed; {@code null} → {@code ""}. Without it a
     * value could forge extra lines inside a Beautica-branded message.
     */
    public static String singleLine(String value) {
        return value == null ? ""
                : CONTROL_OR_SPACE.matcher(FORMAT_CHARS.matcher(value).replaceAll("")).replaceAll(" ").trim();
    }
}
