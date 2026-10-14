package com.beautica.support;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.config.SupportProperties;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.ServiceTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Staging bug: {@code SUPPORT_EMAIL} unset made {@code POST /support/contact} answer 503. That degradation
 * is the intended LOCAL behaviour and stays; the prod/staging guard (EnvironmentFingerprintGuard rules
 * {@code prod-support-email} / {@code staging-support-email}, pinned against the committed YAML in
 * {@link com.beautica.config.ProdProfileFingerprintTest}) is what keeps a deployed instance from booting
 * into it. This class pins the HTTP side end to end on the shared test context, where
 * {@code app.support.email} resolves blank ({@code ${SUPPORT_EMAIL:}}).
 */
@Import(TestSecurityConfig.class)
@DisplayName("Support contact — blank SUPPORT_EMAIL degrades to a clean 503 end to end")
class SupportContactUnconfiguredIT extends AbstractIntegrationTest {

    private static final String CONTACT_URL = "/api/v1/support/contact";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SupportProperties supportProperties;

    @Test
    @DisplayName("POST /support/contact with SUPPORT_EMAIL blank — 503, success=false, fixed message, no e-mail sent")
    void should_return503AndSendNothing_when_supportEmailBlank() throws Exception {
        assertThat(supportProperties.getEmail())
                .as("precondition: the shared test context must leave app.support.email blank").isBlank();
        String token = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder)
                .createClientAndGetToken("support-blank-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> response = restTemplate.exchange(CONTACT_URL, HttpMethod.POST,
                new HttpEntity<>(contactBody(), bearerMultipart(token)), String.class);

        assertThat(response.getStatusCode()).as("body=%s", response.getBody())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("success").asBoolean(true)).isFalse();
        assertThat(body.path("message").asText()).isEqualTo(ServiceUnavailableMessages.SUPPORT_NOT_CONFIGURED);
        verify(emailService, never()).sendSupportContactEmail(
                anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    private MultiValueMap<String, Object> contactBody() throws Exception {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        byte[] json = objectMapper.writeValueAsString(
                        Map.of("subject", "Help", "message", "My booking screen shows nothing at all."))
                .getBytes(StandardCharsets.UTF_8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("request", new HttpEntity<>(json, partHeaders));
        return body;
    }

    private static HttpHeaders bearerMultipart(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }
}
