package com.beautica.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DatabaseUrlNormalizerPostProcessor implements EnvironmentPostProcessor {

    private static final Set<String> LIBPQ_ONLY_PARAMS = Set.of("channel_binding", "sslnegotiation");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String rawUrl = environment.getProperty("DATABASE_URL");
        if (rawUrl == null || rawUrl.startsWith("jdbc:")) {
            return;
        }
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getPath();
            String query = uri.getQuery();
            String userInfo = uri.getUserInfo();

            StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://");
            jdbcUrl.append(host);
            if (port > 0) jdbcUrl.append(':').append(port);
            if (path != null && !path.isEmpty()) jdbcUrl.append(path);

            String filtered = filterQuery(query);
            if (!filtered.isEmpty()) jdbcUrl.append('?').append(filtered);

            Map<String, Object> props = new HashMap<>();
            props.put("spring.datasource.url", jdbcUrl.toString());

            if (userInfo != null) {
                int sep = userInfo.indexOf(':');
                if (sep >= 0) {
                    props.put("spring.datasource.username", userInfo.substring(0, sep));
                    props.put("spring.datasource.password", userInfo.substring(sep + 1));
                } else {
                    props.put("spring.datasource.username", userInfo);
                }
            }

            environment.getPropertySources().addFirst(
                    new MapPropertySource("normalizedDatabaseUrl", props));
        } catch (Exception ignored) {
            // Fall through — Spring will surface a clearer error
        }
    }

    private String filterQuery(String query) {
        if (query == null || query.isEmpty()) return "";
        return Arrays.stream(query.split("&"))
                .filter(param -> {
                    String key = param.contains("=") ? param.substring(0, param.indexOf('=')) : param;
                    return !LIBPQ_ONLY_PARAMS.contains(key);
                })
                .collect(Collectors.joining("&"));
    }
}
