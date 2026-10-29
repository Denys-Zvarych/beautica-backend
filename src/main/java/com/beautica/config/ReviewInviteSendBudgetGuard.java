package com.beautica.config;

import com.beautica.review.invite.config.ReviewInviteProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Phase 400 audit cycle 2 — fails the boot when the review-invite send budget plus one in-flight Turbosms call
 * (connect + read timeout) would outlast the scheduler shutdown grace ({@link SchedulingConfig}). The send loop checks
 * its deadline before each SMS, so the worst tick is {@code sendBudget + connectTimeout + readTimeout}; past the grace
 * the scheduler is torn down mid-send and the row is left {@code SENT} with an SMS that may or may not have gone out.
 *
 * <p>Derived from the configured timeouts, not hard-coded: raising {@code app.sms.turbosms.read-timeout} without
 * lowering {@code app.booking.review-invite.send-budget} is caught here. Lives in {@code config} because the grace is a
 * package-private {@link SchedulingConfig} constant. Skipped when invites are disabled: no send loop runs, so a
 * Turbosms timeout tuned for other SMS traffic must not block the boot.
 */
@Component
class ReviewInviteSendBudgetGuard {

    ReviewInviteSendBudgetGuard(ReviewInviteProperties reviewInviteProperties, TurbosmsProperties turbosmsProperties) {
        if (!reviewInviteProperties.enabled()) {
            return; // no send loop runs (every row is skipped INVITES_DISABLED), so there is no tick to bound
        }
        reviewInviteProperties.requireSendBudgetWithin(
                Duration.ofSeconds(SchedulingConfig.SCHEDULER_SHUTDOWN_GRACE_SECONDS),
                OutboundTimeouts.requirePositive(
                        "app.sms.turbosms.connect-timeout", turbosmsProperties.getConnectTimeout()),
                OutboundTimeouts.requirePositive(
                        "app.sms.turbosms.read-timeout", turbosmsProperties.getReadTimeout()));
    }
}
