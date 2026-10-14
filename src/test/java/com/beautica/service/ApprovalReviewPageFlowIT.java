package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.ServiceTypeSuggestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Railway staging bug: the admin clicked Approve on the review page and got "Invalid CORS request". The page's
 * forms post to a relative path, so the browser sends {@code Origin: <public base URL>} (https), while behind
 * the TLS proxy the app sees http and treats the POST as cross-origin. This walks the whole admin journey on
 * the real context: e-mailed link -> GET review page (headers + relative form action) -> POST decision with
 * the browser's Origin -> persisted state change. MockMvc serves {@code http://localhost}, which reproduces
 * the proxy's scheme mismatch exactly.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Admin review page — e-mailed link to persisted decision with the browser's Origin header")
class ApprovalReviewPageFlowIT extends AbstractIntegrationTest {

    private static final String BASE = "https://api.beautica.test";
    private static final String CATEGORY_NAME = "ITFLOW_CAT";
    private static final String CATEGORY_APPROVE = "/api/v1/service-categories/requests/approve";
    private static final String SUGGESTION_REJECT = "/api/v1/service-types/suggestions/reject";

    @Autowired private WebApplicationContext wac;
    @Autowired private CategoryRequestService categoryRequestService;
    @Autowired private ServiceTypeSuggestionService suggestionService;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanRows() {
        // platform_categories / service_type_suggestion are not in cleanDb(); remove only this test's rows.
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE category_name = ?", CATEGORY_NAME);
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", CATEGORY_NAME);
    }

    @Test
    @DisplayName("category request: GET page sends Referrer-Policy same-origin + relative form; POST approve with Origin=public base URL approves")
    void should_approveCategory_when_adminPostsFromReviewPageWithPublicOrigin() throws Exception {
        String link = submitCategoryRequestAndCaptureLink();
        String token = link.substring(link.lastIndexOf('/') + 1);

        MockHttpServletResponse page = mockMvc.perform(get(link.substring(BASE.length()))).andReturn().getResponse();

        assertReviewPage(page, CATEGORY_APPROVE, token);

        MockHttpServletResponse decision = mockMvc.perform(post(CATEGORY_APPROVE)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("Origin", BASE)
                        .param("token", token))
                .andReturn().getResponse();

        assertThat(decision.getStatus()).as("Invalid CORS request is a 403; body=%s",
                decision.getContentAsString()).isEqualTo(200);
        assertThat(decision.getHeader("Access-Control-Allow-Origin")).isEqualTo(BASE);
        assertThat(decision.getContentAsString()).contains("Категорію підтверджено");
        assertThat(categoryRow()).containsEntry("status", "APPROVED").containsEntry("active", true);
    }

    @Test
    @DisplayName("service-type suggestion: GET page sends Referrer-Policy same-origin + relative form; POST reject with Origin=public base URL rejects")
    void should_rejectSuggestion_when_adminPostsFromReviewPageWithPublicOrigin() throws Exception {
        UUID requester = createRequester();
        suggestionService.submitSuggestionInternal(CATEGORY_NAME, "Flow Type", "desc", requester);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), anyString(), anyString(), anyString(), anyString(), url.capture());
        String link = url.getValue();
        assertThat(link).startsWith(BASE + "/api/v1/service-types/suggestions/review?token=");
        String token = link.substring(link.indexOf("token=") + "token=".length());

        MockHttpServletResponse page = mockMvc.perform(get(link.substring(BASE.length()))).andReturn().getResponse();

        assertReviewPage(page, SUGGESTION_REJECT, token);

        MockHttpServletResponse decision = mockMvc.perform(post(SUGGESTION_REJECT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("Origin", BASE)
                        .param("token", token))
                .andReturn().getResponse();

        assertThat(decision.getStatus()).as("body=%s", decision.getContentAsString()).isEqualTo(200);
        assertThat(decision.getHeader("Access-Control-Allow-Origin")).isEqualTo(BASE);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM service_type_suggestion WHERE category_name = ?", String.class, CATEGORY_NAME))
                .isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("POST approve with a foreign Origin — 403 and the request stays PENDING (no state change)")
    void should_return403AndKeepPending_when_approvePostedFromForeignOrigin() throws Exception {
        String link = submitCategoryRequestAndCaptureLink();
        String token = link.substring(link.lastIndexOf('/') + 1);

        MockHttpServletResponse decision = mockMvc.perform(post(CATEGORY_APPROVE)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("Origin", "https://evil.example")
                        .param("token", token))
                .andReturn().getResponse();

        assertThat(decision.getStatus()).isEqualTo(403);
        assertThat(categoryRow()).containsEntry("status", "PENDING").containsEntry("active", false);
    }

    private String submitCategoryRequestAndCaptureLink() throws Exception {
        UUID requester = createRequester();
        categoryRequestService.submitRequest(new CreateCategoryRequestRequest(CATEGORY_NAME, "Флоу-тест", null), requester);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), anyString(), anyString(), anyString(), url.capture());
        assertThat(url.getValue()).startsWith(BASE + "/api/v1/service-categories/requests/review/");
        return url.getValue();
    }

    /**
     * same-origin (not no-referrer) is what makes the browser send the real Origin on the form POST — a
     * no-referrer page sends {@code Origin: null}, which the scoped CORS entry rejects. The form action must
     * be relative so the POST goes to the same public origin the page was served from.
     */
    private static void assertReviewPage(MockHttpServletResponse page, String expectedAction, String token)
            throws Exception {
        String html = page.getContentAsString();
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getHeader("Referrer-Policy")).isEqualTo("same-origin");
        assertThat(page.getHeader("Cache-Control")).contains("no-store");
        assertThat(html).contains("action=\"" + expectedAction + "\"");
        assertThat(html).contains("value=\"" + token + "\"");
        assertThat(html).doesNotContain("localhost");
    }

    private Map<String, Object> categoryRow() {
        return jdbcTemplate.queryForMap(
                "SELECT status, active FROM platform_categories WHERE name = ?", CATEGORY_NAME);
    }

    /** A real requester row: both tables FK requested_by_user_id to users. */
    private UUID createRequester() throws Exception {
        return new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder)
                .createSalonOwnerWithSalonAndGetUserId("it-flow-" + System.nanoTime() + "@beautica.test");
    }
}
