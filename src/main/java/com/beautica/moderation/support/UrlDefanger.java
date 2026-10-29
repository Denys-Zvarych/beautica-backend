package com.beautica.moderation.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 414 audit-fix — makes URLs in user-written text shown in the support e-mail non-clickable, so a mail client
 * cannot auto-link a reported review's or comment's link into one-click phishing: {@code http(s)://} becomes
 * {@code hxxp(s)://} and the dots of a host name become {@code [.]} ({@code evil.example.com} →
 * {@code evil[.]example[.]com}). A host is two or more letter/digit labels ending in a 2+ letter label, separated by
 * an ASCII or Unicode full stop (U+2024, U+3002, U+FF0E, U+FF61, each replaced by {@code [.]}); the last label may
 * also be a punycode TLD ({@code xn--p1ai}). Dotted-quad IPv4 hosts are defanged too
 * ({@code 1.2.3.4} → {@code 1[.]2[.]3[.]4}). Numbers ({@code 3.5}) and abbreviations ({@code т.д.}) stay as
 * written. Idempotent: defanged text no longer matches.
 * Not an HTML escaper: templates still render with {@code th:text}.
 */
public final class UrlDefanger {

    private static final Pattern SCHEME = Pattern.compile("(?i)\\bhttp(s?)://");
    /** ASCII full stop, one dot leader, and the Unicode full stops clients also accept between host labels. */
    private static final String DOT = "[.\u2024\u3002\uFF0E\uFF61]";
    private static final Pattern HOST = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}-])(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?" + DOT + ")+"
                    // punycode TLD first, so "xn" is not taken as a 2-letter TLD before "--p1ai"
                    + "(?:xn--[a-z0-9-]*[a-z0-9]|\\p{L}{2,})"
                    + "(?![\\p{L}\\p{N}])");
    /** A dotted-quad IPv4 host ({@code 1.2.3.4}); the octet range is not checked — defanging is harmless. */
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![\\p{N}.])\\d{1,3}(?:" + DOT + "\\d{1,3}){3}(?![\\p{N}]|" + DOT + "\\d)");
    private static final Pattern ANY_DOT = Pattern.compile(DOT);

    private UrlDefanger() {
    }

    /** The defanged form of {@code value}; {@code null} stays {@code null}. */
    public static String defang(String value) {
        if (value == null) {
            return null;
        }
        String noScheme = SCHEME.matcher(value).replaceAll("hxxp$1://");
        String hostsDone = HOST.matcher(noScheme).replaceAll(match -> defangDots(match.group()));
        return IPV4.matcher(hostsDone).replaceAll(match -> defangDots(match.group()));
    }

    private static String defangDots(String host) {
        return Matcher.quoteReplacement(ANY_DOT.matcher(host).replaceAll("[.]"));
    }
}
