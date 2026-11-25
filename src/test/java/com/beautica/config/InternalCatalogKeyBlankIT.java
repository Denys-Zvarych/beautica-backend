package com.beautica.config;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B501 (SVC-54) — with {@code INTERNAL_CATALOG_KEY} unset (the committed base default {@code ${INTERNAL_CATALOG_KEY:}}
 * binds blank), the catalog key is disabled: no header value, including an empty one, opens the catalog prefix.
 *
 * <p>Runs on the shared default context (no extra properties). {@code GET /internal/catalog/categories} is served since
 * B504, so a request the filter LETS THROUGH ends in 200 and one it rejects ends in 401: the assertion is exactly "401,
 * never past the filter".
 */
@DisplayName("InternalApiKeyFilter (IT) — blank catalog key is disabled on the real filter chain")
class InternalCatalogKeyBlankIT extends AbstractIntegrationTest {

    private static final String CATALOG_URL = "/api/v1/internal/catalog/categories";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InternalApiKeyProperties properties;

    private ResponseEntity<String> get(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", key);
        return restTemplate.exchange(CATALOG_URL, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("precondition — the test profile binds the catalog key blank")
    void should_bindBlankCatalogKey_when_envVarUnset() {
        assertThat(properties.getInternalCatalogKey()).isBlank();
    }

    @Test
    @DisplayName("blank catalog key + empty X-Internal-Key header on the catalog prefix → 401, not let through")
    void should_return401_when_catalogKeyBlankAndHeaderEmpty() {
        ResponseEntity<String> response = get("");

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("blank catalog key + whitespace header on the catalog prefix → 401")
    void should_return401_when_catalogKeyBlankAndHeaderWhitespace() {
        ResponseEntity<String> response = get(" ");

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("control — master key on the same catalog path passes the filter and reaches the handler (200, not 401)")
    void should_passFilter_when_masterKeyOnCatalogPath() {
        ResponseEntity<String> response = get("test-internal-api-key");

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
    }
}
