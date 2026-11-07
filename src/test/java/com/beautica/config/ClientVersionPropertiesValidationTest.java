package com.beautica.config;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 437 — {@code app.client-version} binds valid values and fails boot on {@code latest < min} (also when latest
 * is the {@code 0} default), a negative code, a code above the Play {@code versionCode} ceiling, or a store URL off
 * {@code https://play.google.com/}; the effective values are logged once at startup. Config-only, no DB.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("app.client-version — binding and boot validation (Phase 437)")
class ClientVersionPropertiesValidationTest {

    private static final String PREFIX = "app.client-version.android.";
    private static final String PLAY_URL = "https://play.google.com/store/apps/details?id=com.beautica.app";

    @Configuration
    @EnableConfigurationProperties(ClientVersionProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Props.class);

    private ApplicationContextRunner with(String min, String latest, String storeUrl) {
        return runner.withPropertyValues(
                PREFIX + "min-version-code=" + min,
                PREFIX + "latest-version-code=" + latest,
                PREFIX + "store-url=" + storeUrl);
    }

    @Test
    @DisplayName("valid values bind")
    void should_bindValues_when_valid() {
        with("12", "15", PLAY_URL).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ClientVersionProperties.Android android = ctx.getBean(ClientVersionProperties.class).android();

            assertThat(android.minVersionCode()).isEqualTo(12);
            assertThat(android.latestVersionCode()).isEqualTo(15);
            assertThat(android.storeUrl()).isEqualTo(PLAY_URL);
        });
    }

    @Test
    @DisplayName("min == latest == 0 (the shipped defaults) bind")
    void should_bind_when_minEqualsLatest() {
        with("0", "0", PLAY_URL).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("latest < min fails boot")
    void should_failBoot_when_latestBelowMin() {
        with("10", "9", PLAY_URL).run(ctx -> assertThat(ctx).getFailure()
                .rootCause().hasMessageContaining("latestAtLeastMin"));
    }

    @Test
    @DisplayName("min > 0 with latest left at the 0 default fails boot, naming both env vars")
    void should_failBoot_when_minSetAndLatestDefault() {
        with("57", "0", PLAY_URL).run(ctx -> assertThat(ctx).getFailure().rootCause()
                .hasMessageContaining("APP_ANDROID_LATEST_VERSION_CODE")
                .hasMessageContaining("APP_ANDROID_MIN_VERSION_CODE"));
    }

    @Test
    @DisplayName("min == latest == the Play versionCode ceiling binds")
    void should_bind_when_codesAtPlayCeiling() {
        String max = String.valueOf(ClientVersionProperties.Android.MAX_VERSION_CODE);

        with(max, max, PLAY_URL).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("min-version-code above the Play versionCode ceiling fails boot")
    void should_failBoot_when_minAboveCeiling() {
        String overMax = String.valueOf(ClientVersionProperties.Android.MAX_VERSION_CODE + 1);

        with(overMax, overMax, PLAY_URL).run(ctx -> assertThat(ctx).getFailure()
                .rootCause().hasMessageContaining("minVersionCode"));
    }

    @Test
    @DisplayName("latest-version-code above the Play versionCode ceiling fails boot")
    void should_failBoot_when_latestAboveCeiling() {
        String overMax = String.valueOf(ClientVersionProperties.Android.MAX_VERSION_CODE + 1);

        with("0", overMax, PLAY_URL).run(ctx -> assertThat(ctx).getFailure()
                .rootCause().hasMessageContaining("latestVersionCode"));
    }

    @Test
    @DisplayName("the effective android min / latest / storeUrl are logged once at startup")
    void should_logEffectiveValues_when_contextStarts(CapturedOutput output) {
        with("12", "15", PLAY_URL).withUserConfiguration(ClientVersionStartupLogger.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();

            String expected = "minVersionCode=12, latestVersionCode=15, storeUrl=" + PLAY_URL;

            // com.beautica logs at TRACE in tests, so pin the level on the line itself, not just its presence.
            assertThat(output.getOut().lines().filter(line -> line.contains(expected)))
                    .singleElement(InstanceOfAssertFactories.STRING).contains(" INFO ");
        });
    }

    @Test
    @DisplayName("negative min-version-code fails boot")
    void should_failBoot_when_minNegative() {
        with("-1", "0", PLAY_URL).run(ctx -> assertThat(ctx).getFailure()
                .rootCause().hasMessageContaining("minVersionCode"));
    }

    @Test
    @DisplayName("negative latest-version-code fails boot")
    void should_failBoot_when_latestNegative() {
        with("-5", "-1", PLAY_URL).run(ctx -> assertThat(ctx).getFailure()
                .rootCause().hasMessageContaining("latestVersionCode"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://evil",
            "http://play.google.com/store/apps/details?id=com.beautica.app",
            "https://evil.example/store",
            "https://play.google.com.evil.example/store",
            "javascript:alert(1)",
            "https://play.google.com/store /x"})
    @DisplayName("store URL not on https://play.google.com/ fails boot")
    void should_failBoot_when_storeUrlNotPlay(String storeUrl) {
        with("0", "0", storeUrl).run(ctx -> assertThat(ctx).getFailure()
                .rootCause().hasMessageContaining("storeUrl"));
    }

    @Test
    @DisplayName("missing android block fails boot")
    void should_failBoot_when_androidBlockMissing() {
        runner.run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("android"));
    }
}
