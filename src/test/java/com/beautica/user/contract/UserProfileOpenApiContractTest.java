package com.beautica.user.contract;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI contract pin for Phase 265's {@code UserProfileResponse.hasMasterProfile}.
 *
 * <h2>Why this test exists</h2>
 * The mobile client generates its Dio API client from the backend's live {@code /api-docs} spec
 * (mobile {@code tool/openapi/fetch_spec.dart}). {@code hasMasterProfile} is a <em>render gate</em>:
 * the owner-settings hub decides whether to draw «Я також працюю як майстер» — and whether to fire
 * {@code GET /api/v1/masters/me} at all — from this one boolean. If the field silently stops being
 * published, mobile codegen drops the accessor and the screen regresses to firing a speculative call
 * that 404s for every opted-out owner. Every other Phase 265 test asserts the value; this one
 * asserts the field is on the wire contract the client is generated from.
 *
 * <h2>Method — precedent, not invention</h2>
 * This follows {@link com.beautica.service.contract.ServiceTypeOpenApiContractTest} exactly: fetch
 * the rendered spec once over HTTP and assert against the parsed {@link JsonNode} <b>in memory</b>.
 * The spec is deliberately NOT written to a file. A snapshot taken from a {@code @SpringBootTest}
 * context also contains the test-only {@code json-cap-echo} endpoint and its {@code EchoRequest}
 * schema, so committing one would leak test scaffolding into the published contract the mobile
 * regeneration script diffs against.
 *
 * <h2>Why a {@code @TestPropertySource} and why these exact two properties</h2>
 * SpringDoc is disabled in the {@code test} profile ({@code springdoc.api-docs.enabled=false};
 * {@code SwaggerSecurityTest} asserts that disabling), so the spec must be re-enabled to render it.
 * The two property overrides below are byte-identical to {@code ServiceTypeOpenApiContractTest}'s,
 * and this class adds no other context-differentiating annotation — so Spring's test-context cache
 * hands both classes the SAME context instead of forking a second one (Q3).
 *
 * <h2>OpenAPI 3.1 nullability</h2>
 * The backend emits OpenAPI 3.1.0, where a nullable field is {@code type: ["boolean", "null"]}, not
 * the 3.0 {@code nullable: true} idiom. {@code hasMasterProfile} is a primitive {@code boolean} and
 * must publish as non-nullable, so mobile codegen makes it a plain {@code bool} rather than a
 * {@code bool?} the screen has to null-guard.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("OpenAPI contract — UserProfileResponse.hasMasterProfile (Phase 265)")
class UserProfileOpenApiContractTest extends AbstractIntegrationTest {

    private static final String API_DOCS_PATH = "/api-docs";
    private static final String SCHEMA_NAME = "UserProfileResponse";
    private static final String FIELD = "hasMasterProfile";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    private JsonNode spec;

    @BeforeAll
    void fetchSpec() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(API_DOCS_PATH, String.class);

        assertThat(response.getStatusCode())
                .as("GET %s must return 200 once SpringDoc is enabled — got %s. If this fails the "
                        + "contract net is dead, not passing.", API_DOCS_PATH, response.getStatusCode())
                .isEqualTo(HttpStatus.OK);

        spec = MAPPER.readTree(response.getBody());

        assertThat(spec.path("openapi").asText())
                .as("published spec must be OpenAPI 3.1.x — the nullability assertion below depends "
                        + "on the 3.1 type-array representation, actual=%s", spec.path("openapi").asText())
                .startsWith("3.1");
    }

    @Test
    @DisplayName("components.schemas.UserProfileResponse publishes hasMasterProfile as a "
            + "non-nullable boolean")
    void should_exposeFieldInOpenApiSpec_when_profileSchemaIsGenerated() {
        JsonNode schema = spec.path("components").path("schemas").path(SCHEMA_NAME);

        assertThat(schema.isMissingNode())
                .as("components.schemas.%s must be published — without the schema itself every "
                        + "assertion about its fields is vacuous; schema keys=%s",
                        SCHEMA_NAME, spec.path("components").path("schemas").fieldNames())
                .isFalse();

        JsonNode field = schema.path("properties").path(FIELD);

        assertThat(field.isMissingNode())
                .as("%s.%s must be present in the published schema — the mobile owner-settings hub "
                        + "reads this exact wire name to decide whether to draw the toggle and "
                        + "whether GET /masters/me will 404; published properties=%s",
                        SCHEMA_NAME, FIELD, schema.path("properties").fieldNames())
                .isFalse();

        assertThat(typeNames(field))
                .as("%s.%s must publish as a plain boolean; type node=%s",
                        SCHEMA_NAME, FIELD, field.path("type"))
                .contains("boolean");

        assertThat(typeNames(field))
                .as("%s.%s is a primitive boolean and must NOT be nullable — a nullable publish "
                        + "makes mobile codegen emit bool? and pushes a null-guard into every "
                        + "consumer of a field that can never be null; type node=%s",
                        SCHEMA_NAME, FIELD, field.path("type"))
                .doesNotContain("null");
    }

    @Test
    @DisplayName("GET /api/v1/users/me's 200 response is the wrapper that carries the "
            + "UserProfileResponse schema")
    void should_referenceProfileSchemaFromUsersMe_when_specIsPublished() {
        // Guards the other half of the contract: a schema present in components.schemas but no
        // longer reachable from the endpoint the client calls is a dead schema, and the assertion
        // above would still pass while mobile's generated UsersApi lost the field entirely.
        JsonNode content = spec
                .path("paths")
                .path("/api/v1/users/me")
                .path("get")
                .path("responses")
                .path("200")
                .path("content");

        assertThat(content.elements().hasNext())
                .as("GET /api/v1/users/me must publish a 200 response body; content node=%s", content)
                .isTrue();

        JsonNode responseSchema = content.elements().next().path("schema");

        assertThat(responseSchema.path("$ref").asText())
                .as("GET /api/v1/users/me 200 must $ref the ApiResponse wrapper around %s, so the "
                        + "hasMasterProfile-bearing schema is reachable from the operation mobile "
                        + "generates; schema=%s", SCHEMA_NAME, responseSchema)
                .endsWith("/ApiResponse" + SCHEMA_NAME);
    }

    /**
     * The declared type(s) of a schema node, tolerating both the OpenAPI 3.1 array form
     * ({@code ["boolean","null"]}) and a single-string {@code type}.
     */
    private static java.util.List<String> typeNames(JsonNode fieldNode) {
        JsonNode type = fieldNode.path("type");
        java.util.List<String> names = new java.util.ArrayList<>();
        if (type.isArray()) {
            type.forEach(t -> names.add(t.asText()));
        } else if (type.isTextual()) {
            names.add(type.asText());
        }
        if (fieldNode.path("nullable").asBoolean(false)) {
            names.add("null");
        }
        return names;
    }
}
