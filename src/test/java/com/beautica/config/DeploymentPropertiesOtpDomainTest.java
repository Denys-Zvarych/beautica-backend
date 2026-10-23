package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 389 — {@code beautica.sms.otp-domain} binding: a non-bare host fails boot; blank means no domain line. */
@DisplayName("DeploymentProperties — beautica.sms.otp-domain validation (Phase 389)")
class DeploymentPropertiesOtpDomainTest {

    @EnableConfigurationProperties(DeploymentProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @ParameterizedTest
    @ValueSource(strings = {"https://x", "x:8080", "beautica.app/book", "BEAUTICA.APP", "beautica",
            "beautica..app", "beautica_app.ua", "a-very-long-staging.beautica.app",
            "staging.beautica.app", "stage1.beautica.app"})
    @DisplayName("non-bare or over-18-char host fails context startup")
    void should_failBoot_when_otpDomainNotBareHost(String host) {
        runner.withPropertyValues("beautica.sms.otp-domain=" + host).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("otpDomain");
        });
    }

    @Test
    @DisplayName("beautica.app binds")
    void should_bindHost_when_otpDomainValid() {
        runner.withPropertyValues("beautica.sms.otp-domain=beautica.app").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DeploymentProperties.class).sms().otpDomainHost()).contains("beautica.app");
        });
    }

    @Test
    @DisplayName("exactly 18-char host binds (boundary)")
    void should_bindHost_when_otpDomainAtMaximumLength() {
        String longestHost = "a".repeat(OtpDomainRules.MAX_HOST_LENGTH - 4) + ".app";

        runner.withPropertyValues("beautica.sms.otp-domain=" + longestHost).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DeploymentProperties.class).sms().otpDomainHost()).contains(longestHost);
        });
    }

    @Test
    @DisplayName("blank or absent binds to no domain")
    void should_bindEmpty_when_otpDomainBlankOrAbsent() {
        runner.withPropertyValues("beautica.sms.otp-domain=").run(context ->
                assertThat(context.getBean(DeploymentProperties.class).sms().otpDomainHost()).isEmpty());
        runner.run(context ->
                assertThat(context.getBean(DeploymentProperties.class).sms().otpDomainHost()).isEmpty());
    }
}
