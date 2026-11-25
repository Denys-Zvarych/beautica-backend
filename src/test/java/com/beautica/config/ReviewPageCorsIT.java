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
 * Behind Railway's TLS proxy the app sees plain http while a server-rendered page's relative-path form post carries
 * {@code Origin: https://<api host>}, so it looks cross-origin. Only the moderation-action confirm page still posts
 * like that and keeps a scoped CORS entry for the public base URL. B545 retired the admin review pages and removed
 * their scoped entry: the four retired review POSTs (410 «use the portal» page) fall under the default {@code /**}
 * policy (website origin only). MockMvc is built from the shared (cached) context so no extra Spring context is created.
 */
@DisplayName("Server-rendered page POSTs: CORS scoped to the public base URL only where a page still posts")
class ReviewPageCorsIT extends AbstractIntegrationTest {

    private static final String PUBLIC_BASE = "https://api.beautica.test";
    private static final String FRONTEND_BASE = "http://localhost:3000";
    private static final String TOKEN = "A".repeat(43);
    private static final String MODERATION_CONFIRM = "/api/v1/moderation/actions/confirm";

    @Autowired private WebApplicationContext wac;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private MockHttpServletRequestBuilder form(String path) {
        return post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", TOKEN);
    }

    private MvcResult postWithOrigin(String path, String origin) throws Exception {
        return mockMvc.perform(form(path).header("Origin", origin)).andReturn();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/service-categories/requests/approve",
            "/api/v1/service-categories/requests/reject",
            "/api/v1/service-types/suggestions/approve",
            "/api/v1/service-types/suggestions/reject"})
    @DisplayName("B545: the retired review POSTs lost their scoped entry — the public base URL origin is rejected")
    void should_return403_when_retiredReviewPostHasPublicBaseUrlOrigin(String path) throws Exception {
        MockHttpServletResponse response = postWithOrigin(path, PUBLIC_BASE).getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/service-categories/requests/approve",
            "/api/v1/service-categories/requests/reject",
            "/api/v1/service-types/suggestions/approve",
            "/api/v1/service-types/suggestions/reject"})
    @DisplayName("frontend origin is admitted by the default policy and gets the 410 portal page")
    void should_allowOrigin_when_originIsFrontendBaseUrl(String path) throws Exception {
        MockHttpServletResponse response = postWithOrigin(path, FRONTEND_BASE).getResponse();

        assertThat(response.getStatus()).as("retired link renders the 410 portal page (B545)").isEqualTo(410);
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
    @DisplayName("Origin: null (what a no-referrer page's form POST sends) is rejected")
    void should_return403_when_originIsNull(String path) throws Exception {
        MockHttpServletResponse response = postWithOrigin(path, "null").getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    @DisplayName("phase 414: the moderation-action confirm POST admits the public origin only; null / foreign rejected")
    void should_scopeCors_when_moderationActionConfirm() throws Exception {
        String confirm = MODERATION_CONFIRM;

        MockHttpServletResponse publicOrigin = postWithOrigin(confirm, PUBLIC_BASE).getResponse();
        MockHttpServletResponse nullOrigin = postWithOrigin(confirm, "null").getResponse();
        MockHttpServletResponse foreign = postWithOrigin(confirm, "https://evil.example").getResponse();

        assertThat(publicOrigin.getStatus()).as("no nonce → the friendly INVALID page, not a CORS 403").isEqualTo(410);
        assertThat(publicOrigin.getHeader("Access-Control-Allow-Origin")).isEqualTo(PUBLIC_BASE);
        assertThat(nullOrigin.getStatus()).isEqualTo(403);
        assertThat(foreign.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("audit-fix: the moderation-action confirm POST has its own CORS entry — the website origin is rejected")
    void should_rejectFrontendOrigin_when_moderationActionConfirm() throws Exception {
        MockHttpServletResponse response = postWithOrigin(MODERATION_CONFIRM, FRONTEND_BASE)
                .getResponse();

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

        assertThat(status).as("retired link renders the 410 portal page (B545)").isEqualTo(410);
    }

    @Test
    @DisplayName("public host over the wrong scheme (http) is rejected on the moderation confirm POST")
    void should_return403_when_originHasWrongScheme() throws Exception {
        MockHttpServletResponse response = postWithOrigin(MODERATION_CONFIRM, "http://api.beautica.test").getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("PUT preflight on the moderation confirm POST is rejected (only POST is allowed)")
    void should_rejectPreflight_when_requestedMethodIsPut() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(options(MODERATION_CONFIRM)
                        .header("Origin", PUBLIC_BASE).header("Access-Control-Request-Method", "PUT"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
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
