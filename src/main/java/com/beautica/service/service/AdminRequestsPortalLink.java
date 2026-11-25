package com.beautica.service.service;

import com.beautica.common.util.SchemeGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * B545 (SVC-06 / SVC-61) — the catalog portal's pending-requests page, {@code {FRONTEND_BASE_URL}/admin/requests}.
 * The one place this link is built: the operator's request emails point at it, and the retired email review / approve /
 * reject links render it on their 410 page. The origin is the existing {@code app.frontend.base-url}; no new env var.
 */
@Component
public class AdminRequestsPortalLink {

    static final String REQUESTS_PATH = "/admin/requests";

    private final String requestsUrl;

    public AdminRequestsPortalLink(@Value("${app.frontend.base-url}") String frontendBaseUrl) {
        // Same scheme rule as every other FRONTEND_BASE_URL link (InviteService): https, or http://localhost in dev.
        if (!SchemeGuard.isAllowedScheme(frontendBaseUrl)) {
            throw new IllegalStateException("app.frontend.base-url must use HTTPS scheme for non-localhost origins");
        }
        this.requestsUrl = stripTrailingSlashes(frontendBaseUrl) + REQUESTS_PATH;
    }

    /** {@code https://beautica.app/} (a common env-var typo) must not produce {@code //admin/requests}. */
    private static String stripTrailingSlashes(String origin) {
        int end = origin.length();
        while (end > 0 && origin.charAt(end - 1) == '/') {
            end--;
        }
        return origin.substring(0, end);
    }

    /** The absolute portal URL of the pending-requests list. */
    public String requestsUrl() {
        return requestsUrl;
    }
}
