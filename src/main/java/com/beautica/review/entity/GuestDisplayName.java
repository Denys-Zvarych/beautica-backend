package com.beautica.review.entity;

import com.beautica.booking.retention.GuestPii;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 399 audit / 401 D5 — the ONE «Ім'я П.» rule for a {@link ReviewSource#GUEST_LINK} review author. The result is
 * published on public, cached review endpoints, so it is built defensively from the booking's raw guest fields:
 *
 * <ul>
 *   <li>name part = the first word of {@code guestName} that holds a visible letter. Words are split on ANY character
 *       other than a letter, a combining mark, an apostrophe or a hyphen — any Unicode space (NBSP, U+2009 …), a
 *       zero-width/format character, a dot, {@code +}, a digit, a Hangul filler — so «Олена&nbsp;Кравець» or
 *       «Олена.Кравець» never glues into «ОленаКравець» (digits, brackets — anything phone-like — never survive);
 *       at most {@link #MAX_CONSECUTIVE_MARKS} combining marks are kept after a base character (a cosmetic bound on
 *       «Zalgo» stacking); leading/trailing apostrophes and hyphens are trimmed; cut to
 *       {@link #NAME_PART_MAX_CODE_POINTS} code points. A two-word name field never publishes the second word;</li>
 *   <li>initial = the first visible letter of {@code guestSurname} (a whole code point), appended as {@code " X."};</li>
 *   <li>a Hangul filler (U+115F, U+1160, U+3164, U+FFA0) is a letter to {@link Character#isLetter} but renders as
 *       nothing, so it never counts as a letter here — a filler-only name yields the fallback, a filler-only surname
 *       yields no initial;</li>
 *   <li>no usable name part → {@link GuestPii#ANONYMISED_NAME} («Гість»), with no initial — the same label phase 402
 *       writes on anonymisation.</li>
 * </ul>
 *
 * Never throws and never returns blank; the result is at most 63 code points, inside {@code VARCHAR(64)} (V212), and
 * holds no digit, so {@code chk_reviews_guest_display_name_no_digits} can never fire for a factory-built row.
 */
final class GuestDisplayName {

    static final int NAME_PART_MAX_CODE_POINTS = 60;
    static final String FALLBACK = GuestPii.ANONYMISED_NAME;

    static final int MAX_CONSECUTIVE_MARKS = 2;

    private static final String NAME_PUNCTUATION = "-'’ʼ`";
    private static final String HANGUL_FILLERS = "\\u115F\\u1160\\u3164\\uFFA0";
    /** Any run of non-name characters (or Hangul fillers) separates words. Compiled once — used on every build. */
    private static final Pattern WORD_SEPARATOR =
            Pattern.compile("[[^\\p{L}\\p{M}'’ʼ`\\-][" + HANGUL_FILLERS + "]]+");
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[-'’ʼ`]+|[-'’ʼ`]+$");
    /**
     * The only shape {@link #of} can produce: one word of letters / combining marks / name punctuation, optionally
     * followed by a space, one letter and a dot. No {@code \p{N}} (or any other category) can match.
     */
    private static final Pattern LABEL_SHAPE = Pattern.compile("^([\\p{L}\\p{M}'’ʼ`\\-]+)(?: (\\p{L})\\.)?$");

    private GuestDisplayName() {
    }

    static String of(String guestName, String guestSurname) {
        Optional<String> namePart = firstNameWord(guestName);
        if (namePart.isEmpty()) {
            return FALLBACK;
        }
        return initial(guestSurname)
                .map(letter -> namePart.get() + " " + letter + ".")
                .orElse(namePart.get());
    }

    /**
     * Phase 399 audit — {@code true} when {@code label} is a label {@link #of} could have built: it matches
     * {@link #LABEL_SHAPE} and is a fixed point of the rule (its name part re-normalises to itself, its initial is a
     * visible letter). Guards a {@link ReviewSource#GUEST_LINK} row written through {@code Review.builder()} rather
     * than {@code Review.guestLinkReview} — a full surname or spaced phone digits never reach the public label.
     */
    static boolean isCanonical(String label) {
        if (label == null) {
            return false;
        }
        Matcher shape = LABEL_SHAPE.matcher(label);
        if (!shape.matches()) {
            return false;
        }
        String namePart = shape.group(1);
        String initial = shape.group(2);
        boolean nameIsFixedPoint = firstNameWord(namePart).filter(namePart::equals).isPresent();
        return nameIsFixedPoint && (initial == null || isVisibleLetter(initial.codePointAt(0)));
    }

    private static Optional<String> firstNameWord(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (String token : WORD_SEPARATOR.split(raw)) {
            String cleaned = EDGE_PUNCTUATION.matcher(keepNameCharacters(token)).replaceAll("");
            if (cleaned.codePoints().anyMatch(GuestDisplayName::isVisibleLetter)) {
                return Optional.of(truncate(cleaned));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> initial(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return raw.codePoints()
                .filter(GuestDisplayName::isVisibleLetter)
                .mapToObj(Character::toString)
                .findFirst();
    }

    /** Keeps name characters only, dropping every combining mark past {@link #MAX_CONSECUTIVE_MARKS} in a row. */
    private static String keepNameCharacters(String token) {
        StringBuilder kept = new StringBuilder(token.length());
        int consecutiveMarks = 0;
        for (int i = 0; i < token.length(); i += Character.charCount(token.codePointAt(i))) {
            int codePoint = token.codePointAt(i);
            if (!isNameCharacter(codePoint)) {
                continue;
            }
            consecutiveMarks = isCombiningMark(codePoint) ? consecutiveMarks + 1 : 0;
            if (consecutiveMarks <= MAX_CONSECUTIVE_MARKS) {
                kept.appendCodePoint(codePoint);
            }
        }
        return kept.toString();
    }

    private static boolean isNameCharacter(int codePoint) {
        return isVisibleLetter(codePoint)
                || isCombiningMark(codePoint)
                || NAME_PUNCTUATION.indexOf(codePoint) >= 0;
    }

    private static boolean isVisibleLetter(int codePoint) {
        return Character.isLetter(codePoint) && !isHangulFiller(codePoint);
    }

    private static boolean isHangulFiller(int codePoint) {
        return codePoint == 0x115F || codePoint == 0x1160 || codePoint == 0x3164 || codePoint == 0xFFA0;
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static String truncate(String value) {
        if (value.codePointCount(0, value.length()) <= NAME_PART_MAX_CODE_POINTS) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, NAME_PART_MAX_CODE_POINTS));
    }
}
