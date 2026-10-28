package com.beautica.moderation.dto;

import com.beautica.review.entity.ModerationStatus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What support sees of a reported target in the queue (phase 396 D4). Internal endpoint only; every field is
 * nullable and only the ones that apply to the target type are set. {@code exists = false} means the target is gone.
 *
 * <p>User-written text ({@code text}, {@code displayName}) has bidi controls and line/paragraph separators rendered
 * as visible {@code \\uXXXX} escapes (audit-fix INFO): content stored before the phase-396 comment pattern (or
 * written by a path without it) cannot reorder what support reads, e.g. make "remove" display as "evomer".
 *
 * @param exists           whether the target row still exists
 * @param text             review / client review comment
 * @param rating           review / client review rating
 * @param moderationStatus review / client review moderation status
 * @param photoUrl         portfolio photo public URL
 * @param displayName      master / salon / client profile name
 */
public record ModerationTargetSnapshot(
        boolean exists,
        String text,
        Integer rating,
        ModerationStatus moderationStatus,
        String photoUrl,
        String displayName
) {

    /** Bidi embeddings/overrides/isolates, LRM/RLM/ALM, and the Unicode line/paragraph separators. */
    private static final String INVISIBLE_CONTROLS = "[\\u061C\\u200E\\u200F\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069]";
    private static final Pattern INVISIBLE = Pattern.compile(INVISIBLE_CONTROLS);

    public static ModerationTargetSnapshot gone() {
        return new ModerationTargetSnapshot(false, null, null, null, null, null);
    }

    public static ModerationTargetSnapshot review(String text, Short rating, ModerationStatus status) {
        return new ModerationTargetSnapshot(
                true, escapeInvisible(text), rating == null ? null : rating.intValue(), status, null, null);
    }

    public static ModerationTargetSnapshot photo(String photoUrl) {
        return new ModerationTargetSnapshot(true, null, null, null, photoUrl, null);
    }

    public static ModerationTargetSnapshot profile(String displayName) {
        return new ModerationTargetSnapshot(true, null, null, null, null, escapeInvisible(displayName));
    }

    /** Replaces every invisible control with its visible {@code \\uXXXX} escape; {@code null} stays {@code null}. */
    static String escapeInvisible(String value) {
        if (value == null) {
            return null;
        }
        return INVISIBLE.matcher(value).replaceAll(match ->
                Matcher.quoteReplacement(String.format("\\u%04X", (int) match.group().charAt(0))));
    }
}
