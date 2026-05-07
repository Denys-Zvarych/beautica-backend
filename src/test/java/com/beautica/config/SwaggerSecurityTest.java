package com.beautica.config;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Swagger / OpenAPI endpoint accessibility policy defined in SecurityConfig.
 *
 * SecurityConfig permits Swagger only when the active profile matches "local | test"
 * (the {@code isDevProfile} flag). Under the "test" profile these paths must be
 * reachable without a JWT — confirming the profile guard works and the permit-all
 * branch is actually executed at runtime.
 */
@DisplayName("SwaggerSecurity — profile-based Swagger access control")
class SwaggerSecurityTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /swagger-ui.html is reachable without auth in test profile")
    void should_allowSwaggerUiHtml_when_testProfileIsActive() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui.html", String.class);

        assertThat(response.getStatusCode())
                .as("Swagger UI HTML must not be blocked (401/403) under the test profile — " +
                    "SecurityConfig.isDevProfile includes 'test' and adds a permitAll() rule")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED)
                .isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /swagger-ui/ root is reachable without auth in test profile")
    void should_allowSwaggerUiRoot_when_testProfileIsActive() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(response.getStatusCode())
                .as("Swagger UI index must not be blocked (401/403) under the test profile")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED)
                .isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /api-docs is reachable without auth in test profile")
    void should_allowApiDocs_when_testProfileIsActive() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);

        assertThat(response.getStatusCode())
                .as("OpenAPI /api-docs must not be blocked (401/403) under the test profile")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED)
                .isNotEqualTo(HttpStatus.FORBIDDEN);
    }
}
