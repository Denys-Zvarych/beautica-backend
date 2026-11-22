package com.beautica.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InternalApiKeyFilter — fails closed on a blank configured key")
class InternalApiKeyFilterBlankKeyTest {

    private static MockHttpServletRequest internalRequest(String header) {
        var req = new MockHttpServletRequest("GET", "/api/v1/internal/service-categories");
        req.setServletPath("/api/v1/internal/service-categories");
        if (header != null) {
            req.addHeader("X-Internal-Key", header);
        }
        return req;
    }

    private static InternalApiKeyFilter filterWithKey(String configured) {
        var props = new InternalApiKeyProperties();
        props.setInternalApiKey(configured);
        return new InternalApiKeyFilter(props, new ObjectMapper());
    }

    @Test
    @DisplayName("should_return401_when_configuredKeyBlankAndHeaderEmpty")
    void should_return401_when_configuredKeyBlankAndHeaderEmpty() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filterWithKey("").doFilter(internalRequest(""), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_return401_when_configuredKeyNull")
    void should_return401_when_configuredKeyNull() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filterWithKey(null).doFilter(internalRequest("anything"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_passThrough_when_headerMatchesConfiguredKey")
    void should_passThrough_when_headerMatchesConfiguredKey() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filterWithKey("k".repeat(40)).doFilter(internalRequest("k".repeat(40)), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    // ── B501: catalog-scoped key ───────────────────────────────────────────────

    private static final String MASTER_KEY = "m".repeat(40);

    private static MockHttpServletRequest catalogRequest(String path, String header) {
        var req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        req.addHeader("X-Internal-Key", header);
        return req;
    }

    private static InternalApiKeyFilter filterWithCatalogKey(String catalogKey) {
        var props = new InternalApiKeyProperties();
        props.setInternalApiKey(MASTER_KEY);
        props.setInternalCatalogKey(catalogKey);
        return new InternalApiKeyFilter(props, new ObjectMapper());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "   "})
    @DisplayName("should_return401_when_catalogKeyBlankAndHeaderEqualsIt")
    void should_return401_when_catalogKeyBlankAndHeaderEqualsIt(String blank) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filterWithCatalogKey(blank).doFilter(catalogRequest("/api/v1/internal/catalog/x", blank), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_return401_when_catalogKeyUnset")
    void should_return401_when_catalogKeyUnset() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filterWithCatalogKey(null).doFilter(catalogRequest("/api/v1/internal/catalog/x", "anything"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            // `..` segment: walks out of the prefix — only the `/..` guard rejects it (no backslash here).
            "/api/v1/internal/catalog/../moderation/reports",
            // backslash traversal with no `/..`: only the backslash guard rejects it.
            "/api/v1/internal/catalog/x\\..\\..\\moderation/reports",
            // bare prefix, no trailing slash: phase B501 scope opens only paths starting `/api/v1/internal/catalog/`.
            "/api/v1/internal/catalog",
            "/api/v1/internal/catalogx/y",
            // case-sensitive prefix match.
            "/api/v1/internal/Catalog/y"})
    @DisplayName("should_return401_when_catalogKeyOutsideTheCatalogPrefix")
    void should_return401_when_catalogKeyOutsideTheCatalogPrefix(String path) throws Exception {
        String catalogKey = "c".repeat(40);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filterWithCatalogKey(catalogKey).doFilter(catalogRequest(path, catalogKey), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    // ── B501 audit-fix: keys are stripped once at binding, as EnvironmentFingerprintGuard validates them ───────────

    @Test
    @DisplayName("should_stripBothKeys_when_bound")
    void should_stripBothKeys_when_bound() {
        var props = new InternalApiKeyProperties();

        props.setInternalApiKey("  " + MASTER_KEY + "\n");
        props.setInternalCatalogKey("\t" + "c".repeat(40) + " ");

        assertThat(props.getInternalApiKey()).isEqualTo(MASTER_KEY);
        assertThat(props.getInternalCatalogKey()).isEqualTo("c".repeat(40));
    }

    @Test
    @DisplayName("should_passThrough_when_configuredCatalogKeyHasSurroundingWhitespace")
    void should_passThrough_when_configuredCatalogKeyHasSurroundingWhitespace() throws Exception {
        String catalogKey = "c".repeat(40);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filterWithCatalogKey(catalogKey + "\n")
                .doFilter(catalogRequest("/api/v1/internal/catalog/x", catalogKey), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("should_passThrough_when_configuredMasterKeyHasSurroundingWhitespace")
    void should_passThrough_when_configuredMasterKeyHasSurroundingWhitespace() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filterWithKey(" " + "k".repeat(40) + "\r\n").doFilter(internalRequest("k".repeat(40)), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("should_return401_when_configuredMasterKeyIsWhitespaceOnly")
    void should_return401_when_configuredMasterKeyIsWhitespaceOnly() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filterWithKey(" \n").doFilter(internalRequest(""), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }
}
