package com.beautica.config.appcheck;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AppCheckProperties — boot validation (B514, AUTH-54)")
class AppCheckPropertiesTest {

    private static AppCheckProperties properties(AppCheckMode mode, String projectNumber, List<String> appIds) {
        AppCheckProperties properties = new AppCheckProperties();
        properties.setMode(mode);
        properties.setProjectNumber(projectNumber);
        properties.setAppIds(appIds);
        return properties;
    }

    @Test
    @DisplayName("should_boot_when_modeOffWithoutProjectNumber")
    void should_boot_when_modeOffWithoutProjectNumber() {
        AppCheckProperties properties = properties(AppCheckMode.OFF, null, List.of());

        assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should_failBoot_when_enforceWithoutProjectNumber")
    void should_failBoot_when_enforceWithoutProjectNumber() {
        AppCheckProperties properties = properties(AppCheckMode.ENFORCE, "", List.of("1:1:android:a"));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AUTH_APP_CHECK_PROJECT_NUMBER");
    }

    @Test
    @DisplayName("should_failBoot_when_logOnlyWithNonNumericProjectNumber")
    void should_failBoot_when_logOnlyWithNonNumericProjectNumber() {
        AppCheckProperties properties = properties(AppCheckMode.LOG_ONLY, "beautica-cb9fb", List.of("1:1:android:a"));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AUTH_APP_CHECK_PROJECT_NUMBER");
    }

    @Test
    @DisplayName("should_failBoot_when_enforceWithBlankAppIds")
    void should_failBoot_when_enforceWithBlankAppIds() {
        AppCheckProperties properties = properties(AppCheckMode.ENFORCE, "123", List.of(" "));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AUTH_APP_CHECK_APP_IDS");
    }

    @Test
    @DisplayName("should_deriveIssuerAudienceAndAppIds_when_configured")
    void should_deriveIssuerAudienceAndAppIds_when_configured() {
        AppCheckProperties properties = properties(AppCheckMode.ENFORCE, " 123 ", List.of(" a ", "", "b"));

        properties.afterPropertiesSet();

        assertThat(properties.expectedIssuer()).isEqualTo("https://firebaseappcheck.googleapis.com/123");
        assertThat(properties.expectedAudience()).isEqualTo("projects/123");
        assertThat(properties.allowedAppIds()).containsExactly("a", "b");
    }
}
