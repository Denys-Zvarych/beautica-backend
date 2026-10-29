package com.beautica.review.invite.support;

import com.beautica.review.invite.config.ReviewInviteProperties;

import java.time.Duration;
import java.time.LocalTime;

/** Phase 400 — {@link ReviewInviteProperties} with the production defaults, for unit tests. */
public final class ReviewInviteTestProperties {

    public static final String MIN_POLICY_VERSION = "2026-10-20";

    private ReviewInviteTestProperties() {
    }

    public static ReviewInviteProperties enabled() {
        return of(true, MIN_POLICY_VERSION);
    }

    public static ReviewInviteProperties disabled() {
        return of(false, "");
    }

    public static ReviewInviteProperties of(boolean enabled, String minPolicyVersion) {
        return new ReviewInviteProperties(enabled, minPolicyVersion, Duration.ofHours(2), LocalTime.of(21, 0),
                LocalTime.of(9, 0), LocalTime.of(10, 0), Duration.ofHours(72), Duration.ofDays(14), 50, 3,
                ReviewInviteProperties.DEFAULT_SMS_TEXT, ReviewInviteProperties.DEFAULT_SEND_BUDGET);
    }
}
