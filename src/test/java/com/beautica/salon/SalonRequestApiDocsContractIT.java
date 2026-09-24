package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ONE intended OpenAPI change of the salon settlement-label fix: the legacy free-text
 * {@code city}/{@code region} request fields are accepted but ignored (derived from
 * {@code cityId}), so the spec flags them {@code deprecated} — while the sibling request fields
 * and the RESPONSE {@code city}/{@code region} stay un-deprecated.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api-docs"
})
@DisplayName("/api-docs — salon request city/region are deprecated, nothing else is")
class SalonRequestApiDocsContractIT extends AbstractIntegrationTest {

    private static final String IGNORED = "Ignored — derived from cityId";
    private static final String IGNORED_ADDRESS = "Ignored — use street/buildingNo/locationNote";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode property(String schema, String field) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = objectMapper.readTree(response.getBody())
                .path("components").path("schemas").path(schema).path("properties").path(field);
        assertThat(node.isMissingNode()).as("%s.%s must exist in /api-docs", schema, field).isFalse();
        return node;
    }

    @ParameterizedTest(name = "{0}.{1} is deprecated and says it is ignored")
    @CsvSource({
            "CreateSalonRequest, city",
            "CreateSalonRequest, region",
            "UpdateSalonRequest, city",
            "UpdateSalonRequest, region"
    })
    @DisplayName("the four ignored request fields are flagged deprecated with the ignore note")
    void should_flagDeprecated_when_requestFieldIsIgnored(String schema, String field) throws Exception {
        JsonNode node = property(schema, field);

        assertThat(node.path("deprecated").asBoolean()).isTrue();
        assertThat(node.path("description").asText()).isEqualTo(IGNORED);
        assertThat(node.path("type").asText())
                .as("still a String — old clients keep parsing").isEqualTo("string");
    }

    @ParameterizedTest(name = "{0}.address is deprecated and points at the structured fields")
    @CsvSource({"CreateSalonRequest", "UpdateSalonRequest"})
    @DisplayName("the free-text address request field is flagged deprecated on both requests")
    void should_flagAddressDeprecated_when_requestAddressIsIgnored(String schema) throws Exception {
        JsonNode node = property(schema, "address");

        assertThat(node.path("deprecated").asBoolean()).isTrue();
        assertThat(node.path("description").asText()).isEqualTo(IGNORED_ADDRESS);
        assertThat(node.path("type").asText()).isEqualTo("string");
    }

    @ParameterizedTest(name = "{0}.{1} is NOT deprecated")
    @CsvSource({
            "CreateSalonRequest, cityId",
            "CreateSalonRequest, street",
            "PublicSalonResponse, address",
            "SalonResponse, address",
            "UpdateSalonRequest, cityId",
            "SalonResponse, city",
            "SalonResponse, region",
            "PublicSalonResponse, city",
            "PublicSalonResponse, region"
    })
    @DisplayName("sibling request fields and the response labels are untouched")
    void should_notFlagDeprecated_when_fieldIsStillHonoured(String schema, String field) throws Exception {
        JsonNode node = property(schema, field);

        assertThat(node.path("deprecated").asBoolean(false)).isFalse();
    }
}
