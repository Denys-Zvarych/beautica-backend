package com.beautica.booking.closure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29.7 acceptance criterion: {@code booking.closure-reminder.enabled} must default to
 * {@code false} and {@code booking.closure-reminder.dry-run} must default to {@code true} — and
 * NEITHER may appear set to the opposite (unsafe) value in any committed {@code
 * application*.yml}. Enabling this job in any environment is a Railway environment-variable
 * change the USER applies (phase 29.7's rollout procedure); no committed config may pre-empt it.
 *
 * <p>Reads the actual committed files via {@link YamlPropertySourceLoader} (handles the
 * multi-document {@code ---}/{@code spring.config.activate.on-profile} shape {@code
 * application.yml} uses) rather than grepping raw text, so a future re-indentation or profile
 * restructuring cannot silently defeat this guard the way a brittle regex could.
 */
@DisplayName("ClosureReminderConfigDefaultsTest — no committed application*.yml enables the job (Phase 29.7)")
class ClosureReminderConfigDefaultsTest {

    private static final String ENABLED_KEY = "booking.closure-reminder.enabled";
    private static final String DRY_RUN_KEY = "booking.closure-reminder.dry-run";

    private static final List<String> APPLICATION_YML_FILES = List.of(
            "application.yml", "application-local.yml", "application-prod.yml");

    @Test
    @DisplayName("no committed application*.yml sets booking.closure-reminder.enabled=true or dry-run=false")
    void should_notEnableOrDisableDryRun_when_scanningEveryCommittedYamlFile() {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

        for (String fileName : APPLICATION_YML_FILES) {
            Resource resource = new ClassPathResource(fileName);
            assertThat(resource.exists()).as("%s must exist on the classpath", fileName).isTrue();

            List<PropertySource<?>> sources;
            try {
                sources = loader.load(fileName, resource);
            } catch (Exception e) {
                throw new AssertionError("Failed to parse " + fileName + " as YAML", e);
            }

            for (PropertySource<?> source : sources) {
                if (source.containsProperty(ENABLED_KEY)) {
                    Object value = source.getProperty(ENABLED_KEY);
                    assertThat(String.valueOf(value))
                            .as("%s must not set %s to true — enabling is a Railway env-var change, "
                                            + "never a committed default", fileName, ENABLED_KEY)
                            .isEqualToIgnoringCase("false");
                }
                if (source.containsProperty(DRY_RUN_KEY)) {
                    Object value = source.getProperty(DRY_RUN_KEY);
                    assertThat(String.valueOf(value))
                            .as("%s must not set %s to false — dry-run must stay the default until "
                                            + "the phase 29.7 rollout gate is passed", fileName, DRY_RUN_KEY)
                            .isEqualToIgnoringCase("true");
                }
            }
        }
    }

    @Test
    @DisplayName("ClosureReminderProperties itself defaults enabled=false and dry-run=true")
    void should_defaultToSafeValues_when_propertiesConstructedFresh() {
        ClosureReminderProperties props = new ClosureReminderProperties();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.isDryRun()).isTrue();
    }
}
