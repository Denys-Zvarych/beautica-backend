package com.beautica.auth;

import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.RegistrationResponse;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.common.ApiResponse;
import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.EmailAlreadyRegisteredException;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disclosure-on integration coverage for {@code POST /auth/register}.
 *
 * <p>Sibling to {@link AuthControllerIT}. The existing IT runs under the default
 * {@code test} profile where {@code app.security.disclose-duplicate-registration=false},
 * which exercises the silent-200 anti-enumeration branch. This class flips the toggle
 * on at the class level so the duplicate-email branch routes through
 * {@link EmailAlreadyRegisteredException} and the wire contract for the structured
 * 409 response is end-to-end verified.
 *
 * <p>{@code @TestPropertySource} is class-level only (Spring does not honour it on
 * methods), and applying it to {@code AuthControllerIT} would invalidate the
 * silent-200 test in that file. A separate IT class is the canonical split.
 */
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = "app.security.disclose-duplicate-registration=true")
@DisplayName("Auth endpoints — duplicate-registration disclosure integration")
class AuthControllerDuplicateRegistrationDisclosureIT extends AbstractIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(AuthControllerDuplicateRegistrationDisclosureIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("should return 409 with structured EMAIL_ALREADY_REGISTERED body when disclosure toggle is on and email is duplicate")
    void should_return409WithStructuredBody_when_disclosureToggleOn_duplicateEmail() throws Exception {
        // Arrange — first registration seeds the email, second hits the duplicate branch.
        var request = new RegisterRequest(
                "disclosure.duplicate@beautica.com", "Str0ngP@ss1!",
                SelfRegistrationRole.CLIENT, null, null, null, null);
        log.debug("Arrange: register email={} once so the second call hits the duplicate branch",
                request.email());

        ResponseEntity<String> firstResponse = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);
        assertThat(firstResponse.getStatusCode())
                .as("seed registration must succeed with 200 OK")
                .isEqualTo(HttpStatus.OK);
        var firstApiResponse = objectMapper.readValue(
                firstResponse.getBody(), new TypeReference<ApiResponse<RegistrationResponse>>() {});
        assertThat(firstApiResponse.success())
                .as("seed registration body must indicate success")
                .isTrue();

        // Act — second POST with the same email triggers the toggle-on duplicate branch.
        log.debug("Act: POST /auth/register a second time with the same email under disclose=true");
        ResponseEntity<String> secondResponse = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);

        // Assert — HTTP status is 409 (not the silent 200 the default profile returns).
        assertThat(secondResponse.getStatusCode())
                .as("disclose-on duplicate registration must surface as 409 Conflict")
                .isEqualTo(HttpStatus.CONFLICT);

        // Assert — body matches the expected wire contract, parsed via ObjectMapper so
        // a malformed JSON change fails fast instead of passing a substring assertion.
        JsonNode body = objectMapper.readTree(secondResponse.getBody());

        assertThat(body.path("success").isBoolean())
                .as("body must contain a boolean 'success' field")
                .isTrue();
        assertThat(body.path("success").asBoolean())
                .as("success must be false on the 409 path")
                .isFalse();

        assertThat(body.path("data").isObject())
                .as("body must contain a structured 'data' object")
                .isTrue();
        // Reference the constant — a rename of EmailAlreadyRegisteredException.ERROR_CODE
        // must fail this test, not silently break the mobile client's routing logic.
        assertThat(body.path("data").path("code").asText())
                .as("data.code must equal the EMAIL_ALREADY_REGISTERED constant")
                .isEqualTo(EmailAlreadyRegisteredException.ERROR_CODE);

        assertThat(body.path("message").asText())
                .as("message must be the stable wire-contract string")
                .isEqualTo("Email already registered");
    }
}
