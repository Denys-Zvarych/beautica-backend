package com.beautica.booking.service;

import com.beautica.booking.domain.GuestCancelToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 412 — the guest's public cancel link, for the STAFF
 * walk-in confirmation SMS AND the LINK guest confirmation ({@code GuestBookingService}) — the single builder, so a
 * walk-in lands on the identical website cancel page (website 024). The raw token
 * only ever travels inside this URL; it is never logged.
 *
 * <p>Phase 435 follow-up — the link is {@code {app.frontend.base-url}/c/{token}} (was {@code /book/cancel/{token}},
 * ~70 chars = one whole UCS-2 SMS part). The website serves {@code /c/{token}} exactly like
 * {@code /book/cancel/{token}}; old links keep working there.
 */
@Component
public class GuestCancelUrlBuilder {

    /** Phase 435 follow-up — the short website alias; behaves exactly like {@code /book/cancel/{token}}. */
    private static final String CANCEL_PATH = "/c/";

    private final String frontendBaseUrl;

    public GuestCancelUrlBuilder(@Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /** {@code {base}/c/{token}} — for a short token AND a legacy UUID one (the website alias accepts both). */
    public String build(GuestCancelToken cancelToken) {
        return frontendBaseUrl + CANCEL_PATH + cancelToken.value();
    }
}
