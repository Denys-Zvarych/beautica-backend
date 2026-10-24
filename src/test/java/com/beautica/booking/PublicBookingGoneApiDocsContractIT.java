package com.beautica.booking;

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
 * Phase 379 acceptance 5 — the website (008/009) learns the retired-link contract from the spec: every public
 * slug-keyed route publishes a {@code 410} response naming {@code BOOKING_LINK_RETIRED}.
 *
 * <p>Same {@code @TestPropertySource} as {@code SiblingSalonOptionApiDocsContractIT}, so the Spring context is
 * shared. Read-only assertion on {@code /api-docs}; never used to dump the spec.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api-docs"
})
@DisplayName("/api-docs — public booking slug routes document 410 BOOKING_LINK_RETIRED (phase 379)")
class PublicBookingGoneApiDocsContractIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "get,  /api/v1/book/{slug}/info",
            "get,  /api/v1/book/{slug}/reviews",
            "get,  /api/v1/book/{slug}/availability",
            "get,  /api/v1/book/{slug}/bookable-days",
            "post, /api/v1/book/{slug}/booking",
            "get,  /api/v1/book/s/{slug}/info",
            "get,  /api/v1/book/s/{slug}/reviews",
            "get,  /api/v1/book/s/{slug}/masters/{masterId}/availability",
            "get,  /api/v1/book/s/{slug}/masters/{masterId}/bookable-days",
            "post, /api/v1/book/s/{slug}/masters/{masterId}/booking"
    })
    @DisplayName("each slug-keyed route publishes a 410 response mentioning BOOKING_LINK_RETIRED")
    void should_document410_when_routeIsSlugKeyed(String method, String path) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode gone = objectMapper.readTree(response.getBody())
                .path("paths").path(path).path(method).path("responses").path("410");

        assertThat(gone.isMissingNode()).as("%s %s must document 410", method, path).isFalse();
        assertThat(gone.path("description").asText()).contains("BOOKING_LINK_RETIRED");
    }
}
