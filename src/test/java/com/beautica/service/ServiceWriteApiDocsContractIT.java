package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test guarding against re-regression of the springdoc "lone {@code @ApiResponse}
 * drops the typed success body" bug on the five service <b>write</b> endpoints in
 * {@link com.beautica.service.controller.ServiceController}.
 *
 * <h2>The bug this guards</h2>
 * Each write endpoint declares a method-level 409 ({@code DuplicateServiceErrorResponse}).
 * springdoc treats a lone method-level {@code @ApiResponse} as the COMPLETE response set and
 * DROPS the auto-derived typed success response — the endpoint is then documented with a
 * {@code void}/empty {@code 200} body. The generated Dart client types the method
 * {@code Response<void>}, breaking {@code res.data?.data} in
 * {@code beautica-mobile/.../service_repository.dart}. The fix wraps each 409 together with an
 * explicit {@code @ApiResponse(responseCode = "200", useReturnTypeSchema = true)} inside
 * {@code @ApiResponses}, so springdoc keeps the typed {@code ApiResponse<Dto>} success schema.
 *
 * <p>This test boots the full context, fetches {@code /api-docs}, and asserts that for each of
 * the five write paths the {@code 200} response carries a NON-empty {@code content} whose JSON
 * schema resolves to a real model ({@code $ref} or an inline object) — i.e. NOT a void/empty
 * success. It asserts on the LIVE, in-memory OpenAPI document only; it never writes the spec to
 * {@code tool/openapi} (no snapshot commit) and never exercises a test-only endpoint.
 *
 * <p>springdoc's {@code api-docs} endpoint is disabled in the base config (and {@code test}
 * profile inherits that), so this test re-enables it locally via {@link TestPropertySource}.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api-docs"
})
@DisplayName("ServiceController write endpoints — /api-docs keeps a typed (non-void) success body")
class ServiceWriteApiDocsContractIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The five write paths that carry a 409 {@code DuplicateServiceErrorResponse} and therefore
     * previously lost their typed success body. Format: {@code HTTP_METHOD, /openapi/path}.
     */
    @ParameterizedTest(name = "{0} {1} → 200 has a non-void typed success schema")
    @CsvSource({
            "post,  /api/v1/salons/{salonId}/services",
            "post,  /api/v1/independent-masters/me/services",
            "post,  /api/v1/independent-masters/me/services/bulk",
            "post,  /api/v1/salons/{salonId}/masters/{masterId}/services/bulk",
            "patch, /api/v1/services/{serviceDefId}"
    })
    @DisplayName("each service write endpoint documents a schema-bearing 200 response")
    void should_documentTypedSuccessBody_when_writeEndpointAlsoDeclares409(
            String httpMethod, String openApiPath) throws Exception {

        JsonNode operation = fetchApiDocs().path("paths").path(openApiPath).path(httpMethod);

        assertThat(operation.isMissingNode())
                .as("operation %s %s must exist in /api-docs", httpMethod, openApiPath)
                .isFalse();

        // The regression manifests as a 200 with no `content` (void/empty success body).
        JsonNode successContent = operation.path("responses").path("200").path("content");
        assertThat(successContent.isMissingNode() || successContent.isEmpty())
                .as("200 response for %s %s MUST carry a content block — an empty/void success "
                        + "means springdoc dropped the typed return schema (the regression)",
                        httpMethod, openApiPath)
                .isFalse();

        // The mapping declares no `produces`, so springdoc keys the media type as `*/*`
        // (springdoc.default-produces-media-type) rather than `application/json`. Read the schema
        // from whichever single media type the content block actually carries.
        JsonNode mediaType = successContent.elements().next();
        JsonNode schema = mediaType.path("schema");
        assertThat(schema.isMissingNode())
                .as("200 content for %s %s must declare a schema under its media type",
                        httpMethod, openApiPath)
                .isFalse();

        boolean hasResolvableSchema =
                schema.hasNonNull("$ref")                       // named component (typical)
                        || "object".equals(schema.path("type").asText())  // inline object
                        || schema.has("allOf") || schema.has("oneOf") || schema.has("anyOf");
        assertThat(hasResolvableSchema)
                .as("200 schema for %s %s must resolve to a real model ($ref/object), not a void "
                        + "success — schema was: %s", httpMethod, openApiPath, schema)
                .isTrue();

        // Sanity: the 409 the endpoint also declares is still present (the fix must not drop it).
        assertThat(operation.path("responses").has("409"))
                .as("the 409 DuplicateServiceErrorResponse must survive alongside the typed 200 "
                        + "for %s %s", httpMethod, openApiPath)
                .isTrue();
    }

    private JsonNode fetchApiDocs() throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(resp.getStatusCode())
                .as("/api-docs must be reachable (springdoc enabled), body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }
}
