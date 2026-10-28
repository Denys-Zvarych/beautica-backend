package com.beautica.moderation;

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
 * Phase 395 acceptance — the SpringDoc spec exposes {@code POST /api/v1/reports} with the request/response
 * shape mobile phase 424 regenerates its Dio client from. Same property set as the sibling api-docs contract ITs
 * so the cached Spring context is reused.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api-docs"
})
@DisplayName("/api-docs — POST /api/v1/reports contract (phase 395)")
class ContentReportOpenApiContractIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /api/v1/reports is documented with the CreateReportRequest body, enums and 201/200 responses")
    void should_exposeReportEndpoint_when_specRendered() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode spec = objectMapper.readTree(response.getBody());

        JsonNode post = spec.path("paths").path("/api/v1/reports").path("post");
        assertThat(post.isMissingNode()).as("POST /api/v1/reports must be in the spec").isFalse();
        assertThat(fieldNames(post.path("responses"))).contains("200", "201", "400", "404", "429");

        JsonNode request = spec.path("components").path("schemas").path("CreateReportRequest");
        assertThat(fieldNames(request.path("properties")))
                .containsExactlyInAnyOrder("targetType", "targetId", "reason", "comment");
        assertThat(textValues(request.path("required"))).containsExactlyInAnyOrder("targetType", "targetId", "reason");
        assertThat(textValues(request.path("properties").path("targetType").path("enum"))).containsExactlyInAnyOrder(
                "REVIEW", "CLIENT_REVIEW", "PORTFOLIO_PHOTO", "MASTER_PROFILE", "SALON_PROFILE", "CLIENT_PROFILE");
        assertThat(textValues(request.path("properties").path("reason").path("enum"))).containsExactlyInAnyOrder(
                "SPAM", "OFFENSIVE", "HARASSMENT", "FALSE_INFO", "SEXUAL_CONTENT", "OTHER");
        assertThat(request.path("properties").path("comment").path("maxLength").asInt()).isEqualTo(500);

        JsonNode reportResponse = spec.path("components").path("schemas").path("ReportResponse");
        assertThat(fieldNames(reportResponse.path("properties")))
                .containsExactlyInAnyOrder("id", "status", "createdAt");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
