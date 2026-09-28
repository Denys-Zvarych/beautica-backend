package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retention window for the in-app notification feed ({@code in_app_notification}) — phase 335.
 *
 * <p>{@code retentionDays} is the age (from {@code created_at}) past which a feed row, read or
 * unread, is hard-deleted by {@code InAppNotificationCleanupJob}'s daily sweep. Validated to
 * {@code [7, 365]}: below a week a client could plausibly lose a still-relevant unread item
 * before ever opening the app; above a year the table grows without bound for no product reason
 * (there is no archive/export feature — see the phase doc's Out-of-scope section).
 *
 * <p>Kept as its own tiny record, prefix {@code notification.inapp} (distinct from the sibling
 * {@link InAppNotificationCleanupProperties}, prefix {@code notification.inapp.cleanup}) — one
 * knob describes the retention POLICY, the other describes how the SWEEP JOB executes it
 * (enabled switch, batch size, per-run batch cap). Mirrors this codebase's existing split between
 * a policy's retention window and its enforcing job's own tuning knobs (e.g.
 * {@link RefreshTokenPolicyConfig#cleanupRetention()} vs {@code RefreshTokenCleanupJob}'s
 * hard-coded schedule).
 */
@ConfigurationProperties(prefix = "notification.inapp")
public record InAppNotificationRetentionProperties(int retentionDays) {

    private static final int MIN_RETENTION_DAYS = 7;
    private static final int MAX_RETENTION_DAYS = 365;

    public InAppNotificationRetentionProperties {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalStateException(
                    "notification.inapp.retention-days must be between " + MIN_RETENTION_DAYS
                            + " and " + MAX_RETENTION_DAYS + " (was " + retentionDays + ")");
        }
    }
}
