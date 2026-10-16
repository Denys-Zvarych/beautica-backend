package com.beautica.common.observability;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure PII redaction rules shared by the Sentry event, transaction and breadcrumb paths (Phase 371, D4).
 *
 * <p>Order matters: tokens first (a JWT contains digit runs), then e-mail, then OTP codes (a 6-digit code
 * must become {@code [otp]}, not survive as an unmatched short digit run), then phone numbers and finally
 * any remaining run of nine or more digits. Over-redaction (e.g. an all-digit UUID segment) is accepted;
 * under-redaction is not.
 *
 * <p><b>Cost is linear.</b> Input is capped at {@value #MAX_LENGTH} characters before any pattern runs; every
 * unbounded run is possessive or anchored by a negative look-behind (so a regex attempt starts only at the
 * beginning of a run, never at every offset inside it); separators are bounded; the e-mail and JWT patterns
 * are skipped outright unless a cheap pre-check ({@code @}/{@code %40}, {@code eyJ}) finds their marker.
 */
public final class PiiRedactor {

    static final String EMAIL = "[email]";
    static final String PHONE = "[phone]";
    static final String JWT = "[jwt]";
    static final String OTP = "[otp]";
    static final String BEARER = "Bearer [redacted]";
    static final String TOKEN = "[token]";
    static final String TRUNCATED = "…[truncated]";
    static final int MAX_LENGTH = 4096;
    /**
     * Patterns run over {@code MAX_LENGTH + LOOKAHEAD} characters before the cut, so a secret straddling the
     * 4 KB boundary is redacted whole rather than leaving its head behind (audit-fix cycle 2, finding 1).
     */
    static final int LOOKAHEAD = 2048;

    private static final Pattern JWT_PATTERN = Pattern.compile("(?<![\\w-])eyJ[\\w-]++\\.[\\w-]++\\.[\\w-]++");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s++[^\\s,;\"']++");
    /** {@code ?token=…}, {@code &key=…}, {@code ;sig=…} in free text (exception values, URLs in messages). */
    private static final Pattern QUERY_SECRET_PATTERN =
            Pattern.compile("(?i)([?&;][\\w-]{0,40}?(?:token|key|code|sig|signature|password|secret|otp)=)[^&\\s\"'#]++");
    /** Unicode local part and labels (Cyrillic / IDN); {@code %40} is a URL-encoded {@code @}. */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}._+-])[\\p{L}\\p{N}._+-]++(?:@|%40)(?:[\\p{L}\\p{N}-]++\\.)++\\p{L}{2,}+");
    /**
     * A ≥32-char run mixing letters and digits (opaque API tokens, FCM device tokens, UUIDs). Anchored to the
     * start of a run, look-aheads bounded to the run, so it stays linear.
     */
    private static final Pattern MIXED_TOKEN_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_:-])(?=[A-Za-z_:-]*+\\d)(?=[\\d_:-]*+[A-Za-z])[A-Za-z0-9_:-]{32,}+");
    private static final int OTP_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    /** 4–6 digits within 20 characters after an otp/code/код keyword. */
    private static final Pattern OTP_AFTER_KEYWORD =
            Pattern.compile("(otp|code|код)(.{0,20}?)(?<!\\d)\\d{4,6}(?!\\d)", OTP_FLAGS);
    /** 4–6 digits within 20 characters before an otp/code/код keyword. */
    private static final Pattern OTP_BEFORE_KEYWORD =
            Pattern.compile("(?<!\\d)\\d{4,6}(?!\\d)(.{0,20}?)(otp|code|код)", OTP_FLAGS);
    private static final List<Pattern> PHONE_PATTERNS = List.of(
            // International: +380501112233, 380501112233, +380 50 111 22 33, +38 (050) 111-22-33.
            Pattern.compile("\\+?38[\\s()-]{0,3}0[\\s()-]{0,3}\\d{2}[\\s()-]{0,3}\\d{3}[\\s-]{0,2}\\d{2}[\\s-]{0,2}\\d{2}(?!\\d)"),
            // National: 0501112233, 050 111 22 33, (050) 111-22-33, 050-111-22-33.
            Pattern.compile("(?<![\\d+])\\(?0[\\s()-]{0,3}\\d{2}[\\s()-]{0,3}\\d{3}[\\s-]{0,2}\\d{2}[\\s-]{0,2}\\d{2}(?!\\d)"),
            // Any other country code with spaced/dashed groups: +48 512 345 678, +1-202-555-0143.
            Pattern.compile("\\+\\d{1,3}(?:[\\s-]?\\d{2,4}){2,5}(?!\\d)"),
            Pattern.compile("(?<!\\d)\\d{9,}+"));

    /**
     * A capability token inside a URL path: a UUID, a ≥20-char {@code [A-Za-z0-9_-]} run that contains a digit
     * (so static route words such as {@code verify-password-reset-otp} are never masked), or any run of ≥32 such
     * characters, digit or not (a 43-char base64url token has no digit about 0.07% of the time). Boundary-anchored, so
     * it also catches {@code prefix-<token>} and a token followed by {@code .json} etc.
     */
    private static final Pattern PATH_TOKEN_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "|(?=[A-Za-z_-]*+\\d)[A-Za-z0-9_-]{20,}+|[A-Za-z0-9_-]{32,}+)(?![A-Za-z0-9_-])");

    /** Non-alphanumeric characters that may continue a secret across the truncation cut (email, token, JWT, path). */
    private static final String SECRET_RUN_PUNCTUATION = "@%._+:/-";

    /** Key names whose values are never sent (extra / contexts / tags / breadcrumb data). */
    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("(?i)note|comment|passport|allerg|health|password|token|otp|phone|email|secret|key");

    private PiiRedactor() {
    }

    /**
     * @return {@code value} with every recognised PII shape replaced by its marker, capped at about
     *         {@value #MAX_LENGTH} chars (cut never inside a secret-like run); {@code null} stays null
     */
    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() <= MAX_LENGTH) {
            return redactAll(value);
        }
        String window = value.substring(0, Math.min(value.length(), MAX_LENGTH + LOOKAHEAD));
        return cutAtSafeBoundary(redactAll(window)) + TRUNCATED;
    }

    private static String redactAll(String input) {
        String out = input;
        if (out.contains("eyJ")) {
            out = JWT_PATTERN.matcher(out).replaceAll(JWT);
        }
        out = BEARER_PATTERN.matcher(out).replaceAll(Matcher.quoteReplacement(BEARER));
        out = QUERY_SECRET_PATTERN.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(TOKEN));
        if (out.indexOf('@') >= 0 || out.contains("%40")) {
            out = EMAIL_PATTERN.matcher(out).replaceAll(Matcher.quoteReplacement(EMAIL));
        }
        out = MIXED_TOKEN_PATTERN.matcher(out).replaceAll(Matcher.quoteReplacement(TOKEN));
        out = OTP_AFTER_KEYWORD.matcher(out).replaceAll("$1$2" + Matcher.quoteReplacement(OTP));
        out = OTP_BEFORE_KEYWORD.matcher(out).replaceAll(Matcher.quoteReplacement(OTP) + "$1$2");
        for (Pattern phone : PHONE_PATTERNS) {
            out = phone.matcher(out).replaceAll(Matcher.quoteReplacement(PHONE));
        }
        return out;
    }

    /** Cuts at {@value #MAX_LENGTH}, then backs off past any secret-like run the cut would split. */
    private static String cutAtSafeBoundary(String redacted) {
        int end = Math.min(redacted.length(), MAX_LENGTH);
        while (end > 0 && isSecretRunChar(redacted.charAt(end - 1))) {
            end--;
        }
        return redacted.substring(0, end);
    }

    private static boolean isSecretRunChar(char c) {
        return Character.isLetterOrDigit(c) || SECRET_RUN_PUNCTUATION.indexOf(c) >= 0;
    }

    /**
     * A URL, path or transaction name ({@code "GET /api/v1/book/cancel/<uuid>"}) with the query/fragment dropped,
     * each segment cut at {@code ;} (matrix params) and percent-decoded, every capability token masked as
     * {@value #TOKEN} ({@link #PATH_TOKEN_PATTERN}), and the rest {@link #redact}ed. Route templates
     * ({@code {token}}) and static route words are left as they are.
     */
    public static String redactPath(String url) {
        if (url == null) {
            return null;
        }
        int window = MAX_LENGTH + LOOKAHEAD;
        String path = pathOnly(url.length() > window ? url.substring(0, window) : url);
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            segments[i] = normaliseSegment(segments[i]);
        }
        String masked = PATH_TOKEN_PATTERN.matcher(String.join("/", segments)).replaceAll(Matcher.quoteReplacement(TOKEN));
        return redact(masked);
    }

    private static String normaliseSegment(String segment) {
        int matrix = segment.indexOf(';');
        String cut = matrix >= 0 ? segment.substring(0, matrix) : segment;
        if (cut.indexOf('%') < 0) {
            return cut;
        }
        try {
            return URLDecoder.decode(cut.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return cut;
        }
    }

    /** @return whether a map key names data that must never leave the process */
    public static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEY.matcher(key).find();
    }

    /** @return the path part of a URL: query string and fragment dropped; {@code null} stays null */
    public static String pathOnly(String url) {
        if (url == null) {
            return null;
        }
        int cut = url.length();
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query >= 0) {
            cut = query;
        }
        if (fragment >= 0 && fragment < cut) {
            cut = fragment;
        }
        return url.substring(0, cut);
    }
}
