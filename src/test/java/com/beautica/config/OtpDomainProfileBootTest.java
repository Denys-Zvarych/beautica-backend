package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 389 QA — {@code beautica.sms.otp-domain} boot validation against the REAL committed
 * {@code application*.yml} (not hand-built properties) with {@code @Validated} binding switched on, and the
 * override fed the way Railway feeds it: an OS environment variable (relaxed binding), not a Spring property.
 *
 * <p>{@code DeploymentPropertiesOtpDomainTest} proves the constraint on a bare binder; this class proves the
 * deployed profiles: prod's pinned {@code beautica.app} passes, a malformed {@code BEAUTICA_SMS_OTPDOMAIN}
 * on prod fails startup, and staging's {@code OTP_SMS_DOMAIN} placeholder is validated the same way.
 */
@DisplayName("prod / prod,staging — otp-domain from the real YAML is validated at boot (Phase 389 QA)")
class OtpDomainProfileBootTest {

    @EnableConfigurationProperties(DeploymentProperties.class)
    static class Config {
    }

    private static ApplicationContextRunner runner(String profiles, Map<String, Object> osEnv) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(osEnvironment(osEnv))
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Config.class)
                .withPropertyValues("spring.profiles.active=" + profiles);
    }

    /** A {@link SystemEnvironmentPropertySource} named like the OS one so Boot applies env-var relaxed binding. */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> osEnvironment(Map<String, Object> vars) {
        return ctx -> ctx.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "qa-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, vars));
    }

    @Test
    @DisplayName("prod — pinned beautica.app passes validation and binds")
    void should_bootWithBeauticaApp_when_prodProfileHasNoOverride() {
        runner("prod", Map.of()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(DeploymentProperties.class).sms().otpDomainHost()).contains("beautica.app");
        });
    }

    @Test
    @DisplayName("prod — BEAUTICA_SMS_OTPDOMAIN with a scheme fails startup")
    void should_failBoot_when_prodEnvVarOtpDomainHasScheme() {
        runner("prod", Map.of("BEAUTICA_SMS_OTPDOMAIN", "https://beautica.app")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("otpDomain");
        });
    }

    @Test
    @DisplayName("prod — BEAUTICA_SMS_OTPDOMAIN over 18 chars (two SMS segments) fails startup")
    void should_failBoot_when_prodEnvVarOtpDomainTooLong() {
        runner("prod", Map.of("BEAUTICA_SMS_OTPDOMAIN", "staging.beautica.app")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("otpDomain");
        });
    }

    @Test
    @DisplayName("prod,staging — OTP_SMS_DOMAIN over 18 chars fails startup (placeholder is validated, not passed through)")
    void should_failBoot_when_stagingOtpSmsDomainTooLong() {
        runner("prod,staging", Map.of("OTP_SMS_DOMAIN", "staging.beautica.app")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("otpDomain");
        });
    }

    @Test
    @DisplayName("prod,staging — OTP_SMS_DOMAIN=stg.beautica.app overrides prod's beautica.app")
    void should_bindStagingHost_when_stagingOtpSmsDomainValid() {
        runner("prod,staging", Map.of("OTP_SMS_DOMAIN", "stg.beautica.app")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(DeploymentProperties.class).sms().otpDomainHost()).contains("stg.beautica.app");
        });
    }

    @Test
    @DisplayName("prod,staging — OTP_SMS_DOMAIN unset boots with no domain line (never inherits beautica.app)")
    void should_bindEmpty_when_stagingOtpSmsDomainUnset() {
        runner("prod,staging", Map.of()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(DeploymentProperties.class).sms().otpDomainHost()).isEmpty();
        });
    }
}
