package com.beautica.notification;

import java.util.List;

/**
 * The ONE shared expected-copy fixture for the B545 (SVC-06 / SVC-61) operator-side catalog request copy: the
 * notification-only "new category request" / "new service-type suggestion" e-mails and the 410 page the retired
 * e-mail links open. Pre-push gate rule 4 (orchestrate.md Step 2.9): tests take the expected lines from here, never a
 * re-typed literal, so a copy change breaks in one place.
 *
 * <p>Values are written out on purpose (not read from {@code EmailService}, {@code AdminRequestsPortalLink} or the
 * templates): an assertion against the renderer's own constant would pass whatever the renderer says. They mirror
 * {@code templates/email/category-request.html}, {@code templates/email/service-type-suggestion.html},
 * {@code templates/catalog-request-retired.html} and the subjects in {@code EmailService}.
 */
public final class CatalogRequestOperatorEmailExpectedCopy {

    /** The portal page every operator link points at: {@code {FRONTEND_BASE_URL}} + this path. */
    public static final String PORTAL_PATH = "/admin/requests";

    /** The one button of both e-mails and of the retired-link page. */
    public static final String PORTAL_BUTTON = "Відкрити заявки";

    /** The footer line of both e-mails: the e-mail only announces, the portal decides. */
    public static final String NOTIFICATION_ONLY_FOOTER =
            "Цей лист лише повідомляє про нову заявку. Рішення приймається тільки в порталі.";

    /** The heading (and title) of the 410 page every retired e-mail link opens. */
    public static final String RETIRED_PAGE_HEADING = "Заявки тепер розглядаються в порталі";

    /** The retired page's promise that the link changed nothing. */
    public static final String RETIRED_PAGE_NOTHING_CHANGED = "Нічого не змінено.";

    /**
     * Fragments that must never appear in an operator e-mail body: the old token, the old review / approve / reject
     * links and any API path (the only link left is the portal page on the website origin).
     */
    public static final List<String> RETIRED_LINK_MARKERS =
            List.of("token", "/review", "/approve", "/reject", "/api/v1/");

    private CatalogRequestOperatorEmailExpectedCopy() {
    }

    /** The portal URL for a given {@code FRONTEND_BASE_URL} origin (no trailing slash). */
    public static String portalUrl(String frontendOrigin) {
        return frontendOrigin + PORTAL_PATH;
    }

    /** The anchor the e-mails and the retired page render for the portal link. */
    public static String portalHref(String frontendOrigin) {
        return "href=\"" + portalUrl(frontendOrigin) + "\"";
    }

    /** {@code «Beautica: Новий запит на категорію послуги — {categoryName}»} (unchanged by B545). */
    public static String categoryRequestSubject(String categoryName) {
        return "Beautica: Новий запит на категорію послуги — " + categoryName;
    }

    /** {@code «Beautica: Нова пропозиція типу послуги — {suggestedName}»} (unchanged by B545). */
    public static String suggestionSubject(String suggestedName) {
        return "Beautica: Нова пропозиція типу послуги — " + suggestedName;
    }
}
