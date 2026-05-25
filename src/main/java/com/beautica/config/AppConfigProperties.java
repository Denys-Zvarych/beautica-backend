package com.beautica.config;

import com.beautica.notification.crypto.OutboxCipherProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        JwtConfig.class,
        OutboxCipherProperties.class,
        OtpPepperConfig.class,
        VerificationPolicyConfig.class,
        PasswordResetPolicyConfig.class
})
public class AppConfigProperties {
}
