package com.beautica.service.controller;

import com.beautica.service.service.AdminRequestsPortalLink;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * B545 (SVC-61) — the six former emailed review / approve / reject links of the category-request and service-type
 * suggestion workflows. Email decisions are retired; the catalog portal is the only decision path (SVC-06).
 *
 * <p>The mappings stay, stay anonymous ({@code SecurityConfig}) and rate-limited ({@code AuthRateLimitFilter}), so a
 * link in an email sent before the deploy opens a clear page instead of a 401/404/500. Every route answers
 * <b>410 Gone</b> with the same page pointing to the portal. No handler binds the {@code token} (path segment, query or
 * form) or any other parameter, touches the database or takes a decision: there is no token oracle and no input that
 * can turn into a 4xx/5xx other than this 410.
 */
@Controller
@RequiredArgsConstructor
public class RetiredCatalogRequestLinksController {

    static final String VIEW = "catalog-request-retired";

    // The old links still carry a raw (now meaningless) token in the URL: keep it out of caches and out of the
    // Referer the portal link would send. no-referrer is safe now: the page has no form that posts back.
    private static final String CACHE_CONTROL_NO_STORE = "no-store, no-cache, must-revalidate";
    private static final String REFERRER_POLICY_HEADER = "Referrer-Policy";
    private static final String REFERRER_POLICY_NO_REFERRER = "no-referrer";

    private final AdminRequestsPortalLink adminRequestsPortalLink;

    @ModelAttribute
    void applyRetiredPageHeaders(HttpServletResponse response, Model model) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
        response.setHeader(REFERRER_POLICY_HEADER, REFERRER_POLICY_NO_REFERRER);
        model.addAttribute("portalUrl", adminRequestsPortalLink.requestsUrl());
    }

    @GetMapping({
            "/api/v1/service-categories/requests/review/{token}",
            // An old link cut off before its token: still the portal page, not a 404.
            "/api/v1/service-categories/requests/review/",
            "/api/v1/service-types/suggestions/review"})
    @ResponseStatus(HttpStatus.GONE)
    public String review() {
        return VIEW;
    }

    @PostMapping({
            "/api/v1/service-categories/requests/approve",
            "/api/v1/service-categories/requests/reject",
            "/api/v1/service-types/suggestions/approve",
            "/api/v1/service-types/suggestions/reject"})
    @ResponseStatus(HttpStatus.GONE)
    public String decide() {
        return VIEW;
    }
}
