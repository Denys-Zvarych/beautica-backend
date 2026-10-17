package com.beautica.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("railway.json — deploy config as code (Phase 367 D1/D3)")
class RailwayConfigTest {

    /** Margin on top of the web phase and the executor graces (Phase 367 D3). */
    private static final int MARGIN_SECONDS = 20;
    private static final String GRACE_SUFFIX = "_SHUTDOWN_GRACE_SECONDS";

    private static JsonNode railway() throws Exception {
        return new ObjectMapper().readTree(Files.readString(Path.of("railway.json")));
    }

    private record Graces(int sum, List<String> names) {}

    /** Every {@code *_SHUTDOWN_GRACE_SECONDS} int constant of the classes that own shutdown-awaiting pools. */
    private static Graces graces() throws Exception {
        List<String> names = new ArrayList<>();
        int sum = 0;
        for (Class<?> owner : List.of(AsyncConfig.class, SchedulingConfig.class)) {
            for (Field f : owner.getDeclaredFields()) {
                if (f.getName().endsWith(GRACE_SUFFIX) && f.getType() == int.class
                        && Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    sum += f.getInt(null);
                    names.add(owner.getSimpleName() + "." + f.getName());
                }
            }
        }
        return new Graces(sum, names);
    }

    private static int webShutdownSeconds() throws Exception {
        PropertySource<?> prod = new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yml")).get(0);
        Object raw = prod.getProperty("spring.lifecycle.timeout-per-shutdown-phase");
        assertThat(raw).as("spring.lifecycle.timeout-per-shutdown-phase in application-prod.yml").isNotNull();
        String value = raw.toString().strip();
        return (int) (value.endsWith("s") && !value.endsWith("ms")
                ? Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)))
                : Duration.parse("PT" + value)).toSeconds();
    }

    @Test
    @DisplayName("builds from the Dockerfile and health-checks /actuator/health")
    void should_useDockerfileBuilderAndActuatorHealth_when_railwayJsonParsed() throws Exception {
        JsonNode json = railway();

        assertThat(json.at("/build/builder").asText()).isEqualTo("DOCKERFILE");
        assertThat(json.at("/build/dockerfilePath").asText()).isEqualTo("Dockerfile");
        assertThat(json.at("/deploy/healthcheckPath").asText()).isEqualTo("/actuator/health");
        assertThat(json.at("/deploy/healthcheckTimeout").asInt()).isEqualTo(300);
        assertThat(json.at("/deploy/restartPolicyType").asText()).isEqualTo("ON_FAILURE");
        assertThat(json.at("/deploy/overlapSeconds").asInt()).isEqualTo(30);
        assertThat(Path.of("Dockerfile")).exists();
    }

    @Test
    @DisplayName("drainingSeconds covers 2x lifecycle phase (executor stop + web) + every executor grace + margin")
    void should_coverShutdownBudget_when_drainingSecondsCompared() throws Exception {
        int draining = railway().at("/deploy/drainingSeconds").asInt();
        int web = webShutdownSeconds();
        Graces graces = graces();

        // Spring 6.1+ (6.2.6 here): ExecutorConfigurationSupport is a SmartLifecycle (phase MAX_VALUE) whose
        // stop() waits for in-flight tasks, capped by timeout-per-shutdown-phase; it stops BEFORE the web
        // server's graceful phase, which has its own cap. The per-pool awaitTermination graces then run
        // sequentially at bean destruction. So the lifecycle cap is spent twice.
        int lifecyclePhases = 2 * web;

        assertThat(graces.names()).as("grace constants discovered").isNotEmpty();
        assertThat(draining)
                .as("drainingSeconds must be >= 2 x lifecycle phase (%ds) + sum of grace constants (%ds: %s) + margin (%ds)",
                        web, graces.sum(), graces.names(), MARGIN_SECONDS)
                .isGreaterThanOrEqualTo(lifecyclePhases + graces.sum() + MARGIN_SECONDS);
    }
}
