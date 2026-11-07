package com.beautica.auth.phoneotp;

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
 * Phase 440 — the website reads the OpenAPI spec for {@code POST /api/v1/book/otp/send}: {@code smsFormat} must be
 * an optional string listing exactly {@code DOMAIN_BOUND} and {@code PLAIN}, and the pre-440 fields must stay as
 * they were (additions-only API rule). Asserts the live {@code /api-docs}; it never dumps the spec to a file.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api-docs"
})
@DisplayName("/api-docs — PhoneOtpSendRequest.smsFormat is optional with both values (Phase 440)")
class PhoneOtpSendApiDocsContractIT extends AbstractIntegrationTest {

    private static final String SCHEMA = "PhoneOtpSendRequest";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode schema() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = objectMapper.readTree(response.getBody()).path("components").path("schemas").path(SCHEMA);
        assertThat(node.isMissingNode()).as("%s must exist in /api-docs", SCHEMA).isFalse();
        return node;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(item -> out.add(item.asText()));
        return out;
    }

    @Test
    @DisplayName("smsFormat is a string, max 32, listing exactly DOMAIN_BOUND and PLAIN")
    void should_exposeSmsFormatWithBothValues_when_specRequested() throws Exception {
        JsonNode smsFormat = schema().path("properties").path("smsFormat");

        assertThat(smsFormat.isMissingNode()).as("smsFormat must be in the spec").isFalse();
        assertThat(smsFormat.path("type").asText()).as("a String on the wire, never a Jackson enum")
                .isEqualTo("string");
        assertThat(texts(smsFormat.path("enum"))).containsExactlyInAnyOrder("DOMAIN_BOUND", "PLAIN");
        assertThat(smsFormat.path("maxLength").asInt()).isEqualTo(32);
    }

    @Test
    @DisplayName("smsFormat is optional; the required list and the pre-440 fields are unchanged")
    void should_keepSmsFormatOptionalAndOldFieldsIntact_when_specRequested() throws Exception {
        JsonNode schema = schema();

        assertThat(texts(schema.path("required"))).as("old website builds send no smsFormat")
                .doesNotContain("smsFormat");
        List<String> fields = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(fields::add);
        assertThat(fields).as("additions only: the four pre-440 fields stay, smsFormat is the one new field")
                .containsExactlyInAnyOrder("phone", "privacyAccepted", "privacyPolicyVersion", "turnstileToken",
                        "smsFormat");
    }
}
