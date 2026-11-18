package com.beautica.notification.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * B520 (NTF-59, AUTH-16) — the one SMS a not-yet-registered person gets when a salon owner or admin invites them to the
 * team (NTF-D14's single non-OTP exception):
 *
 * <pre>
 * Beautica: «{salon}» запрошує вас до команди. Приєднатися: {link}
 * </pre>
 *
 * <ul>
 *   <li><b>Fixed copy</b>, on the {@link GuestProviderCancellationSmsText} precedent: the only variable parts are the
 *       salon name (provider-authored, but already public on the salon page) and the platform-built join link.</li>
 *   <li><b>At most three concatenated UCS-2 segments</b> ({@value #MAX_UCS2_CHARS} UTF-16 units — the text is
 *       Cyrillic, so it is always UCS-2): the salon name is cut, with a trailing «…», to whatever the fixed copy and
 *       the link leave. The link is never cut — a truncated link is a dead invite. A cut never splits a surrogate
 *       pair (an emoji in a salon name).</li>
 *   <li><b>No links under the trusted sender</b> (audit-fix, anti-smishing): every whitespace-separated word of the
 *       salon name that looks like a URL or a domain ({@code http(s)://…}, {@code www.…}, {@code word.tld[/…]}, an
 *       IPv4 literal; ASCII and CJK/full-width dots alike) is dropped before rendering, so an owner cannot make the
 *       SMS carry a second, foreign link. When nothing with a letter or digit is left, the copy without a name is
 *       sent: {@code Beautica: вас запрошують до команди салону. Приєднатися: {link}}. Each word is checked in its
 *       NFKC form with every invisible character stripped (format {@code \p{Cf}}, combining marks {@code \p{M}} and
 *       the other default-ignorables), so a soft hyphen, a zero-width space, a word joiner or a combining accent
 *       inside {@code evil.com} — all of which a phone's linkifier ignores — cannot hide the domain.</li>
 *   <li><b>No phone numbers under the trusted sender</b> (audit-fix 3, anti-vishing): a phone's linkifier makes a
 *       number tap-to-call, so an owner could plant a «callback» number. A phone number usually spans several words
 *       ({@code +380 44 123 45 67}, {@code 044 / 123 / 4567}) and may be glued to a letter word
 *       ({@code Салон0441 234567}, {@code tel:+380441 23456}), so the whole name — in the same NFKC/invisible-stripped
 *       view — is cut at its letters ({@code \p{L}}) into <i>stretches</i>: every word with no letter, plus the
 *       leading/trailing non-letters of the words around it, whatever the punctuation. A stretch with
 *       {@value #PHONE_MIN_DIGITS} or more digits in total drops every word it touches. A word mixing letters and
 *       {@value #PHONE_MIN_DIGITS}+ digits is dropped on its own. Short numbers («Salon 24», «Nails 24/7»,
 *       «Studio 12 34») stay. Residual risk: a number written for humans (spelled-out digits, letters between digit
 *       groups) cannot be filtered.</li>
 * </ul>
 * Stateless; the rendered text (phone-bound, carries a live token) is never logged.
 */
public final class StaffInviteSmsText {

    /** Three concatenated UCS-2 segments of 67 characters. */
    public static final int MAX_UCS2_CHARS = 3 * 67;

    static final String PREFIX = "Beautica: «";
    static final String MIDDLE = "» запрошує вас до команди. Приєднатися: ";
    static final String ELLIPSIS = "…";
    static final String NAMELESS_PREFIX = "Beautica: вас запрошують до команди салону. Приєднатися: ";

    /** Any dot a phone's linkifier may treat as a domain separator: ASCII, ideographic, full-width, half-width. */
    private static final String DOT = "[.\u3002\uFF0E\uFF61]";
    /** A word carrying a scheme, a {@code www.} host, a {@code label.tld} pair or an IPv4 literal. */
    private static final Pattern LINK_LIKE = Pattern.compile(
            "(?iu)(://|(^|[^\\p{L}\\p{N}])www" + DOT + "|[\\p{L}\\p{N}]" + DOT + "\\p{L}{2,}"
                    + "|\\d{1,3}(" + DOT + "\\d{1,3}){3})");
    /**
     * Characters a linkifier skips: format controls (U+00AD, U+200B–U+200F, U+2060–U+2064, U+FEFF, tags…), combining
     * marks (incl. variation selectors and U+034F) and the default-ignorable fillers that are letters, not {@code Cf}.
     */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\p{M}\u115F\u1160\u3164\uFFA0]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");
    /** Digit count from which a letter-free stretch of the name (or one word) is treated as a dialable number. */
    static final int PHONE_MIN_DIGITS = 7;

    private StaffInviteSmsText() {
    }

    /**
     * @param salonName the inviting salon's display name
     * @param inviteUrl the join link ({@code …/invite/accept?token=…}), never shortened
     */
    public static String render(String salonName, String inviteUrl) {
        String safeName = withoutLinks(salonName);
        if (!LETTER_OR_DIGIT.matcher(safeName).find()) {
            return NAMELESS_PREFIX + inviteUrl;
        }
        int budget = MAX_UCS2_CHARS - PREFIX.length() - MIDDLE.length() - inviteUrl.length();
        return PREFIX + fit(safeName, budget) + MIDDLE + inviteUrl;
    }

    /**
     * The salon name with every URL- or domain-like word and every word touching a phone-number-like stretch dropped,
     * whitespace collapsed; {@code ""} for null.
     */
    static String withoutLinks(String salonName) {
        if (salonName == null) {
            return "";
        }
        String[] words = WHITESPACE.split(salonName.strip());
        String[] views = new String[words.length];
        for (int w = 0; w < words.length; w++) {
            views[w] = linkView(words[w]);
        }
        boolean[] inPhoneStretch = wordsInPhoneStretches(views);
        List<String> kept = new ArrayList<>();
        for (int w = 0; w < words.length; w++) {
            if (!inPhoneStretch[w] && !LINK_LIKE.matcher(views[w]).find() && digitCount(views[w]) < PHONE_MIN_DIGITS) {
                kept.add(words[w]);
            }
        }
        return String.join(" ", kept);
    }

    /**
     * Cuts the space-joined views at every letter into letter-free stretches (a word with no letter at all — an empty,
     * invisible-only one included — lies wholly inside one) and flags every word that touches a stretch holding
     * {@value #PHONE_MIN_DIGITS}+ digits.
     */
    private static boolean[] wordsInPhoneStretches(String[] views) {
        boolean[] inPhoneStretch = new boolean[views.length];
        Stretch stretch = new Stretch();
        for (int w = 0; w < views.length; w++) {
            int word = w;
            if (views[w].isEmpty()) {
                stretch.touch(word, 0);
            }
            views[w].codePoints().forEach(cp -> {
                if (Character.isLetter(cp)) {
                    stretch.close(inPhoneStretch);
                } else {
                    stretch.touch(word, Character.isDigit(cp) ? 1 : 0);
                }
            });
        }
        stretch.close(inPhoneStretch);
        return inPhoneStretch;
    }

    /** The open letter-free stretch: the words it touches and its digit count. */
    private static final class Stretch {
        private final List<Integer> words = new ArrayList<>();
        private int digits;

        void touch(int word, int digitCount) {
            if (words.isEmpty() || words.get(words.size() - 1) != word) {
                words.add(word);
            }
            digits += digitCount;
        }

        void close(boolean[] inPhoneStretch) {
            if (digits >= PHONE_MIN_DIGITS) {
                words.forEach(word -> inPhoneStretch[word] = true);
            }
            words.clear();
            digits = 0;
        }
    }

    private static int digitCount(String view) {
        return (int) view.codePoints().filter(Character::isDigit).count();
    }

    /** The word as a linkifier sees it: NFKC-folded, invisible characters removed. Used for the check only. */
    private static String linkView(String word) {
        return INVISIBLE.matcher(Normalizer.normalize(word, Normalizer.Form.NFKC)).replaceAll("");
    }

    private static String fit(String name, int budget) {
        if (name.length() <= budget) {
            return name;
        }
        int keep = Math.max(budget - ELLIPSIS.length(), 0);
        if (keep > 0 && Character.isHighSurrogate(name.charAt(keep - 1))) {
            keep--;
        }
        return name.substring(0, keep) + ELLIPSIS;
    }
}
