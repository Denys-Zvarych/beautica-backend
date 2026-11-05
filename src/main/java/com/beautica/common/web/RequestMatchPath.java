package com.beautica.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

/**
 * Resolves the path a servlet FILTER must match its rules on: the request's DECODED and NORMALIZED path — mirroring
 * how Spring MVC routes the request — instead of the raw, percent-encoded, un-normalized
 * {@link HttpServletRequest#getRequestURI()}. Promoted from {@code AuthRateLimitFilter#resolveMatchPath} (phase 436
 * audit-fix cycle 2) so every path-scoped filter shares one implementation.
 *
 * <p>The servlet container hands back {@code getRequestURI()} exactly as received: still percent-encoded and not
 * collapsed. Spring MVC, however, routes on the decoded/normalized path, so matching a rule on the raw URI lets a
 * caller reach a guarded handler with an equivalent spelling that skips the rule — e.g. {@code POST
 * /api/v1/auth/logi%6e} (routes to login, unthrottled credential stuffing), {@code GET
 * /api/v1/masters/{id}/working%2ddays} or {@code POST /api/v1/book/canc%65l/{token}}. {@code StrictHttpFirewall} does
 * not reject these.
 *
 * <p>Filters run BEFORE the {@code DispatcherServlet} parses and caches the request path, so
 * {@code ServletRequestPathUtils.getCachedPath(request)} is not yet populated. The path is therefore decoded /
 * normalized independently via {@link UrlPathHelper} (which percent-decodes ONCE, strips {@code ;matrix} content, and
 * collapses duplicate slashes) and then any {@code .}/{@code ..} segments that survive decoding (e.g. {@code %2e%2e})
 * are folded with {@link StringUtils#cleanPath}. {@code cleanPath} performs no decoding, so there is no double-decode
 * (which would itself open a bypass). Verified against spring-web 6.2.6: {@code working%2ddays -> working-days},
 * {@code logi%6e -> login}, {@code /auth/./login -> /auth/login}, {@code //auth -> /auth}, {@code %2e%2e -> ..}
 * folded, while unencoded paths pass through byte-for-byte.
 */
public final class RequestMatchPath {

    // urlDecode + removeSemicolonContent are UrlPathHelper defaults; set explicitly so the security-critical decode
    // step is self-documenting and cannot be silently disabled by a future default change. Stateless after
    // construction and thread-safe for the read-only getPathWithinApplication call, so one shared instance is correct.
    private static final UrlPathHelper MATCH_PATH_HELPER = createMatchPathHelper();

    private RequestMatchPath() {
    }

    /** The decoded, normalized path within the application, for filter rule matching. */
    public static String of(HttpServletRequest request) {
        return StringUtils.cleanPath(MATCH_PATH_HELPER.getPathWithinApplication(request));
    }

    private static UrlPathHelper createMatchPathHelper() {
        UrlPathHelper helper = new UrlPathHelper();
        helper.setUrlDecode(true);
        helper.setRemoveSemicolonContent(true);
        return helper;
    }
}
