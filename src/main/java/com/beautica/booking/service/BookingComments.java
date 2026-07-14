package com.beautica.booking.service;

import java.util.regex.Pattern;

/**
 * Normalizes every free-text booking note before it is persisted (Phase 25.1).
 *
 * <p>Applied uniformly to {@code client_comment} (booking creation), {@code provider_comment}
 * (decline / not-complete), and {@code client_cancellation_note} (client cancel) — the four
 * comment write paths in {@code BookingService}. Bean Validation ({@code @Size}/{@code @Pattern}
 * on the request DTOs) rejects control characters and oversized payloads at the controller
 * boundary; this class handles what validation does not: presentation-layer whitespace/line-break
 * normalization and stripping Unicode {@code Cf} (Format) characters — bidi overrides/isolates,
 * zero-width spacing/joiners, directional marks, soft hyphen — that could otherwise be used to
 * visually spoof the rendered text (e.g. in the decline email or a guest SMS) without tripping
 * the control-character ban (those code points are not C0/C1 controls).
 */
public final class BookingComments {

    // Character classes are built from explicit unicode escapes rather than embedding the
    // literal invisible/bidi code points in this source file — this class exists specifically to
    // strip that class of character, so the source itself must stay free of them (editor/diff/
    // grep safety).
    //
    // Invisible/bidi-influencing characters are stripped by Unicode GENERAL CATEGORY (Cf —
    // "Format"), not an enumerated code-point list (LOW finding). The previous explicit ranges
    // (bidi embeddings/overrides U+202A-U+202E, isolates U+2066-U+2069, zero-width space/non-
    // joiner/joiner U+200B-U+200D, BOM U+FEFF) missed weaker-but-still-invisible/direction-
    // influencing siblings in the SAME category: U+200E (LRM), U+200F (RLM), U+061C (ALM),
    // U+2060 (word joiner), U+00AD (soft hyphen). Every one of those, plus every code point the
    // old ranges already covered, is category Cf — verified against the Unicode Character
    // Database (`python3 -c "import unicodedata; print(unicodedata.category(chr(cp)))"` for each
    // code point above returns "Cf"). Cf is defined as invisible-or-formatting-only characters
    // with no glyph of their own, so stripping the whole category cannot remove a visible
    // character a legitimate note needs. {@link #stripFormatCharacters} iterates by CODE POINT
    // (not {@code char}) so a surrogate pair (e.g. an emoji) is never split mid-pair.

    private static final Pattern CRLF = Pattern.compile("\r\n?");

    /** Three or more consecutive line breaks collapse to a single blank line (two breaks). */
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\n{3,}");

    private BookingComments() {
    }

    /**
     * Normalizes a raw comment for storage.
     *
     * @param raw the request-supplied comment, possibly {@code null}
     * @return the normalized comment, or {@code null} if {@code raw} is {@code null} or blank
     *         after normalization (blank-to-null keeps {@code IS NULL} the single "no comment"
     *         representation — never an empty string)
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String noFormatChars = stripFormatCharacters(raw);
        String unixNewlines = CRLF.matcher(noFormatChars).replaceAll("\n");
        String collapsed = EXCESS_BLANK_LINES.matcher(unixNewlines).replaceAll("\n\n");
        String stripped = collapsed.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * Removes every Unicode {@code Cf} (Format) code point — see the class-level comment above
     * for the category rationale. Iterates via {@link String#codePoints()} rather than indexing
     * by {@code char}, so a surrogate pair is always read as one unit and never split.
     */
    private static String stripFormatCharacters(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (Character.getType(codePoint) != Character.FORMAT) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }
}
