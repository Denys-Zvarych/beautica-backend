package com.beautica.common.util;

/**
 * URL scheme allowlist used by the invite-email pipeline (URL builder, outbox enqueue,
 * email transport). Centralised to prevent drift across call sites — a future tightening
 * (e.g. dropping {@code http://localhost} support) only needs to touch one file.
 *
 * <p>Acceptable shapes:
 * <ul>
 *   <li>{@code https://...}</li>
 *   <li>{@code http://localhost} (exact)</li>
 *   <li>{@code http://localhost/...} (path)</li>
 *   <li>{@code http://localhost:...} (port, optionally followed by path)</li>
 * </ul>
 *
 * <p>Rejected: prefix-spoofing shapes such as {@code http://localhost.attacker.com},
 * {@code http://localhostXYZ}, {@code http://localhost-evil.com}; case variants like
 * {@code HTTPS://...} (case-sensitive {@link String#startsWith}); scheme-relative
 * {@code //host/path}; non-allowed schemes ({@code javascript:}, {@code data:},
 * {@code file:}, {@code ftp:}); and the empty string.
 *
 * <p><strong>This is the canonical home of the scheme guard.</strong> Do not duplicate
 * the predicate in caller classes — call this method directly.
 */
public final class SchemeGuard {

    private SchemeGuard() {
        // Utility class — no instances.
    }

    /**
     * Returns {@code true} when {@code url} starts with {@code https://}, equals
     * {@code http://localhost} exactly, or starts with {@code http://localhost/}
     * or {@code http://localhost:}. Returns {@code false} for any other input,
     * including {@code null} (callers should null-check before calling, but the
     * method is null-safe).
     */
    public static boolean isAllowedScheme(String url) {
        if (url == null) {
            return false;
        }
        if (url.startsWith("https://")) {
            return true;
        }
        if (url.equals("http://localhost")) {
            return true;
        }
        if (url.startsWith("http://localhost/") || url.startsWith("http://localhost:")) {
            return true;
        }
        return false;
    }
}
