package com.beautica.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("graceful shutdown — prod profile only (Phase 367 D3)")
class GracefulShutdownConfigTest {

    private static PropertySource<?> load(String file) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        return sources.isEmpty() ? null : sources.get(0);
    }

    @Test
    @DisplayName("application-prod.yml: server.shutdown=graceful and a 20 s phase timeout below drainingSeconds")
    void should_shutDownGracefullyWithin20s_when_prodProfile() throws Exception {
        PropertySource<?> prod = load("application-prod.yml");
        int draining = new ObjectMapper().readTree(Files.readString(Path.of("railway.json")))
                .at("/deploy/drainingSeconds").asInt();

        assertThat(prod.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(prod.getProperty("spring.lifecycle.timeout-per-shutdown-phase").toString()).isEqualTo("20s");
        assertThat(20).isLessThan(draining);
    }

    @Test
    @DisplayName("application.yml and application-local.yml set no graceful-shutdown keys")
    void should_leaveGracefulShutdownUnset_when_baseAndLocalProfiles() throws Exception {
        for (String file : List.of("application.yml", "application-local.yml")) {
            for (PropertySource<?> doc : new YamlPropertySourceLoader().load(file, new ClassPathResource(file))) {
                assertThat(doc.getProperty("server.shutdown")).as(file).isNull();
                assertThat(doc.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).as(file).isNull();
            }
        }
    }
}
