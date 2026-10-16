package com.beautica.common.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.status.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the REAL {@code classpath:logback-spring.xml} through Spring Boot's {@link LogbackLoggingSystem} with the
 * {@code prod} profile active (Phase 371, audit-fix cycle 2, verifier gap 10) — the path Railway takes, which the
 * test suite (on {@code logback-test.xml}) never exercises otherwise. Restores the test logging config afterwards.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("logback-spring.xml under the prod profile — loads cleanly, one JSON object per line")
class ProdLogbackSpringConfigTest {

    private static final String PLAIN_MARKER = "prod-logback-probe-plain";
    private static final String ERROR_MARKER = "prod-logback-probe-error";

    private final LogbackLoggingSystem loggingSystem = new LogbackLoggingSystem(getClass().getClassLoader());
    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    @AfterEach
    void restoreTestLogging() throws Exception {
        MDC.clear();
        loggingSystem.cleanUp();
        loggerContext.reset();
        new ContextInitializer(loggerContext).autoConfig();
    }

    private void initialiseProdLogging() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        loggingSystem.cleanUp();
        loggerContext.getStatusManager().clear();
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(new LoggingInitializationContext(environment), "classpath:logback-spring.xml", null);
    }

    private static JsonNode lineContaining(CapturedOutput output, String marker) throws Exception {
        List<String> lines = output.getOut().lines().filter(l -> l.contains(marker)).toList();
        assertThat(lines).as("exactly one output line carries " + marker).hasSize(1);
        return new ObjectMapper().readTree(lines.get(0));
    }

    @Test
    @DisplayName("prod profile — config loads with no ERROR status; plain and exception lines are single valid JSON objects")
    void should_emitSingleJsonLines_when_prodProfileLoaded(CapturedOutput output) throws Exception {
        initialiseProdLogging();
        Logger log = LoggerFactory.getLogger("com.beautica.ProdLogbackProbe");

        MDC.put("requestId", "req-prod-0001");
        log.info(PLAIN_MARKER + " \"quoted\"\nsecond line");
        log.error(ERROR_MARKER, new IllegalStateException("failed for canary@x.ua",
                new RuntimeException("phone +380501112233")));
        MDC.clear();

        assertThat(loggerContext.getStatusManager().getCopyOfStatusList())
                .noneMatch(status -> status.getEffectiveLevel() >= Status.ERROR);
        JsonNode plain = lineContaining(output, PLAIN_MARKER);
        assertThat(plain.path("level").asText()).isEqualTo("INFO");
        assertThat(plain.path("requestId").asText()).isEqualTo("req-prod-0001");
        assertThat(plain.path("msg").asText()).isEqualTo(PLAIN_MARKER + " \"quoted\"\nsecond line");
        assertThat(plain.has("exception")).isFalse();
        JsonNode error = lineContaining(output, ERROR_MARKER);
        assertThat(error.path("level").asText()).isEqualTo("ERROR");
        assertThat(error.path("exception").asText())
                .contains("java.lang.IllegalStateException: failed for [email]")
                .contains("Caused by: java.lang.RuntimeException: phone [phone]");
        assertThat(output.getOut()).doesNotContain("canary@x.ua").doesNotContain("380501112233")
                .doesNotContain("\tat com.beautica.common.logging.ProdLogbackSpringConfigTest");
    }
}
