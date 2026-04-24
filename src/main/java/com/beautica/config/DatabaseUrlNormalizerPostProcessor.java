package com.beautica.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

public class DatabaseUrlNormalizerPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = environment.getProperty("DATABASE_URL");
        if (databaseUrl != null && !databaseUrl.startsWith("jdbc:")) {
            Map<String, Object> props = new HashMap<>();
            props.put("DATABASE_URL", "jdbc:" + databaseUrl);
            environment.getPropertySources().addFirst(
                new MapPropertySource("normalizedDatabaseUrl", props));
        }
    }
}
