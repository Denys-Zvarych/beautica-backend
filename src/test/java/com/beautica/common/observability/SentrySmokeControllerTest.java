package com.beautica.common.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SentrySmokeController — Phase 371 step 9 (staging-only bean)")
class SentrySmokeControllerTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SentrySmokeController.class);

    @Test
    @DisplayName("beautica.environment=prod — bean absent")
    void should_beAbsent_when_prod() {
        runner.withPropertyValues("beautica.environment=prod")
                .run(context -> assertThat(context).doesNotHaveBean(SentrySmokeController.class));
    }

    @Test
    @DisplayName("beautica.environment=local and unset — bean absent")
    void should_beAbsent_when_localOrUnset() {
        runner.withPropertyValues("beautica.environment=local")
                .run(context -> assertThat(context).doesNotHaveBean(SentrySmokeController.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(SentrySmokeController.class));
    }

    @Test
    @DisplayName("beautica.environment=staging — bean present")
    void should_bePresent_when_staging() {
        runner.withPropertyValues("beautica.environment=staging")
                .run(context -> assertThat(context).hasSingleBean(SentrySmokeController.class));
    }

    @Test
    @DisplayName("trigger throws the canary exception (unhandled → generic ERROR log → Sentry event)")
    void should_throwCanaryException_when_triggered() {
        SentrySmokeController controller = new SentrySmokeController();

        assertThatThrownBy(controller::sentrySmoke)
                .isInstanceOf(SentrySmokeTestException.class)
                .hasMessage(SentrySmokeController.CANARY_MESSAGE);
    }
}
