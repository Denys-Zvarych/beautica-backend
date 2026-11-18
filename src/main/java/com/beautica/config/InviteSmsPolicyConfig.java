package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * B520 (AUTH-52, AUTH-D14, SAL-48, D5) — staff invite SMS policy, prefix {@code app.invite.sms}.
 *
 * @param perInviterPerHour invite SMS one user may send per rolling hour (default 10); beyond → 429
 *                          {@code INVITE_SMS_LIMIT}
 * @param perSalonPerDay    invite SMS one salon may send per rolling 24 h (default 30); beyond → 429
 * @param retention         an unaccepted phone invite is deleted this long after it expired or was revoked (30 days,
 *                          stated by the privacy policy, W042 P10)
 * @param perPhonePerDay    invite SMS one phone may receive per rolling 24 h across ALL salons and inviters (AUTH-D14
 *                          per-phone limit, default 3); beyond → 429
 */
@ConfigurationProperties(prefix = "app.invite.sms")
public record InviteSmsPolicyConfig(
        int perInviterPerHour,
        int perSalonPerDay,
        Duration retention,
        int perPhonePerDay
) {

    /** AUTH-D14 per-phone default when {@code app.invite.sms.per-phone-per-day} is absent. */
    public static final int DEFAULT_PER_PHONE_PER_DAY = 3;

    @ConstructorBinding
    public InviteSmsPolicyConfig {
        if (perInviterPerHour < 1 || perSalonPerDay < 1 || perPhonePerDay < 1) {
            throw new IllegalStateException("app.invite.sms caps must be at least 1");
        }
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalStateException("app.invite.sms.retention must be a positive duration");
        }
    }

    /** The pre-AUTH-D14 shape (tests, callers that do not care about the per-phone cap): per-phone cap = default. */
    public InviteSmsPolicyConfig(int perInviterPerHour, int perSalonPerDay, Duration retention) {
        this(perInviterPerHour, perSalonPerDay, retention, DEFAULT_PER_PHONE_PER_DAY);
    }
}
