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

import java.util.List;

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

    @Test
    @DisplayName("POST /api/v1/salons/{salonId}/portfolio declares multipart/form-data only (Phase 362)")
    void should_declareMultipartOnly_when_salonPortfolioUpload() {
        JsonNode content = requestContent("/api/v1/salons/{salonId}/portfolio");

        assertThat(content.has(MULTIPART)).as("multipart/form-data present").isTrue();
        assertThat(content.has("application/json")).as("no application/json").isFalse();
        assertThat(content.path(MULTIPART).toString()).contains("binary");
    }

    @Test
    @DisplayName("Phase 362: salon portfolio DELETE is documented; /media/portfolio is documented as INDEPENDENT_MASTER only")
    void should_documentPortfolioContract_when_specGenerated() {
        JsonNode salonItem = spec.path("paths").path("/api/v1/salons/{salonId}/portfolio/{mediaId}");
        JsonNode mediaPortfolioPost = spec.path("paths").path("/api/v1/media/portfolio").path("post");

        assertThat(salonItem.has("delete")).as("DELETE /salons/{salonId}/portfolio/{mediaId} present").isTrue();
        assertThat(spec.path("paths").path("/api/v1/salons/{salonId}/portfolio").path("post")
                .path("responses").has("409")).as("salon upload documents the 409 cap").isTrue();
        assertThat(mediaPortfolioPost.path("summary").asText()).contains("INDEPENDENT_MASTER only");
        assertThat(mediaPortfolioPost.path("responses").has("409")).as("master upload documents the 409 cap").isTrue();
    }

    @Test
    @DisplayName("Phase 362 audit F6: both portfolio POSTs document 409 = PORTFOLIO_LIMIT_REACHED and the busy 503 "
            + "with Retry-After")
    void should_documentCapCodeAndBusy503_when_specGenerated() {
        JsonNode salonPost = spec.path("paths").path("/api/v1/salons/{salonId}/portfolio").path("post");
        JsonNode masterPost = spec.path("paths").path("/api/v1/media/portfolio").path("post");

        for (JsonNode post : List.of(salonPost, masterPost)) {
            assertThat(post.path("responses").path("409").path("description").asText())
                    .contains("PORTFOLIO_LIMIT_REACHED");
            assertThat(post.path("responses").path("503").path("description").asText())
                    .contains("busy").contains("Retry-After");
        }
    }

    @Test
    @DisplayName("Phase 362: salon portfolio path keeps the public GET beside POST (201) and DELETE (204/403/404); "
            + "master DELETE documented as INDEPENDENT_MASTER only")
    void should_documentSalonPortfolioStatusesAndPublicGet_when_specGenerated() {
        JsonNode collection = spec.path("paths").path("/api/v1/salons/{salonId}/portfolio");
        JsonNode itemDelete = spec.path("paths").path("/api/v1/salons/{salonId}/portfolio/{mediaId}").path("delete");
        JsonNode masterDelete = spec.path("paths").path("/api/v1/media/portfolio/{mediaId}").path("delete");

        assertThat(collection.has("get")).as("public GET not shadowed by the new POST").isTrue();
        assertThat(collection.path("post").path("responses").has("201")).as("POST documents 201").isTrue();
        assertThat(collection.path("post").path("responses").has("200")).as("POST never documents 200").isFalse();
        assertThat(itemDelete.path("responses").has("204")).as("DELETE documents 204").isTrue();
        assertThat(itemDelete.path("responses").has("403")).as("DELETE documents 403").isTrue();
        assertThat(itemDelete.path("responses").has("404")).as("DELETE documents 404").isTrue();
        assertThat(masterDelete.path("summary").asText()).contains("INDEPENDENT_MASTER only");
        // H3 (2026-10-12): another master's photo is a 404, not a 403 — 403 is the role gate only.
        assertThat(masterDelete.path("responses").path("403").path("description").asText())
                .as("master DELETE 403 documents the role gate, never another master's photo")
                .contains("not an INDEPENDENT_MASTER")
                .doesNotContain("another master");
        assertThat(masterDelete.path("responses").path("404").path("description").asText())
                .as("master DELETE 404 covers another master's photo")
                .contains("another master's photo");
    }

    @Test
    @DisplayName("POST /api/v1/services/{serviceDefId}/photo declares multipart/form-data only (Phase 342)")
    void should_declareMultipartOnly_when_servicePhotoUpload() {
        JsonNode content = requestContent("/api/v1/services/{serviceDefId}/photo");

        assertThat(content.has(MULTIPART)).as("multipart/form-data present").isTrue();
        assertThat(content.has("application/json")).as("no application/json").isFalse();
        assertThat(content.path(MULTIPART).toString()).contains("binary");
    }

    @Test
    @DisplayName("PATCH /api/v1/services/{serviceDefId}/photo and UpdateServicePhotoRequest are gone (Phase 342)")
    void should_notPublishPatchPhotoOrItsRequestSchema() {
        JsonNode photoPath = spec.path("paths").path("/api/v1/services/{serviceDefId}/photo");

        assertThat(photoPath.has("patch")).as("no PATCH operation").isFalse();
        assertThat(photoPath.has("post")).as("POST present").isTrue();
        assertThat(photoPath.has("delete")).as("DELETE present").isTrue();
        assertThat(spec.path("components").path("schemas").has("UpdateServicePhotoRequest"))
                .as("request schema removed").isFalse();
    }

    @Test
    @DisplayName("DELETE /api/v1/services/{serviceDefId}/photo documents a 204")
    void should_documentNoContent_when_servicePhotoDelete() {
        JsonNode responses = spec.path("paths").path("/api/v1/services/{serviceDefId}/photo")
                .path("delete").path("responses");

        assertThat(responses.has("204")).isTrue();
    }

    private static final String SALON_IMAGE_PATH = "/api/v1/salons/{salonId}/media/{slot}";

    @Test
    @DisplayName("POST /api/v1/salons/{salonId}/media/{slot} is multipart-only with a binary `file` part (Phase 343)")
    void should_declareMultipartFilePart_when_salonImageUpload() {
        JsonNode content = requestContent(SALON_IMAGE_PATH);

        assertThat(content.has(MULTIPART)).as("multipart/form-data present").isTrue();
        assertThat(content.has("application/json")).as("no application/json").isFalse();
        assertThat(content.path(MULTIPART).path("schema").path("properties").path("file").path("format").asText())
                .as("the part is named `file` and is binary — the generated client and seed script send it so")
                .isEqualTo("binary");
    }

    @Test
    @DisplayName("salon image {slot} is published as the lowercase enum [logo, cover] on POST and DELETE; "
            + "DELETE documents 204 (Phase 343)")
    void should_publishLowercaseSlotEnumAndDelete204_when_salonImagePath() {
        JsonNode path = spec.path("paths").path(SALON_IMAGE_PATH);

        for (String verb : new String[]{"post", "delete"}) {
            JsonNode slotParam = null;
            for (JsonNode p : path.path(verb).path("parameters")) {
                if ("slot".equals(p.path("name").asText())) {
                    slotParam = p;
                }
            }
            assertThat(slotParam).as("%s has a {slot} path parameter", verb).isNotNull();
            assertThat(slotParam.path("schema").path("enum").toString())
                    .as("%s slot enum", verb).isEqualTo("[\"logo\",\"cover\"]");
        }
        assertThat(path.path("delete").path("responses").has("204")).isTrue();
    }

    @Test
    @DisplayName("SalonResponse and PublicSalonResponse both publish avatarUrl and coverImageUrl, never an R2 key "
            + "(Phase 343)")
    void should_publishImageUrlsWithoutKeys_when_salonSchemas() {
        for (String schema : new String[]{"SalonResponse", "PublicSalonResponse"}) {
            JsonNode props = spec.path("components").path("schemas").path(schema).path("properties");

            assertThat(props.has("avatarUrl")).as("%s.avatarUrl", schema).isTrue();
            assertThat(props.has("coverImageUrl")).as("%s.coverImageUrl", schema).isTrue();
            assertThat(props.has("avatarR2Key") || props.has("coverR2Key")).as("%s exposes no R2 key", schema).isFalse();
        }
    }
}
