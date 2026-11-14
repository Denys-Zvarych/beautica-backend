package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 494 (GST-46 / NTF-47) — the 2h guest reminder is ON with a 0m min-lead only under the {@code local}
 * profile; the base {@code application.yml} (what prod/staging/test inherit) keeps it OFF with a 3h min-lead.
 *
 * <p>Binds {@code app.booking.reminder-2h} from the real YAML files on the classpath, layered the way Spring
 * Boot layers a profile overlay (profile file first = highest precedence). Placeholders resolve against
 * these YAML sources only — never the OS environment — so a developer's exported
 * {@code APP_BOOKING_REMINDER_2H_*} cannot change the outcome. No Spring context, no DB.
 */
@DisplayName("app.booking.reminder-2h — local-profile overlay vs base defaults")
class LocalProfileReminder2hDefaultsTest {

    private static final String PREFIX = "app.booking.reminder-2h";

    @Test
    @DisplayName("local profile — 2h reminder is enabled with a 0m min-lead")
    void should_enableWithZeroMinLead_when_localProfileActive() throws IOException {
        BookingSmsProperties.Reminder2h bound = bind("application-local.yml", "application.yml");

        assertThat(bound.isEnabled()).as("local reminder-2h.enabled").isTrue();
        assertThat(bound.getMinLead()).as("local reminder-2h.min-lead").isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("no local profile — 2h reminder stays disabled with a 3h min-lead")
    void should_stayDisabledWithThreeHourMinLead_when_localProfileNotActive() throws IOException {
        BookingSmsProperties.Reminder2h bound = bind("application.yml");

        assertThat(bound.isEnabled()).as("base reminder-2h.enabled").isFalse();
        assertThat(bound.getMinLead()).as("base reminder-2h.min-lead").isEqualTo(Duration.ofHours(3));
    }

    /** Binds the reminder block from the given classpath YAML files, highest precedence first. */
    private static BookingSmsProperties.Reminder2h bind(String... yamlFilesHighestPrecedenceFirst)
            throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : yamlFilesHighestPrecedenceFirst) {
            for (PropertySource<?> source : loader.load(file, new ClassPathResource(file))) {
                sources.addLast(source);
            }
        }
        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind(PREFIX, Bindable.of(BookingSmsProperties.Reminder2h.class))
                .orElseThrow(() -> new AssertionError(PREFIX + " is not present in " + String.join(", ",
                        yamlFilesHighestPrecedenceFirst)));
    }
}
