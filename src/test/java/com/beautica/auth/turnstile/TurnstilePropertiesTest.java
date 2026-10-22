package com.beautica.auth.turnstile;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link TurnstileProperties} binding and validation (phase 382): enabled requires a secret. */
@DisplayName("TurnstileProperties — enabled requires a secret")
class TurnstilePropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @EnableConfigurationProperties(TurnstileProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    @DisplayName("should_failBinding_when_enabledWithBlankSecret")
    void should_failBinding_when_enabledWithBlankSecret() {
        runner.withPropertyValues("app.turnstile.enabled=true", "app.turnstile.secret-key=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("TURNSTILE_SECRET_KEY");
        });
    }

    @Test
    @DisplayName("should_failBinding_when_enabledWithWhitespaceOnlySecret")
    void should_failBinding_when_enabledWithWhitespaceOnlySecret() {
        runner.withPropertyValues("app.turnstile.enabled=true", "app.turnstile.secret-key=   ").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("should_bindAndStripSecret_when_enabledWithSecret")
    void should_bindAndStripSecret_when_enabledWithSecret() {
        runner.withPropertyValues("app.turnstile.enabled=true", "app.turnstile.secret-key= s3cret \n",
                "app.turnstile.expected-hostnames[0]=beautica.app", "app.turnstile.expected-action=otp_send")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TurnstileProperties props = context.getBean(TurnstileProperties.class);
                    assertThat(props.getSecretKey()).isEqualTo("s3cret");
                    assertThat(props.getExpectedHostnames()).containsExactly("beautica.app");
                    assertThat(props.getExpectedAction()).isEqualTo("otp_send");
                });
    }

    @Test
    @DisplayName("should_bind_when_disabledWithoutSecret")
    void should_bind_when_disabledWithoutSecret() {
        runner.withPropertyValues("app.turnstile.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TurnstileProperties.class).isEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("should_reportViolation_when_validatedDirectlyWithEnabledAndBlankSecret")
    void should_reportViolation_when_validatedDirectlyWithEnabledAndBlankSecret() {
        TurnstileProperties props = new TurnstileProperties();
        props.setEnabled(true);

        Set<ConstraintViolation<TurnstileProperties>> violations = validator.validate(props);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).contains("TURNSTILE_SECRET_KEY");
    }
}
