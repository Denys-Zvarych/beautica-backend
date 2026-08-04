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
 * drops the typed success body" bug on the service <b>write</b> endpoints in
 * {@link com.beautica.service.controller.ServiceController}.
 *
 * <h2>The bug this guards</h2>
 * Each write endpoint declares at least one method-level non-2xx {@code @ApiResponse} — a 409
 * ({@code DuplicateServiceErrorResponse}) and/or the 429 added with {@code serviceWriteBuckets}.
 * springdoc treats a lone method-level {@code @ApiResponse} as the COMPLETE response set and
 * DROPS the auto-derived typed success response — the endpoint is then documented with a
 * {@code void}/empty {@code 200} body. The generated Dart client types the method
 * {@code Response<void>}, breaking {@code res.data?.data} in
 * {@code beautica-mobile/.../service_repository.dart}. The fix wraps each 409 together with an
 * explicit {@code @ApiResponse(responseCode = "200", useReturnTypeSchema = true)} inside
 * {@code @ApiResponses}, so springdoc keeps the typed {@code ApiResponse<Dto>} success schema.
 *
 * <p>This test boots the full context, fetches {@code /api-docs}, and asserts that for each
 * typed write path the {@code 200} response carries a NON-empty {@code content} whose JSON
 * schema resolves to a real model ({@code $ref} or an inline object) — i.e. NOT a void/empty
 * success. The one void write endpoint ({@code DELETE /services/{serviceDefId}}) gets its own
 * weaker guard: the success response must still be documented at all.
 * It asserts on the LIVE, in-memory OpenAPI document only; it never writes the spec to
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
     * Every write path whose handler returns a <b>typed</b> body and that also declares at least
     * one method-level non-2xx {@code @ApiResponse} — the exact shape that triggers the springdoc
     * bug. Format: {@code HTTP_METHOD, /openapi/path, companionErrorCode}.
     *
     * <p>{@code companionErrorCode} is the declared non-2xx response that must survive alongside
     * the typed 200; it is the annotation whose presence would, on its own, have suppressed the
     * success schema. The first five paths carry a 409 {@code DuplicateServiceErrorResponse}; the
     * last two carry only the 429 rate-limit response added with {@code serviceWriteBuckets}.
     *
     * <p>{@code DELETE /services/{serviceDefId}} is deliberately absent: it returns
     * {@code ResponseEntity<Void>}, so a schema-bearing 200 is not merely unexpected but wrong.
     * It is covered by {@link #should_documentSuccessResponse_when_voidWriteEndpointDeclares429()}.
     */
    @ParameterizedTest(name = "{0} {1} → 200 has a non-void typed success schema")
    @CsvSource({
            "post,  /api/v1/salons/{salonId}/services,                          409",
            "post,  /api/v1/independent-masters/me/services,                    409",
            "post,  /api/v1/independent-masters/me/services/bulk,               409",
            "post,  /api/v1/salons/{salonId}/masters/{masterId}/services/bulk,  409",
            "patch, /api/v1/services/{serviceDefId},                            409",
            "post,  /api/v1/salons/{salonId}/masters/{masterId}/services,       429",
            "patch, /api/v1/services/{serviceDefId}/photo,                      429"
    })
    @DisplayName("each typed service write endpoint documents a schema-bearing 200 response")
    void should_documentTypedSuccessBody_when_writeEndpointAlsoDeclaresErrorResponse(
            String httpMethod, String openApiPath, String companionErrorCode) throws Exception {

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

        // Sanity: the declared error response is still present (the fix must not drop it).
        assertThat(operation.path("responses").has(companionErrorCode))
                .as("the declared %s response must survive alongside the typed 200 for %s %s",
                        companionErrorCode, httpMethod, openApiPath)
                .isTrue();
    }

    /**
     * {@code DELETE /services/{serviceDefId}} returns {@code ResponseEntity<Void>}, so it can never
     * carry a typed success schema and is excluded from the parameterized case above. It still
     * needs a guard: its {@code 200, useReturnTypeSchema = true} annotation exists solely so the
     * 429 is not the lone method-level {@code @ApiResponse} — which springdoc would treat as the
     * COMPLETE response set, dropping the success response from the operation entirely and typing
     * the generated Dart method as error-only. This asserts the success response is still
     * documented (and, correctly, bodiless) next to the 429.
     */
    @Test
    @DisplayName("DELETE /services/{serviceDefId} — void write endpoint still documents a "
            + "(bodiless) success response alongside its 429")
    void should_documentSuccessResponse_when_voidWriteEndpointDeclares429() throws Exception {
        String openApiPath = "/api/v1/services/{serviceDefId}";

        JsonNode operation = fetchApiDocs().path("paths").path(openApiPath).path("delete");
        assertThat(operation.isMissingNode())
                .as("operation delete %s must exist in /api-docs", openApiPath)
                .isFalse();

        JsonNode responses = operation.path("responses");
        assertThat(responses.has("200"))
                .as("delete %s MUST still document a 200 success response — its absence means "
                        + "springdoc treated the 429 as the complete response set and dropped the "
                        + "success entirely (the regression); responses were: %s",
                        openApiPath, responses)
                .isTrue();

        assertThat(responses.has("429"))
                .as("the declared 429 rate-limit response must survive alongside the 200 for "
                        + "delete %s", openApiPath)
                .isTrue();

        // Positive statement of the void contract: no typed body is expected or wanted here.
        JsonNode successContent = responses.path("200").path("content");
        assertThat(successContent.isMissingNode() || successContent.isEmpty())
                .as("delete %s returns ResponseEntity<Void>; its 200 must stay bodiless — a "
                        + "content block here means the handler's return type changed and this "
                        + "endpoint belongs in the typed parameterized case instead, content=%s",
                        openApiPath, successContent)
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
