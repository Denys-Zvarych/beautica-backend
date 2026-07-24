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
 * Verifies that SpringDoc / Swagger endpoints are disabled in the test and
 * production profiles.
 *
 * application.yml sets springdoc.api-docs.enabled=false and
 * springdoc.swagger-ui.enabled=false. application-test.yml does not override
 * these values, so SpringDoc registers no routes when tests run under
 * @ActiveProfiles("test"). No route registration means the paths are not
 * accessible — they return a non-200 error status and do not serve OpenAPI
 * JSON content.
 *
 * Two-part assertion per path:
 *   1. HTTP status is not 200 — the endpoint did not respond successfully.
 *   2. Response body does not contain the "openapi" or "swagger" JSON keys —
 *      no OpenAPI spec is served regardless of which error code the framework
 *      produces (404 from a properly-wired error handler, or 500 if
 *      NoResourceFoundException is not yet mapped by GlobalExceptionHandler).
 *
 * This design decouples the test from GlobalExceptionHandler internals while
 * still catching the case that matters: SpringDoc being accidentally
 * re-enabled in the production/test profile.
 *
 * Note: verifying that Swagger IS enabled under the "local" profile requires
 * a running local database and is not automated here. Confirm manually via
 * ./gradlew bootRun --args='--spring.profiles.active=local' and checking
 * http://localhost:8080/swagger-ui.html.
 */
@DisplayName("SwaggerConfig — Swagger disabled in test/production profile")
class SwaggerSecurityTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /swagger-ui.html does not serve Swagger content when SpringDoc is disabled")
    void should_notServeSwaggerUiHtml_when_springdocIsDisabledInTestProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui.html", String.class);

        assertThat(response.getStatusCode())
                .as("springdoc.swagger-ui.enabled=false must prevent /swagger-ui.html from " +
                    "returning HTTP 200 — got %s. If this fails, Swagger is live in production.",
                    response.getStatusCode())
                .isNotEqualTo(HttpStatus.OK);

        String body = response.getBody() != null ? response.getBody() : "";
        assertThat(body)
                .as("Response body for /swagger-ui.html must not contain Swagger HTML content " +
                    "when SpringDoc is disabled.")
                .doesNotContainIgnoringCase("swagger")
                .doesNotContainIgnoringCase("openapi");
    }

    @Test
    @DisplayName("GET /swagger-ui/index.html does not serve Swagger content when SpringDoc is disabled")
    void should_notServeSwaggerUiIndex_when_springdocIsDisabledInTestProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(response.getStatusCode())
                .as("springdoc.swagger-ui.enabled=false must prevent /swagger-ui/index.html " +
                    "from returning HTTP 200 — got %s.",
                    response.getStatusCode())
                .isNotEqualTo(HttpStatus.OK);

        String body = response.getBody() != null ? response.getBody() : "";
        assertThat(body)
                .as("Response body for /swagger-ui/index.html must not contain Swagger content " +
                    "when SpringDoc is disabled.")
                .doesNotContainIgnoringCase("swagger")
                .doesNotContainIgnoringCase("openapi");
    }

    @Test
    @DisplayName("GET /api-docs does not serve OpenAPI JSON when SpringDoc is disabled")
    void should_notServeApiDocs_when_springdocIsDisabledInTestProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);

        assertThat(response.getStatusCode())
                .as("springdoc.api-docs.enabled=false must prevent /api-docs from returning " +
                    "HTTP 200 — got %s. If this fails, the OpenAPI spec is publicly accessible " +
                    "in production.",
                    response.getStatusCode())
                .isNotEqualTo(HttpStatus.OK);

        String body = response.getBody() != null ? response.getBody() : "";
        assertThat(body)
                .as("Response body for /api-docs must not contain an OpenAPI spec when " +
                    "SpringDoc is disabled.")
                .doesNotContain("\"openapi\"")
                .doesNotContain("\"paths\"");
    }

    @Test
    @DisplayName("GET /api-docs/swagger-config does not serve config when SpringDoc is disabled")
    void should_notServeSwaggerConfig_when_springdocIsDisabledInTestProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs/swagger-config", String.class);

        assertThat(response.getStatusCode())
                .as("springdoc.api-docs.enabled=false must prevent /api-docs/swagger-config " +
                    "from returning HTTP 200 — got %s.",
                    response.getStatusCode())
                .isNotEqualTo(HttpStatus.OK);

        String body = response.getBody() != null ? response.getBody() : "";
        assertThat(body)
                .as("Response body for /api-docs/swagger-config must not contain Swagger " +
                    "config content when SpringDoc is disabled.")
                .doesNotContain("\"configUrl\"")
                .doesNotContain("\"urls\"");
    }
}
