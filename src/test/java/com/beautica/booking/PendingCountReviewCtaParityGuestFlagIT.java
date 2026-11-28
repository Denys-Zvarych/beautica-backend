package com.beautica.booking;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link PendingCountReviewCtaParityIT}'s per-row matrix with the guest rating flag ON
 * ({@code APP_GUEST_CLIENT_RATING_ENABLED}, REV-41): guest / walk-in rows with a live phone gain the CTA and
 * must gain the count with it.
 */
@TestPropertySource(properties = "app.client-rating.guest-enabled=true")
@DisplayName("Pending badge toRateClient <=> providerCanReviewClient CTA, per row, guest rating flag ON")
class PendingCountReviewCtaParityGuestFlagIT extends PendingCountReviewCtaParityIT {

    @Override
    protected boolean expectedGuestFlag() {
        return true;
    }
}
