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
}
