package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed config for the SMSClub SMS provider (Phase 439), bound from {@code app.sms.smsclub}.
 *
 * <p>Same degradation contract as {@link TurbosmsProperties}: the {@code token} ({@code SMSCLUB_TOKEN}) is NOT
 * {@code @NotBlank} — the app boots without it and {@code SmsClubService#send} raises a clean
 * {@code SmsDeliveryException}. Prod presence is enforced by {@code EnvironmentFingerprintGuard}
 * ({@code prod-smsclub-token-*}), only when {@code beautica.sms.mode=smsclub}.
 *
 * <p>The token is a secret and is never logged.
 */
@ConfigurationProperties(prefix = "app.sms.smsclub")
public class SmsClubProperties {

    /** Default alpha-name registered with SMSCLUB ({@code src_addr}). */
    static final String DEFAULT_SENDER_NAME = "Beauty";
    /** Delivery lifetime for booking / reminder / review SMS, minutes. */
    static final int DEFAULT_LIFETIME_MINUTES = 60;
    /** Delivery lifetime for the guest OTP, minutes; must not exceed the OTP code TTL (asserted in a test). */
    static final int DEFAULT_OTP_LIFETIME_MINUTES = 10;
    /** The only send endpoint prod may use ({@code EnvironmentFingerprintGuard} rule {@code prod-smsclub-base-url}). */
    /** Staging-only real-send ceiling per Kyiv day while the {@code smsclub} opt-in is on (Phase 439 audit-fix). */
    static final int DEFAULT_STAGING_DAILY_CAP = 50;
    static final String DEFAULT_BASE_URL = "https://im.smsclub.mobi/sms/send";

    /** Provider API token (Bearer). Blank when SMSClub is unconfigured. */
    private String token = "";

    /** Alpha-name sent as {@code src_addr}. */
    private String senderName = DEFAULT_SENDER_NAME;

    /** Send endpoint; overridable only so tests can point at WireMock. */
    private String baseUrl = DEFAULT_BASE_URL;

    /** TCP connect timeout (validated positive by {@code SmsClubService}'s constructor). Default 3s. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** Response (read) timeout. Default 5s. */
    private Duration readTimeout = Duration.ofSeconds(5);

    /** {@code lifetime} for the gated booking {@code SmsService}, minutes. */
    private Integer lifetimeMinutes = DEFAULT_LIFETIME_MINUTES;

    /** {@code lifetime} for the ungated OTP sender, minutes. */
    private Integer otpLifetimeMinutes = DEFAULT_OTP_LIFETIME_MINUTES;

    /** Staging-only daily send cap ({@code StagingSmsDailyCap}); ignored outside the {@code staging} profile. */
    private Integer stagingDailyCap = DEFAULT_STAGING_DAILY_CAP;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Integer getLifetimeMinutes() {
        return lifetimeMinutes;
    }

    public void setLifetimeMinutes(Integer lifetimeMinutes) {
        this.lifetimeMinutes = lifetimeMinutes;
    }

    public Integer getOtpLifetimeMinutes() {
        return otpLifetimeMinutes;
    }

    public void setOtpLifetimeMinutes(Integer otpLifetimeMinutes) {
        this.otpLifetimeMinutes = otpLifetimeMinutes;
    }

    public Integer getStagingDailyCap() {
        return stagingDailyCap;
    }

    public void setStagingDailyCap(Integer stagingDailyCap) {
        this.stagingDailyCap = stagingDailyCap;
    }
}
