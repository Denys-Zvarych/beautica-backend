package com.beautica.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the REAL prod console pattern and conversion rule from {@code logback-spring.xml} (Phase 371, audit-fix
 * cycle 1, finding 7): a hostile message must still render as exactly one valid JSON line with the same fields.
 */
@DisplayName("logback-spring.xml prod JSON line — escaped %msg")
class ProdJsonLogPatternTest {

    private static final Path CONFIG = Path.of("src/main/resources/logback-spring.xml");

    private static String render(String message) throws Exception {
        return render(message, null);
    }

    private static String render(String message, Throwable throwable) throws Exception {
        String xml = Files.readString(CONFIG);
        Matcher rule = Pattern.compile("<conversionRule[^>]*/>").matcher(xml);
        StringBuilder rules = new StringBuilder();
        while (rule.find()) {
            rules.append(rule.group());
        }
        Matcher prodPattern = Pattern.compile(
                "<springProfile name=\"prod\">.*?<pattern>(.*?)</pattern>", Pattern.DOTALL).matcher(xml);
        assertThat(rules).as("conversionRules present").contains("jsonMsg").contains("jsonEx");
        assertThat(prodPattern.find()).as("prod pattern present").isTrue();

        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new ByteArrayInputStream(
                ("<configuration>" + rules + "</configuration>").getBytes(StandardCharsets.UTF_8)));
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(prodPattern.group(1));
        layout.start();

        LoggingEvent event = new LoggingEvent("fqcn", context.getLogger("com.beautica.Probe"), Level.WARN,
                message, throwable, null);
        event.setMDCPropertyMap(Map.of("requestId", "req-12345678"));
        return layout.doLayout(event);
    }

    @Test
    @DisplayName("quotes, backslashes, newlines, tabs and control chars — one valid JSON line, message intact")
    void should_renderOneValidJsonLine_when_messageIsHostile() throws Exception {
        String hostile = "a \"quoted\" \\path\\ line1\nline2\r\n\t\u0001\",\"level\":\"FORGED";

        String line = render(hostile);

        assertThat(line).endsWith(System.lineSeparator());
        assertThat(line.strip()).doesNotContain("\n").doesNotContain("\r");
        JsonNode json = new ObjectMapper().readTree(line);
        assertThat(json.path("msg").asText()).isEqualTo(hostile);
        assertThat(json.path("level").asText()).isEqualTo("WARN");
        assertThat(json.path("requestId").asText()).isEqualTo("req-12345678");
        assertThat(json.path("logger").asText()).isEqualTo("com.beautica.Probe");
        assertThat(json.has("time")).isTrue();
        assertThat(json.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("plain message — unchanged text")
    void should_keepPlainMessage_when_nothingToEscape() throws Exception {
        String line = render("Booking created for salon 42");

        assertThat(new ObjectMapper().readTree(line).path("msg").asText()).isEqualTo("Booking created for salon 42");
    }

    @Test
    @DisplayName("event with an exception — still ONE JSON object; escaped, PII-redacted stack trace in \"exception\"")
    void should_renderExceptionAsJsonField_when_eventHasThrowable() throws Exception {
        IllegalStateException failure = new IllegalStateException("lookup failed for canary@x.ua \"quoted\"",
                new RuntimeException("caused by +380501112233"));

        String line = render("Unhandled exception", failure);

        assertThat(line.strip()).doesNotContain("\n").doesNotContain("\r");
        assertThat(line.split(System.lineSeparator(), -1)).hasSize(2);
        JsonNode json = new ObjectMapper().readTree(line);
        assertThat(json.size()).isEqualTo(6);
        assertThat(json.path("msg").asText()).isEqualTo("Unhandled exception");
        String exception = json.path("exception").asText();
        assertThat(exception).contains("java.lang.IllegalStateException: lookup failed for [email] \"quoted\"")
                .contains("Caused by: java.lang.RuntimeException: caused by [phone]")
                .contains("\tat com.beautica.common.logging.ProdJsonLogPatternTest")
                .doesNotContain("canary@x.ua").doesNotContain("380501112233");
    }
}
