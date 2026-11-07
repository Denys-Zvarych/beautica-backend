package com.beautica.config;

import com.beautica.review.invite.config.ReviewInviteProperties;
import org.springframework.beans.factory.annotation.Value;
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

    private static final String SMSCLUB_MODE = "smsclub";

    /**
     * @param smsMode raw {@code beautica.sms.mode}; Phase 439 audit-fix — in {@code smsclub} mode the in-flight SMS is
     *                an SMSClub call, so its timeouts bound the tick. {@code turbosms}, {@code capture} and an unset
     *                mode keep the original Turbosms check (matched case-insensitively, like {@code SmsConfig}).
     */
    ReviewInviteSendBudgetGuard(ReviewInviteProperties reviewInviteProperties, TurbosmsProperties turbosmsProperties,
                                SmsClubProperties smsClubProperties,
                                @Value("${beautica.sms.mode:turbosms}") String smsMode) {
        if (!reviewInviteProperties.enabled()) {
            return; // no send loop runs (every row is skipped INVITES_DISABLED), so there is no tick to bound
        }
        boolean smsClub = SMSCLUB_MODE.equalsIgnoreCase(smsMode == null ? "" : smsMode.strip());
        Duration connectTimeout = smsClub
                ? OutboundTimeouts.requirePositive("app.sms.smsclub.connect-timeout", smsClubProperties.getConnectTimeout())
                : OutboundTimeouts.requirePositive("app.sms.turbosms.connect-timeout", turbosmsProperties.getConnectTimeout());
        Duration readTimeout = smsClub
                ? OutboundTimeouts.requirePositive("app.sms.smsclub.read-timeout", smsClubProperties.getReadTimeout())
                : OutboundTimeouts.requirePositive("app.sms.turbosms.read-timeout", turbosmsProperties.getReadTimeout());
        reviewInviteProperties.requireSendBudgetWithin(
                Duration.ofSeconds(SchedulingConfig.SCHEDULER_SHUTDOWN_GRACE_SECONDS),
                connectTimeout,
                readTimeout,
                // Phase 408: a routed invite tries Telegram first, so its worst send carries that leg too.
                ReviewInviteProperties.TELEGRAM_LEG_WORST_CASE);
    }
}
