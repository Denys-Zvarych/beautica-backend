package com.beautica.config;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Behind Railway's TLS proxy the app sees plain http while the review pages' relative-path form posts
 * carry {@code Origin: https://<api host>}, so they look cross-origin. A scoped CORS entry for exactly the
 * four review POSTs must admit the public base URL and nothing else. MockMvc is built from the shared
 * (cached) context so no extra Spring context is created.
 */
@DisplayName("Admin review-page POSTs pass CORS for the public base URL only")
class ReviewPageCorsIT extends AbstractIntegrationTest {

    private static final String PUBLIC_BASE = "https://api.beautica.test";
    private static final String FRONTEND_BASE = "http://localhost:3000";
    private static final String TOKEN = "A".repeat(43);

    @Autowired private WebApplicationContext wac;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private MockHttpServletRequestBuilder form(String path) {
        return post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", TOKEN);
    }

    private static final String APPROVE_CAT = "/api/v1/service-categories/requests/approve";

    private MvcResult postWithOrigin(String path, String origin) throws Exception {
        return mockMvc.perform(form(path).header("Origin", origin)).andReturn();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/service-categories/requests/approve",
            "/api/v1/service-categories/requests/reject",
            "/api/v1/service-types/suggestions/approve",
            "/api/v1/service-types/suggestions/reject"})
    @DisplayName("public base URL origin is admitted and echoed back")
    void should_allowOrigin_when_originIsPublicBaseUrl(String path) throws Exception {
        MockHttpServletResponse response = postWithOrigin(path, PUBLIC_BASE).getResponse();

        assertThat(response.getStatus()).as("unknown token renders the neutral 200 result page").isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(PUBLIC_BASE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/service-categories/requests/approve",
            "/api/v1/service-categories/requests/reject",
            "/api/v1/service-types/suggestions/approve",
            "/api/v1/service-types/suggestions/reject"})
    @DisplayName("frontend origin is admitted and echoed back")
    void should_allowOrigin_when_originIsFrontendBaseUrl(String path) throws Exception {
        MockHttpServletResponse response = postWithOrigin(path, FRONTEND_BASE).getResponse();

        assertThat(response.getStatus()).as("unknown token renders the neutral 200 result page").isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(FRONTEND_BASE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/service-categories/requests/approve",
            "/api/v1/service-categories/requests/reject",
            "/api/v1/service-types/suggestions/approve",
            "/api/v1/service-types/suggestions/reject"})
    @DisplayName("foreign origin is rejected with 403")
    void should_return403_when_originIsForeign(String path) throws Exception {
        MockHttpServletResponse response = postWithOrigin(path, "https://evil.example").getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/service-categories/requests/approve",
            "/api/v1/service-categories/requests/reject",
            "/api/v1/service-types/suggestions/approve",
            "/api/v1/service-types/suggestions/reject"})
    @DisplayName("Origin: null (what a no-referrer page's form POST sends) is rejected - why the pages use same-origin")
    void should_return403_when_originIsNull(String path) throws Exception {
        MockHttpServletResponse response = postWithOrigin(path, "null").getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    @DisplayName("public host over the wrong scheme (http) is rejected")
    void should_return403_when_originHasWrongScheme() throws Exception {
        MockHttpServletResponse response = postWithOrigin(APPROVE_CAT, "http://api.beautica.test").getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("trailing-slash path does not inherit the scoped entry: public origin is not served 2xx")
    void should_notSucceed_when_pathHasTrailingSlash() throws Exception {
        MockHttpServletResponse response = postWithOrigin(APPROVE_CAT + "/", PUBLIC_BASE).getResponse();

        assertThat(response.getStatus() / 100).isNotEqualTo(2);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    @DisplayName("PUT preflight on a review POST path is rejected (only POST is allowed)")
    void should_rejectPreflight_when_requestedMethodIsPut() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(options(APPROVE_CAT)
                        .header("Origin", PUBLIC_BASE).header("Access-Control-Request-Method", "PUT"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/service-categories/requests/approve",
            "/api/v1/service-categories/requests/reject",
            "/api/v1/service-types/suggestions/approve",
            "/api/v1/service-types/suggestions/reject"})
    @DisplayName("no Origin header is a normal (non-CORS) request")
    void should_notReturn403_when_noOrigin(String path) throws Exception {
        int status = mockMvc.perform(form(path)).andReturn().getResponse().getStatus();

        assertThat(status).as("unknown token renders the neutral 200 result page").isEqualTo(200);
    }

    @Test
    @DisplayName("scope stays narrow: POST /auth/login with the public base URL origin is still rejected")
    void should_return403_when_loginPostedWithPublicBaseUrlOrigin() throws Exception {
        int status = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@beautica.test\",\"password\":\"x\"}").header("Origin", PUBLIC_BASE))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }
}
