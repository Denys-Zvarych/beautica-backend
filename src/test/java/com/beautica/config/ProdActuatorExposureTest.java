package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 364 QA — the smoke test only sees health details because {@code application-test.yml}
 * widens them. This pins the non-test (production) binding: details stay {@code never} and health
 * is the only exposed web endpoint, and shows the {@code test} profile is the sole difference.
 */
@DisplayName("Actuator exposure — production config vs test override (Phase 364)")
class ProdActuatorExposureTest {

    private static ApplicationContextRunner runner(String... props) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(props);
    }

    @Test
    @DisplayName("should_hideHealthDetails_when_noTestProfile")
    void should_hideHealthDetails_when_noTestProfile() {
        runner().run(ctx -> {
            var details = Binder.get(ctx.getEnvironment())
                    .bind("management.endpoint.health", HealthEndpointProperties.class).get().getShowDetails();

            assertThat(details).as("show-details, actual=%s", details)
                    .isEqualTo(org.springframework.boot.actuate.endpoint.Show.NEVER);
        });
    }

    @Test
    @DisplayName("should_exposeOnlyHealthEndpoint_when_noTestProfile")
    void should_exposeOnlyHealthEndpoint_when_noTestProfile() {
        runner().run(ctx -> {
            var include = Binder.get(ctx.getEnvironment())
                    .bind("management.endpoints.web.exposure", WebEndpointProperties.Exposure.class).get()
                    .getInclude();

            assertThat(include).containsExactly("health");
        });
    }

    @Test
    @DisplayName("should_showHealthDetails_when_testProfileActive")
    void should_showHealthDetails_when_testProfileActive() {
        runner("spring.profiles.active=test").run(ctx -> {
            var details = Binder.get(ctx.getEnvironment())
                    .bind("management.endpoint.health", HealthEndpointProperties.class).get().getShowDetails();

            assertThat(details).isEqualTo(org.springframework.boot.actuate.endpoint.Show.ALWAYS);
        });
    }
}
