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
        assertThat(description)
                .as("2026-10-20: owner gets SALON links only, actual=%s", description)
                .contains("SALON_OWNER gets ONLY SALON links", "no personal MASTER link")
                .doesNotContain("SALON_OWNER get their own MASTER link", "INDEPENDENT_MASTER and SALON_OWNER");
        assertThat(spec.path("components").path("schemas").path("BookingLinkResponse").path("description").asText())
                .as("record-level schema doc")
                .contains("MASTER: INDEPENDENT_MASTER only", "no MASTER link");
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
                "bookable", "masters", "region", "street", "buildingNo", "updatedAt", "description", "locationNote",
                "portfolio", "serviceCategories", "ratingDistribution", "latestReviews");
        assertThat(texts(page.path("required")))
                .containsExactlyInAnyOrder("salonId", "name", "reviewCount", "bookable", "masters", "updatedAt",
                        "portfolio", "serviceCategories", "ratingDistribution", "latestReviews");
        for (String nullable : List.of("region", "street", "buildingNo", "description", "locationNote")) {
            assertThat(texts(page.path("properties").path(nullable).path("type")))
                    .as(nullable).containsExactlyInAnyOrder("string", "null");
        }
        assertThat(texts(page.path("properties").path("avgRating").path("type")))
                .containsExactlyInAnyOrder("number", "null");
        JsonNode master = schemas.path("SalonBookingSlugMasterItem");
        List<String> masterFields = new ArrayList<>();
        master.path("properties").fieldNames().forEachRemaining(masterFields::add);
        assertThat(masterFields).containsExactlyInAnyOrder("masterSlug", "name", "avatarUrl", "professionalTitle");
        assertThat(texts(master.path("required"))).containsExactlyInAnyOrder("masterSlug", "name");
    }

    @Test
    @DisplayName("phase 380: /api-docs exposes the enriched master page with required/nullable markers per field")
    void should_exposeEnrichedMasterPage_when_specRendered() throws Exception {
        JsonNode schemas = spec().path("components").path("schemas");

        JsonNode page = schemas.path("BookingSlugInfoResponse");
        List<String> fields = new ArrayList<>();
        page.path("properties").fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "masterName", "avatarUrl", "bio", "services", "masterId", "masterType", "professionalTitle", "city",
                "region", "salon", "avgRating", "reviewCount", "categories", "portfolio", "latestReviews",
                "bookable", "updatedAt", "ratingDistribution", "street", "buildingNo", "locationNote");
        assertThat(texts(page.path("required"))).containsExactlyInAnyOrder(
                "masterName", "services", "masterId", "masterType", "reviewCount", "categories", "portfolio",
                "latestReviews", "bookable", "updatedAt", "ratingDistribution");
        for (String nullable : List.of("avatarUrl", "bio", "professionalTitle", "city", "region", "avgRating",
                "street", "buildingNo", "locationNote")) {
            assertThat(texts(page.path("properties").path(nullable).path("type")))
                    .as(nullable).contains("null");
        }
        JsonNode salonRef = schemas.path("BookingSlugSalonRef");
        assertThat(texts(salonRef.path("required"))).containsExactlyInAnyOrder("name");
        assertThat(texts(salonRef.path("properties").path("bookingSlug").path("type")))
                .containsExactlyInAnyOrder("string", "null");
        assertThat(texts(schemas.path("CategoryRef").path("required"))).containsExactlyInAnyOrder("id", "name", "key");
        assertThat(schemas.path("ServiceSummaryDto").path("properties").has("category")).isTrue();
        assertThat(spec().path("paths").path("/api/v1/book/{slug}/reviews").has("get"))
                .as("phase 383 reviews route in the spec").isTrue();
        JsonNode reviewsParams = spec().path("paths").path("/api/v1/book/{slug}/reviews").path("get").path("parameters");
        List<String> paramNames = new ArrayList<>();
        reviewsParams.forEach(p -> paramNames.add(p.path("name").asText()));
        assertThat(paramNames).contains("slug", "sort");
        JsonNode salonReviews = spec().path("paths").path("/api/v1/book/s/{slug}/reviews").path("get");
        assertThat(salonReviews.isMissingNode()).as("phase 384 salon reviews route in the spec").isFalse();
    }
}
