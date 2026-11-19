package com.beautica.config;

import com.beautica.auth.LoginFailureLimiter;
import com.beautica.auth.filter.AuthRateLimitFilter;
import com.beautica.auth.phoneotp.PhoneOtp;
import com.beautica.auth.phoneotp.PhoneOtpService;
import com.beautica.user.email.NotificationEmailService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Auth / OTP caps that used to be hard-coded constants (user 2026-11-19). Every default IS the old constant, and no
 * committed profile except {@code local} sets {@code app.auth-limits.*}, so prod, staging and test behave exactly as
 * before. {@code application-local.yml} raises them for manual testing on a phone (all traffic shares 127.0.0.1).
 *
 * <ul>
 *   <li>{@code phone-otp-max-sends-per-window} — OTP sends per phone / per signed-in account per 15 min ({@link PhoneOtpService});</li>
 *   <li>{@code phone-otp-max-verify-attempts} — wrong tries before a phone OTP code is invalidated;</li>
 *   <li>{@code email-code-max-verify-attempts} — wrong tries before an email-change code expires ({@link NotificationEmailService});</li>
 *   <li>{@code login-max-failures} / {@code login-lock-window} — login and current-password lockout ({@link LoginFailureLimiter});</li>
 *   <li>{@code otp-verify-ip-capacity} / {@code guest-booking-ip-capacity} — per-IP buckets per 15 min ({@link AuthRateLimitFilter});</li>
 *   <li>{@code phone-failed-verifies-per-day} — wrong SMS codes per phone per rolling 24 h, every purpose (AUTH-D26 c);</li>
 *   <li>{@code otp-resend-cooldown-seconds} — gap between two SMS codes to one phone for one purpose (AUTH-D27 a).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.auth-limits")
public record AuthLimitsProperties(
        @DefaultValue("" + PhoneOtpService.MAX_SENDS_PER_WINDOW) int phoneOtpMaxSendsPerWindow,
        @DefaultValue("" + PhoneOtp.MAX_VERIFY_ATTEMPTS) int phoneOtpMaxVerifyAttempts,
        @DefaultValue("" + NotificationEmailService.MAX_ATTEMPTS_PER_CODE) int emailCodeMaxVerifyAttempts,
        @DefaultValue("" + LoginFailureLimiter.MAX_FAILURES) int loginMaxFailures,
        @DefaultValue("15m") Duration loginLockWindow,
        @DefaultValue("" + AuthRateLimitFilter.OTP_VERIFY_CAPACITY) long otpVerifyIpCapacity,
        @DefaultValue("" + AuthRateLimitFilter.GUEST_BOOKING_CAPACITY) long guestBookingIpCapacity,
        @DefaultValue("" + PhoneOtpService.MAX_FAILED_VERIFIES_PER_DAY) int phoneFailedVerifiesPerDay,
        @DefaultValue("" + PhoneOtpService.RESEND_COOLDOWN_SECONDS) int otpResendCooldownSeconds
) {

    public AuthLimitsProperties {
        requirePositive(phoneOtpMaxSendsPerWindow, "phone-otp-max-sends-per-window");
        requirePositive(phoneOtpMaxVerifyAttempts, "phone-otp-max-verify-attempts");
        requirePositive(emailCodeMaxVerifyAttempts, "email-code-max-verify-attempts");
        requirePositive(loginMaxFailures, "login-max-failures");
        requirePositive(otpVerifyIpCapacity, "otp-verify-ip-capacity");
        requirePositive(guestBookingIpCapacity, "guest-booking-ip-capacity");
        requirePositive(phoneFailedVerifiesPerDay, "phone-failed-verifies-per-day");
        requirePositive(otpResendCooldownSeconds, "otp-resend-cooldown-seconds");
        if (loginLockWindow == null || loginLockWindow.isNegative() || loginLockWindow.isZero()) {
            throw new IllegalStateException("app.auth-limits.login-lock-window must be a positive duration");
        }
    }

    /** The production values — what the pre-existing (test) constructors of the four consumers use. */
    public static AuthLimitsProperties defaults() {
        return new AuthLimitsProperties(
                PhoneOtpService.MAX_SENDS_PER_WINDOW,
                PhoneOtp.MAX_VERIFY_ATTEMPTS,
                NotificationEmailService.MAX_ATTEMPTS_PER_CODE,
                LoginFailureLimiter.MAX_FAILURES,
                LoginFailureLimiter.LOCK_WINDOW,
                AuthRateLimitFilter.OTP_VERIFY_CAPACITY,
                AuthRateLimitFilter.GUEST_BOOKING_CAPACITY,
                PhoneOtpService.MAX_FAILED_VERIFIES_PER_DAY,
                PhoneOtpService.RESEND_COOLDOWN_SECONDS);
    }

    private static void requirePositive(long value, String key) {
        if (value < 1) {
            throw new IllegalStateException("app.auth-limits." + key + " must be >= 1");
        }
    }
}
