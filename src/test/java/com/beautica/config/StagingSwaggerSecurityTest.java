package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 368 audit F3 — under {@code prod,staging}: {@code /api-docs} is public (the mobile OpenAPI regen
 * fetches it) but Swagger UI is not served. Companion to {@link SwaggerSecurityTest} (test profile).
 */
@DisplayName("SwaggerConfig — prod,staging: /api-docs public, swagger-ui off")
class StagingSwaggerSecurityTest extends AbstractStagingProfileIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /api-docs is 200 and serves the OpenAPI document anonymously")
    void should_serveApiDocs_when_stagingProfileActive() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"openapi\"").contains("\"paths\"");
    }

    @Test
    @DisplayName("swagger-ui.html and /swagger-ui/index.html are not served")
    void should_notServeSwaggerUi_when_stagingProfileActive() {
        for (String path : new String[]{"/swagger-ui.html", "/swagger-ui/index.html"}) {
            ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

            assertThat(response.getStatusCode()).as(path).isNotEqualTo(HttpStatus.OK);
            String body = response.getBody() == null ? "" : response.getBody();
            assertThat(body).as(path).doesNotContainIgnoringCase("swagger-ui");
        }
    }
}
