package com.beautica.booking.domain;

import com.beautica.common.util.Placeholders;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 435 audit-fix — the ONE place provider-written text is made safe for a Beautica-branded guest SMS (LINK and
 * walk-in confirmations, 24h / 2h reminders). A walk-in phone was typed by the provider and never opted in, so the
 * salon name (up to 255 chars of any text) and the street / legacy address must not turn the SMS into the provider's
 * own advert or phishing carrier.
 *
 * <ul>
 *   <li>every value is flattened to one line ({@link Placeholders#singleLine});</li>
 *   <li>the salon name and the address are DROPPED when they look like a URL or a domain (a scheme, {@code www.},
 *       {@code label.tld}, an IPv4) — the caller then drops the «Салон …» part / the «Адреса:» line;</li>
 *   <li>the master name is DROPPED on the same rule (audit-fix cycle 2): registration forbids digits and control
 *       characters but not dots, so {@code t.me/promo} or {@code bit.ly/sale} is a legal first/last name. With no
 *       {@code masterName} value, {@link #render} removes the master segment from the template — the confirmation
 *       loses its «Майстер …» line (salon variant: «Салон {salon}.»), the reminder its master segment;</li>
 *   <li>URL detection runs on an NFKC-normalised copy with every {@code \p{Cf}} format character (zero-width,
 *       soft hyphen, bidi overrides) removed («№» mapped to «#» first, so NFKC's «No» never reads as a TLD); it
 *       accepts look-alike dots, a space before a dot, spaces around a dot only for {@code www}, IPv4,
 *       {@code label . tld/} and look-alike TLDs (never Latin «vul. Khreshchatyk»), and checks a Cyrillic→Latin
 *       look-alike skeleton against common TLDs;</li>
 *   <li>salon name ≤ {@value #MAX_SALON_NAME}, address ≤ {@value #MAX_ADDRESS}, master name and service name
 *       ≤ {@value #MAX_PERSON_NAME} UTF-16 units, truncated with «…» never inside a surrogate pair. The service name
 *       is required content (the guest must recognise the booking), so it is capped, not dropped;</li>
 *   <li>{@link #render} caps the whole message at {@value #MAX_SMS_CHARS} chars (5 UCS-2 segments), dropping the
 *       optional lines first: the address, then the salon (falling back to the independent-master template).</li>
 * </ul>
 */
public final class GuestSmsProviderText {

    public static final int MAX_SALON_NAME = 40;
    public static final int MAX_ADDRESS = 60;
    public static final int MAX_PERSON_NAME = 40;
    /** 5 concatenated UCS-2 segments of 67 chars. */
    public static final int MAX_SMS_CHARS = 335;

    private static final String ELLIPSIS = "…";

    /** A dot as typed, or a look-alike (one-dot leader, ideographic, small / full-width / half-width full stop). */
    private static final String DOT_CHAR = "[.\\u2024\\u3002\\uFE52\\uFF0E\\uFF61]";
    private static final String SPACE = "[\\s\\p{Z}]*";
    /** A dot with any whitespace around it ({@code t . me}) — only where a false positive is unlikely. */
    private static final String SPACED_DOT = SPACE + DOT_CHAR + SPACE;
    /**
     * A dot with whitespace allowed BEFORE it only ({@code bit .ly}): Latin prose and addresses put a space AFTER an
     * abbreviation dot («vul. Khreshchatyk», «Nails. Studio»), never a domain.
     */
    private static final String TIGHT_DOT = SPACE + DOT_CHAR;
    private static final String NOT_WORD = "(?![\\p{L}\\p{N}])";
    private static final Pattern URL_LIKE = Pattern.compile(
            "(?iu)"
                    + "[a-z][a-z0-9+.\\-]*://"                                    // any scheme://
                    + "|\\b(?:https?|ftp|mailto|javascript|data|tel|sms|tg|viber|whatsapp|intent):" // scheme:
                    + "|\\bwww" + SPACED_DOT                                       // www.
                    + "|[\\p{L}\\p{N}\\-]" + TIGHT_DOT + "[a-z]{2,24}" + NOT_WORD   // label.tld (ASCII TLD)
                    + "|[\\p{L}\\p{N}\\-]" + SPACED_DOT + "[a-z]{2,24}" + SPACE + "/" // label . tld/path
                    + "|[\\p{L}\\p{N}\\-]" + TIGHT_DOT + "(?:укр|рф|бел|срб|қаз|мон)" + NOT_WORD // Cyrillic TLDs
                    + "|\\b\\d{1,3}" + SPACED_DOT + "\\d{1,3}" + SPACED_DOT + "\\d{1,3}" + SPACED_DOT
                    + "\\d{1,3}\\b");                                                 // IPv4

    /**
     * Common TLDs a Cyrillic look-alike can spell ({@code t.mе}, {@code bit.lу}, {@code promo.соm}). Matched on the
     * Latin skeleton; a hit counts only when the matched span holds a non-ASCII letter, so plain Latin text («Beauty.
     * Pro») is left to {@link #URL_LIKE}.
     */
    private static final Pattern LOOKALIKE_TLD = Pattern.compile(
            "(?u)[\\p{L}\\p{N}\\-]" + SPACED_DOT
                    + "(?:com|net|org|me|io|co|ly|cc|info|biz|shop|site|online|store|link|xyz|app|pro|top|club|page|bio)"
                    + NOT_WORD);

    /** «№» — NFKC turns it into «No», which reads as a TLD («буд. № 5»); replaced before NFKC, detection only. */
    private static final char NUMERO_SIGN = '\u2116';

    /** Lower-case Cyrillic letters that render like a Latin one (input already lower-cased). */
    private static final String CYRILLIC_LOOKALIKES = "авекмнорстухіјѕһԁӏ";
    private static final String LATIN_SKELETON = "abekmhopctyxijshdl";

    private static final Pattern FORMAT_CHARS = Pattern.compile("\\p{Cf}");

    /** Template segments carrying the master name, removed when the name is dropped (see {@link #render}). */
    private static final Pattern MASTER_SEGMENT = Pattern.compile(
            "(?iu),[ \\t]*майстер[ \\t]*\\{masterName}"      // «Салон {salonName}, майстер {masterName}.»
                    + "|\\{masterName}[ \\t]*\\|[ \\t]*"        // «{masterName} | {serviceName} …»
                    + "|[ \\t]*\\|[ \\t]*\\{masterName}");      // «… | {masterName}»
    private static final String MASTER_NAME = "masterName";

    private GuestSmsProviderText() {
    }

    /** The salon name for an SMS, or empty when absent or URL-like (drop the «Салон …» part). */
    public static Optional<String> salonName(String raw) {
        return droppingUrls(raw, MAX_SALON_NAME);
    }

    /** The address for an SMS, or empty when absent or URL-like (drop the «Адреса:» line). */
    public static Optional<String> address(String raw) {
        return droppingUrls(raw, MAX_ADDRESS);
    }

    /** A service name: one line, capped; never dropped (required content). */
    public static String personName(String raw) {
        return truncate(Placeholders.singleLine(raw), MAX_PERSON_NAME);
    }

    /**
     * The master name for an SMS (one line, capped), or empty when it looks like a URL — leave {@code masterName} out
     * of the values and {@link #render} drops the master segment.
     */
    public static Optional<String> masterName(String raw) {
        String flat = Placeholders.singleLine(raw);
        return looksLikeUrl(flat) ? Optional.empty() : Optional.of(truncate(flat, MAX_PERSON_NAME));
    }

    /**
     * {@code true} when {@code value} holds a scheme, {@code www.}, a {@code label.tld} or an IPv4 — checked after
     * NFKC normalisation with {@code \p{Cf}} removed, and on a Cyrillic look-alike skeleton against common TLDs.
     */
    public static boolean looksLikeUrl(String value) {
        if (value == null) {
            return false;
        }
        String normalised = normalise(value);
        return URL_LIKE.matcher(normalised).find() || hasLookalikeTld(normalised);
    }

    private static boolean hasLookalikeTld(String normalised) {
        // Character.toLowerCase keeps one char per char, so skeleton indices line up with normalised.
        Matcher matcher = LOOKALIKE_TLD.matcher(latinSkeleton(normalised));
        while (matcher.find()) {
            if (normalised.substring(matcher.start(), matcher.end()).chars().anyMatch(c -> c > 0x7F)) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(String value) {
        String stripped = FORMAT_CHARS.matcher(value.replace(NUMERO_SIGN, '#')).replaceAll("");
        return FORMAT_CHARS.matcher(Normalizer.normalize(stripped, Normalizer.Form.NFKC)).replaceAll("");
    }

    private static String latinSkeleton(String value) {
        StringBuilder skeleton = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            int at = CYRILLIC_LOOKALIKES.indexOf(c);
            skeleton.append(at < 0 ? c : LATIN_SKELETON.charAt(at));
        }
        return skeleton.toString();
    }

    /** {@code template} without its master segment (see {@link #MASTER_SEGMENT}); {@code null} stays {@code null}. */
    static String withoutMaster(String template) {
        return template == null ? null : MASTER_SEGMENT.matcher(template).replaceAll("");
    }

    /**
     * {@code value} cut to at most {@code max} UTF-16 units including the trailing «…»; never splits a surrogate
     * pair, trailing whitespace before the «…» is trimmed. A value that fits is returned unchanged.
     */
    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = max - ELLIPSIS.length();
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    /**
     * Renders the SMS within {@link #MAX_SMS_CHARS}, trying in order: the salon template with the address, the salon
     * template without it, then {@code masterTemplate} without it. The salon template is skipped when it is
     * {@code null} or {@code values} holds no {@code salonName}. Lines whose placeholder is in {@code optional} (or is
     * an address key) are dropped whole when their value is absent ({@link Placeholders#formatDroppingEmptyLines}).
     * When no variant fits (an oversized configured template), the shortest attempt is returned.
     *
     * <p>Audit-fix cycle 2: when {@code values} holds no {@code masterName} (dropped as URL-like), the master segment
     * is removed from both templates first ({@link #withoutMaster}) and any line still holding it is dropped.
     *
     * @param values      sanitised placeholder values; {@code salonName} / address keys may be absent
     * @param addressKeys the placeholders that carry the address (dropped first)
     */
    public static String render(String salonTemplate, String masterTemplate, Map<String, String> values,
                                Set<String> optional, Set<String> addressKeys) {
        Set<String> droppable = new HashSet<>(optional);
        droppable.addAll(addressKeys);
        if (!values.containsKey(MASTER_NAME)) {
            salonTemplate = withoutMaster(salonTemplate);
            masterTemplate = withoutMaster(masterTemplate);
            droppable.add(MASTER_NAME);
        }
        Map<String, String> withoutAddress = new HashMap<>(values);
        addressKeys.forEach(withoutAddress::remove);

        List<String> attempts = new ArrayList<>(4);
        String salonName = values.get("salonName");
        if (salonTemplate != null && salonName != null && !salonName.isBlank()) {
            attempts.add(Placeholders.formatDroppingEmptyLines(salonTemplate, values, droppable));
            attempts.add(Placeholders.formatDroppingEmptyLines(salonTemplate, withoutAddress, droppable));
        } else {
            attempts.add(Placeholders.formatDroppingEmptyLines(masterTemplate, values, droppable));
        }
        attempts.add(Placeholders.formatDroppingEmptyLines(masterTemplate, withoutAddress, droppable));
        for (String attempt : attempts) {
            if (attempt.length() <= MAX_SMS_CHARS) {
                return attempt;
            }
        }
        return attempts.get(attempts.size() - 1);
    }

    private static Optional<String> droppingUrls(String raw, int max) {
        String flat = Placeholders.singleLine(raw);
        if (flat.isEmpty() || looksLikeUrl(flat)) {
            return Optional.empty();
        }
        return Optional.of(truncate(flat, max));
    }
}
