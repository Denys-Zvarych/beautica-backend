package com.beautica.booking.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phase 412 — the guest's public cancel link {@code {app.frontend.base-url}/book/cancel/{token}}, for the STAFF
 * walk-in confirmation SMS AND the LINK guest confirmation ({@code GuestBookingService}) — the single builder, so a
 * walk-in lands on the identical website cancel page (website 024). The raw token
 * only ever travels inside this URL; it is never logged.
 */
@Component
public class GuestCancelUrlBuilder {

    private static final String CANCEL_PATH = "/book/cancel/";

    private final String frontendBaseUrl;

    public GuestCancelUrlBuilder(@Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public String build(UUID cancelToken) {
        return frontendBaseUrl + CANCEL_PATH + cancelToken;
    }
}
