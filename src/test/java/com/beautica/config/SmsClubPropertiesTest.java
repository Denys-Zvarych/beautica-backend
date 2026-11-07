package com.beautica.config;

import com.beautica.auth.phoneotp.PhoneOtpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SmsClubProperties — defaults, committed yml values and the OTP lifetime cap (phase 439)")
class SmsClubPropertiesTest {

    @Test
    @DisplayName("an unbound instance carries the contract defaults (BEAUTY, real endpoint, 3s/5s, 60/10 min, staging cap 50)")
    void should_haveContractDefaults_when_unbound() {
        SmsClubProperties props = new SmsClubProperties();

        assertThat(props.getToken()).isEmpty();
        assertThat(props.getSenderName()).isEqualTo("BEAUTY");
        assertThat(props.getBaseUrl()).isEqualTo("https://im.smsclub.mobi/sms/send");
        assertThat(props.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.getLifetimeMinutes()).isEqualTo(60);
        assertThat(props.getOtpLifetimeMinutes()).isEqualTo(10);
        assertThat(props.getStagingDailyCap()).isEqualTo(50);
    }

    @Test
    @DisplayName("with no env vars set, the committed application.yml binds the same contract values, staging cap 50 included")
    void should_bindCommittedYmlValues_when_noEnvIsSet() throws Exception {
        SmsClubProperties props = bindFromApplicationYml();

        assertThat(props.getToken()).isEmpty();
        assertThat(props.getSenderName()).isEqualTo("BEAUTY");
        assertThat(props.getBaseUrl()).isEqualTo("https://im.smsclub.mobi/sms/send");
        assertThat(props.getLifetimeMinutes()).isEqualTo(60);
        assertThat(props.getOtpLifetimeMinutes()).isEqualTo(10);
        assertThat(props.getStagingDailyCap()).isEqualTo(50);
    }

    @Test
    @DisplayName("the OTP lifetime, Java default and committed yml alike, never exceeds the OTP code TTL")
    void should_capOtpLifetimeAtTheOtpCodeTtl_when_defaultsAndYmlAreUsed() throws Exception {
        Duration otpTtl = otpCodeTtl();

        long javaDefault = new SmsClubProperties().getOtpLifetimeMinutes();
        long committed = bindFromApplicationYml().getOtpLifetimeMinutes();

        assertThat(Duration.ofMinutes(javaDefault)).isLessThanOrEqualTo(otpTtl);
        assertThat(Duration.ofMinutes(committed)).isLessThanOrEqualTo(otpTtl);
    }

    @Test
    @DisplayName("SMSCLUB_TOKEN / SMSCLUB_SENDER_NAME / SMSCLUB_BASE_URL env values flow through the committed yml placeholders")
    void should_bindEnvOverrides_when_smsClubEnvVarsAreSet() throws Exception {
        SmsClubProperties props = bindFromApplicationYml(Map.of(
                "SMSCLUB_TOKEN", "env-token-value-1234567890",
                "SMSCLUB_SENDER_NAME", "OwnAlpha",
                "SMSCLUB_BASE_URL", "http://127.0.0.1:9/sms/send"));

        assertThat(props.getToken()).isEqualTo("env-token-value-1234567890");
        assertThat(props.getSenderName()).as("switching alpha-name is env-only").isEqualTo("OwnAlpha");
        assertThat(props.getBaseUrl()).isEqualTo("http://127.0.0.1:9/sms/send");
        assertThat(props.getLifetimeMinutes()).as("lifetimes are not env-driven").isEqualTo(60);
        assertThat(props.getOtpLifetimeMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("the OTP lifetime is strictly shorter than the booking lifetime, so a stale code is never delivered late")
    void should_keepOtpLifetimeBelowBookingLifetime_when_ymlIsCommitted() throws Exception {
        SmsClubProperties committed = bindFromApplicationYml();

        assertThat(committed.getOtpLifetimeMinutes()).isPositive().isLessThan(committed.getLifetimeMinutes());
        assertThat(committed.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(committed.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    /** {@code PhoneOtpService.OTP_TTL} is private; read reflectively so this test tracks the real value. */
    private static Duration otpCodeTtl() throws Exception {
        Field field = PhoneOtpService.class.getDeclaredField("OTP_TTL");
        field.setAccessible(true);
        return (Duration) field.get(null);
    }

    private static SmsClubProperties bindFromApplicationYml() throws Exception {
        return bindFromApplicationYml(Map.of());
    }

    private static SmsClubProperties bindFromApplicationYml(Map<String, Object> env) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("fake-env", env));
        sources.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("app.sms.smsclub", SmsClubProperties.class).orElseGet(SmsClubProperties::new);
    }
}
