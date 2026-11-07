package com.beautica.notification.sms;

import com.beautica.common.TimeZones;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Phase 439 audit-fix — a staging-only ceiling on real SMSClub sends per Kyiv calendar day.
 *
 * <p>Staging Turnstile runs the always-pass Cloudflare test secret, so while the time-boxed {@code smsclub} opt-in is
 * on, a bot could pump billable SMS through the guest OTP. This in-memory counter (per instance, reset at Kyiv
 * midnight, lost on restart — deliberately cheap) bounds the damage. Registered by {@code SmsConfig} only under the
 * {@code staging} profile in {@code smsclub} mode: prod and capture mode never build one.
 *
 * <p>One instance is shared by the booking and the OTP {@link SmsClubService}, so the cap is a single daily total.
 */
public class StagingSmsDailyCap {

    /** Fixed reason token for logs and the exception message; never carries a phone or message text. */
    public static final String REASON = "STAGING_DAILY_CAP";

    private final Clock kyivClock;
    private final int dailyCap;
    private LocalDate day;
    private int sentToday;

    public StagingSmsDailyCap(Clock clock, Integer dailyCap) {
        if (dailyCap == null || dailyCap <= 0) {
            throw new IllegalStateException("app.sms.smsclub.staging-daily-cap must be positive");
        }
        this.kyivClock = clock.withZone(TimeZones.KYIV);
        this.dailyCap = dailyCap;
    }

    /** Counts one send attempt; {@code false} once today's cap is used up (the attempt is then not counted). */
    public synchronized boolean tryAcquire() {
        LocalDate today = LocalDate.now(kyivClock);
        if (!today.equals(day)) {
            day = today;
            sentToday = 0;
        }
        if (sentToday >= dailyCap) {
            return false;
        }
        sentToday++;
        return true;
    }
}
