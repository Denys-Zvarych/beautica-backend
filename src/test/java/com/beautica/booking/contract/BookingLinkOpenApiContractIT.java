package com.beautica.booking.contract;

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
 * Phase 375 — the {@code GET /booking-links/mine} contract mobile 413 generates its client from.
 * Asserts the shape only; never commit a spec dumped from a test context. Same property set as the
 * sibling api-docs contract ITs so the cached Spring context is reused.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api-docs"
})
@DisplayName("/api-docs — phase 375 booking-links contract")
class BookingLinkOpenApiContractIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;

    private JsonNode spec() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    @DisplayName("/api-docs exposes /booking-links/mine with required fields, nullable salonId and MASTER/SALON kind enum")
    void should_exposeBookingLinksSchemas_when_specRendered() throws Exception {
        JsonNode spec = spec();
        JsonNode schemas = spec.path("components").path("schemas");

        assertThat(spec.path("paths").has("/api/v1/booking-links/mine")).isTrue();
        assertThat(texts(schemas.path("BookingLinksResponse").path("required")))
                .containsExactlyInAnyOrder("shareEnabled", "links");
        JsonNode link = schemas.path("BookingLinkResponse");
        assertThat(texts(link.path("required")))
                .containsExactlyInAnyOrder("kind", "slug", "url", "displayName", "bookable");
        assertThat(texts(link.path("properties").path("salonId").path("type")))
                .containsExactlyInAnyOrder("string", "null");
        assertThat(texts(link.path("properties").path("kind").path("enum")))
                .containsExactlyInAnyOrder("MASTER", "SALON");
    }

    @Test
    @DisplayName("phase 376: /booking-links/mine operation documents SALON links for owner/admin, the /book/s/{slug} URL and the 409 lock timeout")
    void should_documentSalonLinks_when_specRendered() throws Exception {
        JsonNode spec = spec();
        JsonNode get = spec.path("paths").path("/api/v1/booking-links/mine").path("get");
        String description = get.path("description").asText();
        String tagDescription = "";
        for (JsonNode tag : spec.path("tags")) {
            if ("Booking links".equals(tag.path("name").asText())) {
                tagDescription = tag.path("description").asText();
            }
        }

        assertThat(description)
                .as("mobile 413/414 read the role matrix from this text, actual=%s", description)
                .contains("SALON_OWNER", "SALON_ADMIN", "SALON link", "https://beautica.app/book/s/{slug}");
        assertThat(tagDescription).as("tag, actual=%s", tagDescription).contains("beautica.app/book/s/{slug}");
        assertThat(get.path("responses").has("409")).as("lock-timeout 409 documented").isTrue();
        assertThat(spec.path("components").path("schemas").path("BookingLinkResponse")
                .path("properties").path("url").path("description").asText())
                .contains("/book/s/{slug}");
    }

    @Test
    @DisplayName("phase 377: /api-docs exposes GET /book/s/{slug}/info with the salon page schema and no private fields")
    void should_exposeSalonBookingPage_when_specRendered() throws Exception {
        JsonNode spec = spec();
        JsonNode schemas = spec.path("components").path("schemas");

        assertThat(spec.path("paths").path("/api/v1/book/s/{slug}/info").has("get")).isTrue();
        JsonNode page = schemas.path("SalonBookingSlugInfoResponse");
        List<String> pageFields = new ArrayList<>();
        page.path("properties").fieldNames().forEachRemaining(pageFields::add);
        assertThat(pageFields).containsExactlyInAnyOrder(
                "salonId", "name", "city", "avatarUrl", "coverImageUrl", "avgRating", "reviewCount",
                "bookable", "masters");
        assertThat(texts(page.path("required")))
                .containsExactlyInAnyOrder("salonId", "name", "reviewCount", "bookable", "masters");
        assertThat(texts(page.path("properties").path("avgRating").path("type")))
                .containsExactlyInAnyOrder("number", "null");
        JsonNode master = schemas.path("SalonBookingSlugMasterItem");
        List<String> masterFields = new ArrayList<>();
        master.path("properties").fieldNames().forEachRemaining(masterFields::add);
        assertThat(masterFields).containsExactlyInAnyOrder("masterSlug", "name", "avatarUrl", "professionalTitle");
        assertThat(texts(master.path("required"))).containsExactlyInAnyOrder("masterSlug", "name");
    }
}
