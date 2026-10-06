package com.beautica.salon;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code SiblingSalonOption} OpenAPI schema behind
 * {@code GET /salons/{salonId}/sibling-salons} (the rotate-admin destination picker). The mobile
 * Dio client is generated from this spec, so {@code avatarUrl} must be published as a nullable
 * string — a non-nullable one would make the generated model throw on every logo-less salon — and
 * the storage-internal R2 key must never appear.
 *
 * <p>Same {@code @TestPropertySource} as {@link SalonRequestApiDocsContractIT}, so both share one
 * cached Spring context.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api-docs"
})
@DisplayName("/api-docs — SiblingSalonOption publishes a nullable avatarUrl and no R2 key")
class SiblingSalonOptionApiDocsContractIT extends AbstractIntegrationTest {

    private static final String SCHEMA = "SiblingSalonOption";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode schema() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = objectMapper.readTree(response.getBody())
                .path("components").path("schemas").path(SCHEMA);
        assertThat(node.isMissingNode()).as("%s must exist in /api-docs", SCHEMA).isFalse();
        return node;
    }

    @Test
    @DisplayName("avatarUrl is a nullable, optional string")
    void should_publishNullableAvatarUrl_when_specIsGenerated() throws Exception {
        // Arrange
        JsonNode schema = schema();

        // Act
        JsonNode avatarUrl = schema.path("properties").path("avatarUrl");

        // Assert
        assertThat(avatarUrl.isMissingNode()).as("avatarUrl must be published").isFalse();
        assertThat(avatarUrl.toString())
                .as("avatarUrl must admit null and be a string: %s", avatarUrl)
                .contains("null")
                .contains("string");
        assertThat(schema.path("required"))
                .extracting(JsonNode::asText)
                .as("avatarUrl is nullable, so it must not be listed as required")
                .doesNotContain("avatarUrl");
    }

    @Test
    @DisplayName("exactly the five picker properties — no R2 key, no owner id")
    void should_publishOnlyPickerProperties_when_specIsGenerated() throws Exception {
        // Arrange
        JsonNode schema = schema();

        // Act
        JsonNode properties = schema.path("properties");

        // Assert
        assertThat(properties.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("id", "name", "street", "buildingNo", "avatarUrl");
    }
}
