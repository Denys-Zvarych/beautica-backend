package com.beautica.media.contract;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 341 — the upload endpoints must publish {@code multipart/form-data} (not
 * {@code application/json}) so the generated mobile client posts the right content type.
 *
 * <p>Method follows {@code UserProfileOpenApiContractTest}: fetch the rendered spec over HTTP,
 * assert in memory, never write it to a file (a {@code @SpringBootTest} spec contains test-only
 * endpoints). Same {@code @TestPropertySource} so the Spring test context is shared.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("OpenAPI contract — media upload endpoints are multipart (Phase 341)")
class MediaOpenApiContractIT extends AbstractIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MULTIPART = "multipart/form-data";

    @Autowired
    private TestRestTemplate restTemplate;

    private JsonNode spec;

    @BeforeAll
    void fetchSpec() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        spec = MAPPER.readTree(response.getBody());
    }

    private JsonNode requestContent(String path) {
        return spec.path("paths").path(path).path("post").path("requestBody").path("content");
    }

    @Test
    @DisplayName("POST /api/v1/media/avatar declares multipart/form-data only")
    void should_declareMultipartOnly_when_avatarUpload() {
        JsonNode content = requestContent("/api/v1/media/avatar");

        assertThat(content.has(MULTIPART)).as("multipart/form-data present").isTrue();
        assertThat(content.has("application/json")).as("no application/json").isFalse();
        assertThat(content.path(MULTIPART).toString()).contains("binary");
    }

    @Test
    @DisplayName("POST /api/v1/media/portfolio declares multipart/form-data only")
    void should_declareMultipartOnly_when_portfolioUpload() {
        JsonNode content = requestContent("/api/v1/media/portfolio");

        assertThat(content.has(MULTIPART)).as("multipart/form-data present").isTrue();
        assertThat(content.has("application/json")).as("no application/json").isFalse();
        assertThat(content.path(MULTIPART).toString()).contains("binary");
    }
}
