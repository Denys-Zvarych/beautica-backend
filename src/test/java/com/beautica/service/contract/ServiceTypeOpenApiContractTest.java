package com.beautica.service.contract;

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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI contract-hardening regression net for Phase 16.5.
 *
 * <p>The mobile client generates its Dio API client from the backend's live
 * {@code /api-docs} spec (mobile {@code tool/openapi/fetch_spec.dart}). A silent
 * change to the published contract — making an optional field required, or
 * dropping the nullability of {@code serviceTypeId} — would break mobile codegen
 * or produce an incorrect client at runtime. These tests pin the exact contract
 * shape that Phase 16.5 hardened so any future regression fails CI here, in the
 * backend, before it ever reaches the mobile build.
 *
 * <h2>Why a dedicated context</h2>
 * SpringDoc is disabled in the {@code test} profile (application.yml sets
 * {@code springdoc.api-docs.enabled=false}; {@code SwaggerSecurityTest} asserts
 * that disabling). To render the real spec we re-enable it via
 * {@code @TestPropertySource}. This forks a separate cached Spring context but
 * reuses the singleton Testcontainers Postgres from {@link AbstractIntegrationTest},
 * so the spec is rendered against a freshly-migrated DB — not the local V73-drift
 * dev database.
 *
 * <h2>OpenAPI 3.1 nullability</h2>
 * The backend emits OpenAPI 3.1.0, where a nullable field is expressed as
 * {@code type: ["string", "null"]} (a JSON array), NOT the 3.0
 * {@code nullable: true} idiom. Assertions below tolerate the {@code type} value
 * serialising as either a single string or an array, and check membership of
 * {@code "null"} in the array form.
 *
 * <p>The spec is fetched once for the whole class (it is immutable for a given
 * context) and shared via a parsed root node — no per-test mutation, so the
 * shared fixture is safe.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("OpenAPI contract — service-type nullability & required-param hardening (Phase 16.5)")
class ServiceTypeOpenApiContractTest extends AbstractIntegrationTest {

    private static final String API_DOCS_PATH = "/api-docs";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    private JsonNode spec;

    @BeforeAll
    void fetchSpec() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(API_DOCS_PATH, String.class);

        assertThat(response.getStatusCode())
                .as("GET %s must return 200 once SpringDoc is enabled — got %s. "
                        + "If this fails, the contract test cannot run and the regression net is dead.",
                        API_DOCS_PATH, response.getStatusCode())
                .isEqualTo(HttpStatus.OK);

        spec = MAPPER.readTree(response.getBody());

        assertThat(spec.path("openapi").asText())
                .as("Published spec must be OpenAPI 3.1.x — nullability assertions below "
                        + "depend on the 3.1 type-array representation, actual=%s",
                        spec.path("openapi").asText())
                .startsWith("3.1");
    }

    // ── 1. categoryName query param is required ───────────────────────────────

    @Test
    @DisplayName("GET /api/v1/service-types — categoryName parameter is documented required:true")
    void should_documentCategoryNameAsRequired_when_specIsPublished() {
        JsonNode parameters = spec
                .path("paths")
                .path("/api/v1/service-types")
                .path("get")
                .path("parameters");

        assertThat(parameters.isArray())
                .as("GET /api/v1/service-types must publish a parameters array; node=%s", parameters)
                .isTrue();

        JsonNode categoryNameParam = findParameterByName(parameters, "categoryName");

        assertThat(categoryNameParam)
                .as("categoryName parameter must be present in the published spec for "
                        + "GET /api/v1/service-types — the @Parameter annotation drives mobile codegen")
                .isNotNull();

        assertThat(categoryNameParam.path("required").asBoolean(false))
                .as("categoryName must be documented required:true (@Parameter(required=true)); "
                        + "node=%s", categoryNameParam)
                .isTrue();
    }

    // ── 1b. GET /service-types 200 is a single unambiguous schema (no oneOf) ──

    @Test
    @DisplayName("GET /api/v1/service-types — 200 schema is a single $ref to "
            + "ApiResponseListPlatformServiceTypeResponse, never a oneOf")
    void should_publishSingle200Schema_when_specIsPublished() {
        JsonNode schema = spec
                .path("paths")
                .path("/api/v1/service-types")
                .path("get")
                .path("responses")
                .path("200")
                .path("content");

        // Content media type key varies ("*/*" for ApiResponse) — take the first entry.
        JsonNode responseSchema = schema.elements().hasNext()
                ? schema.elements().next().path("schema")
                : MAPPER.missingNode();

        assertThat(responseSchema.has("oneOf"))
                .as("GET /api/v1/service-types 200 must NOT be a oneOf — the legacy "
                        + "getServiceTypes operation must be @Operation(hidden=true) so SpringDoc "
                        + "does not collapse two operations on this path into an ambiguous oneOf "
                        + "that breaks mobile codegen; schema=%s", responseSchema)
                .isFalse();

        assertThat(responseSchema.path("$ref").asText())
                .as("GET /api/v1/service-types 200 must $ref the single platform-type wrapper "
                        + "ApiResponseListPlatformServiceTypeResponse; schema=%s", responseSchema)
                .endsWith("/ApiResponseListPlatformServiceTypeResponse");
    }

    @Test
    @DisplayName("GET /api/v1/service-types — legacy categoryId & q params are NOT published "
            + "(legacy operation hidden)")
    void should_notPublishLegacyParams_when_specIsPublished() {
        JsonNode parameters = spec
                .path("paths")
                .path("/api/v1/service-types")
                .path("get")
                .path("parameters");

        assertThat(findParameterByName(parameters, "categoryId"))
                .as("legacy categoryId param must not appear — the legacy operation is hidden; params=%s",
                        parameters)
                .isNull();
        assertThat(findParameterByName(parameters, "q"))
                .as("legacy q param must not appear — the legacy operation is hidden; params=%s",
                        parameters)
                .isNull();
    }

    // ── 2. CreateServiceDefinitionRequest schema ──────────────────────────────

    @Test
    @DisplayName("CreateServiceDefinitionRequest — serviceTypeId present, non-nullable, and IN required[] (mandatory since V111)")
    void should_documentServiceTypeIdAsRequired_when_createRequestSchemaPublished() {
        JsonNode schema = schema("CreateServiceDefinitionRequest");

        JsonNode serviceTypeId = schema.path("properties").path("serviceTypeId");
        assertThat(serviceTypeId.isMissingNode())
                .as("CreateServiceDefinitionRequest.serviceTypeId property must be present; schema=%s", schema)
                .isFalse();

        // Phase 16.x / V111: the picker can no longer be skipped. The DTO carries @NotNull and
        // Schema.RequiredMode.REQUIRED, so SpringDoc must NOT mark the field nullable and MUST
        // list it in required[]. Mobile codegen keys off both signals to make the field mandatory.
        assertThat(typeContainsNull(serviceTypeId))
                .as("CreateServiceDefinitionRequest.serviceTypeId must NOT be nullable now that a "
                        + "service type is mandatory; type node=%s", serviceTypeId.path("type"))
                .isFalse();

        List<String> required = requiredFieldNames(schema);

        assertThat(required)
                .as("serviceTypeId MUST be in the schema required[] — the picker is mandatory since V111; required=%s",
                        required)
                .contains("serviceTypeId");

        // name is still OPTIONAL on create: when blank, the service layer defaults it to the
        // selected service type's Ukrainian name. @NotBlank was removed, so SpringDoc must not
        // list name in required[]. The mobile create form lets masters leave it blank.
        assertThat(required)
                .as("name must NOT be in the schema required[] — it is optional and defaults to "
                        + "the service-type name when blank; required=%s", required)
                .doesNotContain("name");
    }

    @Test
    @DisplayName("UpdateServiceDefinitionRequest — serviceTypeId present and nullable")
    void should_documentServiceTypeIdAsNullable_when_updateRequestSchemaPublished() {
        JsonNode schema = schema("UpdateServiceDefinitionRequest");

        JsonNode serviceTypeId = schema.path("properties").path("serviceTypeId");
        assertThat(serviceTypeId.isMissingNode())
                .as("UpdateServiceDefinitionRequest.serviceTypeId property must be present; schema=%s", schema)
                .isFalse();

        assertThat(typeContainsNull(serviceTypeId))
                .as("UpdateServiceDefinitionRequest.serviceTypeId must be nullable "
                        + "(type contains \"null\") in OpenAPI 3.1; type node=%s", serviceTypeId.path("type"))
                .isTrue();
    }

    // ── 3. Response schemas expose nullable serviceTypeId + serviceTypeNameUk ──

    @Test
    @DisplayName("ServiceDefinitionResponse — serviceTypeId & serviceTypeNameUk present and nullable")
    void should_documentServiceTypeFieldsAsNullable_when_serviceDefinitionResponseSchemaPublished() {
        assertNullableServiceTypeFields("ServiceDefinitionResponse");
    }

    @Test
    @DisplayName("MasterServiceResponse — serviceTypeId & serviceTypeNameUk present and nullable")
    void should_documentServiceTypeFieldsAsNullable_when_masterServiceResponseSchemaPublished() {
        assertNullableServiceTypeFields("MasterServiceResponse");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assertNullableServiceTypeFields(String schemaName) {
        JsonNode schema = schema(schemaName);
        JsonNode properties = schema.path("properties");

        for (String field : List.of("serviceTypeId", "serviceTypeNameUk")) {
            JsonNode fieldNode = properties.path(field);
            assertThat(fieldNode.isMissingNode())
                    .as("%s.%s property must be present in the published schema; schema=%s",
                            schemaName, field, schema)
                    .isFalse();

            assertThat(typeContainsNull(fieldNode))
                    .as("%s.%s must be nullable (type contains \"null\") in OpenAPI 3.1; type node=%s",
                            schemaName, field, fieldNode.path("type"))
                    .isTrue();
        }
    }

    private JsonNode schema(String schemaName) {
        JsonNode schema = spec.path("components").path("schemas").path(schemaName);
        assertThat(schema.isMissingNode())
                .as("components.schemas.%s must be published; without it the contract assertion is meaningless",
                        schemaName)
                .isFalse();
        return schema;
    }

    private static JsonNode findParameterByName(JsonNode parameters, String name) {
        for (JsonNode param : parameters) {
            if (name.equals(param.path("name").asText())) {
                return param;
            }
        }
        return null;
    }

    /**
     * Resilient to the OpenAPI 3.1 {@code type} representation: a field is
     * nullable when {@code type} is the array {@code [..., "null"]}, OR when the
     * 3.0 {@code nullable:true} idiom is present (defensive — not expected here).
     */
    private static boolean typeContainsNull(JsonNode fieldNode) {
        JsonNode type = fieldNode.path("type");
        if (type.isArray()) {
            for (JsonNode t : type) {
                if ("null".equals(t.asText())) {
                    return true;
                }
            }
        }
        return fieldNode.path("nullable").asBoolean(false);
    }

    private static List<String> requiredFieldNames(JsonNode schema) {
        List<String> names = new ArrayList<>();
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            required.forEach(n -> names.add(n.asText()));
        }
        return names;
    }
}
