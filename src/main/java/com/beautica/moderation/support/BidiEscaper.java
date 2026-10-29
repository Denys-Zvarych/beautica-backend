package com.beautica.moderation.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes invisible text-direction controls visible in user-written text shown to support (phase 396 audit-fix INFO,
 * promoted out of {@code ModerationTargetSnapshot} in phase 414 so the moderation queue and the report e-mail share
 * ONE transform). Bidi embeddings / overrides / isolates, LRM / RLM / ALM, the Unicode line / paragraph separators and
 * the zero-width characters (ZWSP / ZWNJ / ZWJ, word joiner and the invisible operators U+2060–U+2064, BOM / ZWNBSP)
 * are replaced by their literal {@code \\uXXXX} escape, so stored content cannot reorder what support reads (e.g. make
 * "remove" display as "evomer"). HTML escaping is NOT done here: templates still render with {@code th:text}.
 */
public final class BidiEscaper {

    /**
     * Bidi embeddings/overrides/isolates, LRM/RLM/ALM, the Unicode line/paragraph separators, and (phase 414) the
     * zero-width characters U+200B–U+200D, U+2060–U+2064 and U+FEFF, which can hide or split words support reads.
     */
    private static final String INVISIBLE_CONTROLS =
            "[\\u061C\\u200B-\\u200F\\u2028\\u2029\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]";
    private static final Pattern INVISIBLE = Pattern.compile(INVISIBLE_CONTROLS);

    private BidiEscaper() {
    }

    /** Replaces every invisible control with its visible {@code \\uXXXX} escape; {@code null} stays {@code null}. */
    public static String escape(String value) {
        if (value == null) {
            return null;
        }
        return INVISIBLE.matcher(value).replaceAll(match ->
                Matcher.quoteReplacement(String.format("\\u%04X", (int) match.group().charAt(0))));
    }
}
