package com.beautica.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * B546 (REV-41, D5) — provider-to-client rating settings, bound from {@code app.client-rating}.
 *
 * @param guestEnabled feature flag {@code APP_GUEST_CLIENT_RATING_ENABLED}, default {@code false}. Off: a guest / walk-in
 *                     booking with no account stays unrateable (today's 400, flag {@code false}); readouts are not
 *                     gated (with the flag off no phone-keyed rating can be written). Prod turns it on only once the
 *                     privacy bump W051 is live (SUP-33).
 */
@ConfigurationProperties(prefix = "app.client-rating")
public record ClientRatingProperties(@DefaultValue("false") boolean guestEnabled) {
}
