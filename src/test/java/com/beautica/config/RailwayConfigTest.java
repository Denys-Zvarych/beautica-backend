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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("railway.json — deploy config as code (Phase 367 D1/D3)")
class RailwayConfigTest {

    /** Margin on top of the web phase and the executor graces (Phase 367 D3). */
    private static final int MARGIN_SECONDS = 20;
    private static final String GRACE_SUFFIX = "_SHUTDOWN_GRACE_SECONDS";
    private static final String WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties";
    private static final Pattern BUILDER_FROM =
            Pattern.compile("(?im)^\\s*FROM\\s+gradle:([0-9][0-9.]*)-jdk21\\S*\\s+AS\\s+builder\\s*$");
    private static final Pattern WRAPPER_DISTRIBUTION =
            Pattern.compile("(?m)^distributionUrl=.*/gradle-([0-9][0-9.]*)-(?:bin|all)\\.zip\\s*$");

    /** Anything in a builder RUN that would fetch a Gradle distribution (wrapper, `gradle wrapper`, direct zip). */
    private static final Pattern GRADLE_DOWNLOAD =
            Pattern.compile("(?i)gradlew|\\bwrapper\\b|services\\.gradle\\.org|--gradle-version|gradle-[0-9][0-9.]*-(?:bin|all)\\.zip");
    private static final Pattern STAGE_FROM = Pattern.compile("(?i)^FROM\\s+\\S+(?:\\s+AS\\s+(\\S+))?$");

    /**
     * Dockerfile instructions grouped by stage name (lower-cased {@code AS} alias), comments dropped and
     * backslash continuations joined, so a multi-line RUN is checked as one instruction.
     */
    private static Map<String, List<String>> dockerStages() throws Exception {
        Map<String, List<String>> stages = new LinkedHashMap<>();
        List<String> current = null;
        StringBuilder pending = new StringBuilder();
        for (String raw : Files.readAllLines(Path.of("Dockerfile"))) {
            String line = raw.strip();
            if (pending.isEmpty() && (line.isEmpty() || line.startsWith("#"))) {
                continue;
            }
            if (line.endsWith("\\")) {
                pending.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            String instruction = pending.append(line).toString().strip();
            pending.setLength(0);
            Matcher from = STAGE_FROM.matcher(instruction);
            if (from.matches()) {
                current = new ArrayList<>();
                stages.put(from.group(1) == null ? "stage" + stages.size() : from.group(1).toLowerCase(), current);
            }
            if (current != null) {
                current.add(instruction);
            }
        }
        return stages;
    }

    private static List<String> instructions(List<String> stage, String keyword) {
        return stage.stream().filter(i -> i.regionMatches(true, 0, keyword + " ", 0, keyword.length() + 1)).toList();
    }

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
    @DisplayName("Dockerfile builder uses preinstalled Gradle matching the wrapper version, never ./gradlew")
    void should_matchWrapperGradleVersion_when_dockerfileBuilderParsed() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String wrapperProps = Files.readString(Path.of(WRAPPER_PROPERTIES));

        Matcher builder = BUILDER_FROM.matcher(dockerfile);
        Matcher wrapper = WRAPPER_DISTRIBUTION.matcher(wrapperProps);

        assertThat(builder.find()).as("Dockerfile must have 'FROM gradle:<version>-jdk21... AS builder'").isTrue();
        assertThat(wrapper.find()).as("distributionUrl in %s", WRAPPER_PROPERTIES).isTrue();
        assertThat(builder.group(1))
                .as("builder image Gradle version must equal the wrapper distributionUrl version")
                .isEqualTo(wrapper.group(1));
        assertThat(builder.group())
                .as("builder FROM must pin the image by digest (gradle:<tag>@sha256:<64 hex>)")
                .containsPattern("gradle:\\S+@sha256:[0-9a-f]{64}\\s");
        assertThat(dockerfile).as("Dockerfile must not invoke the wrapper (it downloads Gradle)")
                .doesNotContain("./gradlew");
    }

    @Test
    @DisplayName("Dockerfile builder stage never RUNs anything that downloads a Gradle distribution and builds with plain gradle")
    void should_notDownloadGradleDistribution_when_builderStageRunsParsed() throws Exception {
        List<String> builder = dockerStages().get("builder");
        assertThat(builder).as("Dockerfile must have a stage named 'builder'").isNotNull();
        List<String> runs = instructions(builder, "RUN");

        assertThat(runs)
                .as("builder RUNs must not use the wrapper, 'gradle wrapper', --gradle-version or fetch a distribution zip")
                .noneMatch(run -> GRADLE_DOWNLOAD.matcher(run).find());
        assertThat(runs)
                .as("builder must build the jar with the preinstalled gradle, got %s", runs)
                .anyMatch(run -> run.matches("(?i)RUN\\s+gradle\\s+.*\\bbootJar\\b.*"));
    }

    @Test
    @DisplayName("Dockerfile runtime stage runs as non-root 'beautica' and copies only the boot jar from the builder")
    void should_runAsNonRootAndCopyOnlyJar_when_runtimeStageParsed() throws Exception {
        Map<String, List<String>> stages = dockerStages();
        List<String> runtime = stages.get("runtime");
        assertThat(runtime).as("Dockerfile must have a stage named 'runtime'").isNotNull();
        assertThat(new ArrayList<>(stages.keySet())).as("runtime must be the final (shipped) stage")
                .last().isEqualTo("runtime");

        List<String> users = instructions(runtime, "USER");
        List<String> copies = instructions(runtime, "COPY");
        int userIdx = runtime.indexOf(users.isEmpty() ? null : users.get(users.size() - 1));
        int entrypointIdx = runtime.indexOf(instructions(runtime, "ENTRYPOINT").stream().findFirst().orElse(null));

        assertThat(users).as("runtime stage USER instructions").containsExactly("USER beautica");
        assertThat(userIdx).as("USER beautica must come before ENTRYPOINT").isBetween(0, entrypointIdx);
        assertThat(copies).as("runtime stage must COPY only the boot jar from the builder")
                .containsExactly("COPY --from=builder /app/build/libs/*.jar app.jar");
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
