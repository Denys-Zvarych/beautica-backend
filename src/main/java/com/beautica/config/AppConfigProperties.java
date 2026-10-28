package com.beautica.config;

import com.beautica.auth.turnstile.TurnstileProperties;
import com.beautica.booking.closure.ClosureReminderProperties;
import com.beautica.booking.retention.GuestPiiRetentionProperties;
import com.beautica.moderation.config.ModerationProperties;
import com.beautica.notification.crypto.OutboxCipherProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        JwtConfig.class,
        OutboxCipherProperties.class,
        OtpPepperConfig.class,
        VerificationPolicyConfig.class,
        PasswordResetPolicyConfig.class,
        PasswordResetOtpPolicyConfig.class,
        RefreshTokenPolicyConfig.class,
        InternalApiKeyProperties.class,
        PublicBaseUrlProperties.class,
        SupportProperties.class,
        TurbosmsProperties.class,
        BookingSmsProperties.class,
        OutboxReclaimPolicyConfig.class,
        ClosureReminderProperties.class,
        InAppNotificationRetentionProperties.class,
        InAppNotificationCleanupProperties.class,
        DeploymentProperties.class,
        GuestPiiRetentionProperties.class,
        BookingLinksProperties.class,
        BookingInfoWorkerProperties.class,
        TurnstileProperties.class,
        ModerationProperties.class
})
public class AppConfigProperties {
}
