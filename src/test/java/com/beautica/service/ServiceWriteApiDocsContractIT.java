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
            "post,  /api/v1/salons/{salonId}/masters/{masterId}/services,       409",
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

    // ── Phase 305 D3 — 409 DUPLICATE_SERVICE is published as PER-MASTER on the salon-master bulk
    // endpoint, not the generic per-owner text every other write endpoint carries. Phase 302 D4
    // narrowed this endpoint's conflict scope: a call that used to 409 for a salon's SECOND master
    // now returns 201 (the salon's existing definition is reused). The mobile client's error
    // handling must be regenerated against the true, per-master semantics.

    @Test
    @DisplayName("Phase 305 D3: POST .../masters/{masterId}/services/bulk documents its 409 "
            + "DUPLICATE_SERVICE as PER-MASTER (Phase 302 D4), not the generic per-owner text")
    void should_documentDuplicateServiceAsPerMaster_when_salonMasterBulkEndpointPublishesSpec()
            throws Exception {
        JsonNode operation = fetchApiDocs()
                .path("paths").path("/api/v1/salons/{salonId}/masters/{masterId}/services/bulk").path("post");

        assertThat(operation.isMissingNode())
                .as("POST .../masters/{masterId}/services/bulk must exist in /api-docs")
                .isFalse();

        String description = operation.path("responses").path("409").path("description").asText();

        assertThat(description)
                .as("the 409 description must name the conflict as PER-MASTER — a dropped or "
                        + "reverted annotation would fall back to the generic per-owner "
                        + "DUPLICATE_SERVICE_409 text, which says nothing about Phase 302 D4's "
                        + "narrowing; description was: %s", description)
                .contains("per-MASTER")
                .contains("another master in the same salon");
    }

    // ── Phase 313 D4 — single-assign declares its 409 DUPLICATE_SERVICE schema AND keeps its
    // typed 200 alongside the pre-existing 429. springdoc does not scan GlobalExceptionHandler, so
    // without the endpoint-level @ApiResponse the 409 body has no schema in /api-docs at all
    // (case 11); the lone-@ApiResponse trap would otherwise drop the auto-derived typed 200 the
    // moment the annotation set became "409, 429" without an explicit 200 entry (case 12).

    @Test
    @DisplayName("Phase 313 case 11: POST .../masters/{masterId}/services declares a 409 whose "
            + "schema $refs DuplicateServiceErrorResponse, reusing the bulk endpoint's schema")
    void should_documentDuplicateServiceSchema_when_singleAssignEndpointPublishesSpec() throws Exception {
        JsonNode operation = fetchApiDocs()
                .path("paths").path("/api/v1/salons/{salonId}/masters/{masterId}/services").path("post");

        assertThat(operation.isMissingNode())
                .as("POST .../masters/{masterId}/services must exist in /api-docs")
                .isFalse();

        JsonNode response409 = operation.path("responses").path("409");
        assertThat(response409.isMissingNode())
                .as("Phase 313 D4 — the single-assign endpoint must declare a 409 response")
                .isFalse();

        JsonNode schema409 = response409.path("content").elements().hasNext()
                ? response409.path("content").elements().next().path("schema")
                : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        assertThat(schema409.path("$ref").asText())
                .as("the 409 schema must $ref DuplicateServiceErrorResponse, the same schema the "
                        + "bulk endpoint declares — no parallel error DTO; schema was: %s", schema409)
                .endsWith("/DuplicateServiceErrorResponse");
    }

    @Test
    @DisplayName("Phase 313 case 12: POST .../masters/{masterId}/services still declares a typed "
            + "200 AND its pre-existing 429, alongside the new 409 — the lone-@ApiResponse "
            + "regression guard for a THREE-entry @ApiResponses set")
    void should_keepTyped200And429_when_singleAssignEndpointGains409() throws Exception {
        JsonNode operation = fetchApiDocs()
                .path("paths").path("/api/v1/salons/{salonId}/masters/{masterId}/services").path("post");

        assertThat(operation.isMissingNode())
                .as("POST .../masters/{masterId}/services must exist in /api-docs")
                .isFalse();

        JsonNode responses = operation.path("responses");
        JsonNode successContent = responses.path("200").path("content");
        assertThat(successContent.isMissingNode() || successContent.isEmpty())
                .as("200 response MUST carry a content block — an empty/void success means "
                        + "springdoc dropped the typed return schema; responses were: %s", responses)
                .isFalse();
        assertThat(responses.has("429"))
                .as("the pre-existing 429 rate-limit response must survive alongside the new 409; "
                        + "responses were: %s", responses)
                .isTrue();
        assertThat(responses.has("409"))
                .as("the new 409 must be present too; responses were: %s", responses)
                .isTrue();
    }

    // ── Phase 305 D4 — every {masterId} in this track documents that it is a `masters` row id,
    // NOT a userId. Passing a userId yields 404 "Master not found", which reads like a missing
    // master rather than a wrong identifier — a live footgun on the exact screens this track
    // unblocks (ServiceCatalogService.java masterRepository.findById call sites).

    @ParameterizedTest(name = "{0} {1} — masterId parameter documents \"Master row id (NOT a user id)\"")
    @CsvSource({
            "post, /api/v1/salons/{salonId}/masters/{masterId}/services",
            "post, /api/v1/salons/{salonId}/masters/{masterId}/services/bulk"
    })
    @DisplayName("Phase 305 D4: every {masterId} path parameter documents it is a master ROW id, "
            + "not a userId — asserted so it cannot be dropped in a later annotation tidy-up")
    void should_documentMasterIdAsRowIdNotUserId_when_endpointTakesMasterIdPathParam(
            String httpMethod, String openApiPath) throws Exception {

        JsonNode operation = fetchApiDocs().path("paths").path(openApiPath).path(httpMethod);
        assertThat(operation.isMissingNode())
                .as("operation %s %s must exist in /api-docs", httpMethod, openApiPath)
                .isFalse();

        JsonNode masterIdParam = findParameterByName(operation.path("parameters"), "masterId");
        assertThat(masterIdParam)
                .as("%s %s must publish a masterId path parameter", httpMethod, openApiPath)
                .isNotNull();

        assertThat(masterIdParam.path("description").asText())
                .as("masterId's @Parameter description must state it is a master row id, not a "
                        + "userId — the exact footgun documented in Phase 305 D4; node=%s",
                        masterIdParam)
                .isEqualTo("Master row id (NOT a user id)");
    }

    private static JsonNode findParameterByName(JsonNode parameters, String name) {
        for (JsonNode param : parameters) {
            if (name.equals(param.path("name").asText())) {
                return param;
            }
        }
        return null;
    }

    private JsonNode fetchApiDocs() throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(resp.getStatusCode())
                .as("/api-docs must be reachable (springdoc enabled), body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }
}
