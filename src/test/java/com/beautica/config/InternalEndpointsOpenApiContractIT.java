package com.beautica.config;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard: no {@code /api/v1/internal/**} endpoint may appear in the public OpenAPI spec the mobile
 * client is generated from (excluded via {@code springdoc.paths-to-exclude}). Same property set as
 * the sibling api-docs contract ITs so the cached Spring context is reused.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api-docs"
})
@DisplayName("/api-docs — internal endpoints are hidden")
class InternalEndpointsOpenApiContractIT extends AbstractIntegrationTest {

    private static final String INTERNAL_PREFIX = "/api/v1/internal";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("/api-docs lists no path under /api/v1/internal/ and no Internal* tag")
    void should_excludeInternalPaths_when_specRendered() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode spec = objectMapper.readTree(response.getBody());

        List<String> paths = new ArrayList<>();
        spec.path("paths").fieldNames().forEachRemaining(paths::add);
        List<String> tags = new ArrayList<>();
        spec.path("tags").forEach(tag -> tags.add(tag.path("name").asText()));

        assertThat(paths).as("spec must render real paths, else the guard is vacuous").isNotEmpty();
        assertThat(paths).noneMatch(p -> p.startsWith(INTERNAL_PREFIX));
        assertThat(tags).noneMatch(t -> t.toLowerCase().startsWith("internal"));
    }
}
