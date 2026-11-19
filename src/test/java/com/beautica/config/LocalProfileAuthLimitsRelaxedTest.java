package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local manual testing (user 2026-11-19) — the auth / OTP / contact-verify rate limits and lockouts are relaxed
 * under the {@code local} profile ONLY; {@code prod} and {@code prod,staging} still resolve the original values.
 *
 * <p>Binds from the real YAML files on the classpath, layered the way Spring Boot layers profile overlays (highest
 * precedence first), same approach as {@link LocalProfileReminder2hDefaultsTest}. Placeholders resolve against these
 * YAML sources only, never the OS environment. No Spring context, no DB. A rate-limit key ABSENT from the prod layers
 * means {@code RateLimitConfig}'s {@code @Value} default (the production value) applies.
 */
@DisplayName("Local profile — auth/OTP limits relaxed, prod/staging unchanged")
class LocalProfileAuthLimitsRelaxedTest {

    private static final String RELAXED_CAPACITY = "100000";
    private static final int RELAXED_FAILURE_THRESHOLD = 1000;
    private static final Duration RELAXED_COOLDOWN = Duration.ofSeconds(1);

    private static final String[] LOCAL = {"application-local.yml", "application.yml"};
    private static final String[] PROD = {"application-prod.yml", "application.yml"};
    private static final String[] STAGING = {"application-staging.yml", "application-prod.yml", "application.yml"};

    @ParameterizedTest(name = "app.rate-limit.{0}")
    @ValueSource(strings = {
            "register-capacity", "login-capacity", "refresh-capacity", "otp-send-capacity",
            "verify-email-capacity", "resend-verification-capacity", "forgot-password-capacity",
            "reset-password-capacity", "verify-password-reset-otp-capacity", "change-password-otp-capacity",
            "invite-validate-capacity", "invite-accept-capacity"
    })
    @DisplayName("Local profile relaxes each per-IP capacity while prod and staging keep the base value")
    void should_relaxPerIpCapacity_when_localProfile_and_keepProdValue_otherwise(String key) {
        String property = "app.rate-limit." + key;

        Optional<String> local = value(property, LOCAL);
        Optional<String> prod = value(property, PROD);
        Optional<String> staging = value(property, STAGING);
        Optional<String> base = value(property, "application.yml");

        assertThat(local).as("local " + property).contains(RELAXED_CAPACITY);
        assertThat(prod).as("prod " + property + " = base value (or absent → @Value default)").isEqualTo(base);
        assertThat(staging).as("staging " + property + " = base value (or absent → @Value default)").isEqualTo(base);
        assertThat(base).as("base " + property).isNotEqualTo(Optional.of(RELAXED_CAPACITY));
    }

    @Test
    @DisplayName("Local profile relaxes the email verification lock and resend cooldown")
    void should_relaxVerificationLockAndCooldown_when_localProfile() {
        VerificationPolicyConfig local = bindVerification(LOCAL);
        VerificationPolicyConfig prod = bindVerification(PROD);
        VerificationPolicyConfig staging = bindVerification(STAGING);

        assertThat(local.cumulativeFailureThreshold()).isEqualTo(RELAXED_FAILURE_THRESHOLD);
        assertThat(local.resendCooldown()).isEqualTo(RELAXED_COOLDOWN);
        for (VerificationPolicyConfig original : new VerificationPolicyConfig[] {prod, staging}) {
            assertThat(original.cumulativeFailureThreshold()).isEqualTo(10);
            assertThat(original.lockoutDuration()).isEqualTo(Duration.ofMinutes(15));
            assertThat(original.resendCooldown()).isEqualTo(Duration.ofSeconds(60));
        }
    }

    @Test
    @DisplayName("Local profile relaxes the password-reset OTP locks and resend cooldown")
    void should_relaxPasswordResetOtpLocksAndCooldown_when_localProfile() {
        PasswordResetOtpPolicyConfig local = bindPasswordResetOtp(LOCAL);
        PasswordResetOtpPolicyConfig prod = bindPasswordResetOtp(PROD);
        PasswordResetOtpPolicyConfig staging = bindPasswordResetOtp(STAGING);

        assertThat(local.maxAttemptsPerCode()).isEqualTo(RELAXED_FAILURE_THRESHOLD);
        assertThat(local.cumulativeFailureThreshold()).isEqualTo(RELAXED_FAILURE_THRESHOLD);
        assertThat(local.resendCooldown()).isEqualTo(RELAXED_COOLDOWN);
        for (PasswordResetOtpPolicyConfig original : new PasswordResetOtpPolicyConfig[] {prod, staging}) {
            assertThat(original.maxAttemptsPerCode()).isEqualTo(5);
            assertThat(original.cumulativeFailureThreshold()).isEqualTo(10);
            assertThat(original.lockoutDuration()).isEqualTo(Duration.ofMinutes(15));
            assertThat(original.resendCooldown()).isEqualTo(Duration.ofSeconds(60));
        }
    }

    @Test
    @DisplayName("Local profile relaxes the formerly hard-coded auth limits")
    void should_relaxFormerlyHardCodedLimits_when_localProfile() {
        AuthLimitsProperties local = bindAuthLimits(LOCAL);

        assertThat(local.phoneOtpMaxSendsPerWindow()).isEqualTo(Integer.parseInt(RELAXED_CAPACITY));
        assertThat(local.phoneOtpMaxVerifyAttempts()).isEqualTo(RELAXED_FAILURE_THRESHOLD);
        assertThat(local.emailCodeMaxVerifyAttempts()).isEqualTo(RELAXED_FAILURE_THRESHOLD);
        assertThat(local.loginMaxFailures()).isEqualTo(RELAXED_FAILURE_THRESHOLD);
        assertThat(local.otpVerifyIpCapacity()).isEqualTo(Long.parseLong(RELAXED_CAPACITY));
        assertThat(local.guestBookingIpCapacity()).isEqualTo(Long.parseLong(RELAXED_CAPACITY));
    }

    @Test
    @DisplayName("Prod, staging and test profiles keep the original auth-limit constants")
    void should_keepOldConstants_when_prodOrStagingOrTestProfile() {
        AuthLimitsProperties prod = bindAuthLimits(PROD);
        AuthLimitsProperties staging = bindAuthLimits(STAGING);
        AuthLimitsProperties test = bindAuthLimits("application-test.yml", "application.yml");

        AuthLimitsProperties oldConstants = new AuthLimitsProperties(3, 5, 5, 10, Duration.ofMinutes(15), 10, 10);
        assertThat(prod).as("prod").isEqualTo(oldConstants);
        assertThat(staging).as("prod,staging").isEqualTo(oldConstants);
        assertThat(test).as("test").isEqualTo(oldConstants);
        assertThat(AuthLimitsProperties.defaults()).as("defaults() for the legacy constructors").isEqualTo(oldConstants);
    }

    /** Binds {@code app.auth-limits}, falling back to the {@code @DefaultValue}s when no layer sets it (as Spring does). */
    private static AuthLimitsProperties bindAuthLimits(String... files) {
        return binder(files).bindOrCreate("app.auth-limits", Bindable.of(AuthLimitsProperties.class));
    }

    /** The raw value of {@code property} in the given layers; empty when no layer sets it. */
    private static Optional<String> value(String property, String... files) {
        return Optional.ofNullable(binder(files).bind(property, String.class).orElse(null));
    }

    private static VerificationPolicyConfig bindVerification(String... files) {
        return binder(files).bind("app.verification", Bindable.of(VerificationPolicyConfig.class))
                .orElseThrow(() -> new AssertionError("app.verification missing in " + String.join(", ", files)));
    }

    private static PasswordResetOtpPolicyConfig bindPasswordResetOtp(String... files) {
        return binder(files).bind("app.password-reset-otp", Bindable.of(PasswordResetOtpPolicyConfig.class))
                .orElseThrow(() -> new AssertionError("app.password-reset-otp missing in " + String.join(", ", files)));
    }

    /** A binder over the given classpath YAML files, highest precedence first. */
    private static Binder binder(String... yamlFilesHighestPrecedenceFirst) {
        MutablePropertySources sources = new MutablePropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : yamlFilesHighestPrecedenceFirst) {
            try {
                for (PropertySource<?> source : loader.load(file, new ClassPathResource(file))) {
                    sources.addLast(source);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }
}
