package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.EmailService;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.ServiceTypeSuggestionService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin approval e-mail links are built from the bound {@code app.public-base-url}, not a
 * hard-coded or localhost default. Real context, real property binding; only the mail sender is mocked.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Approval e-mail links use the configured app.public-base-url")
class ApprovalLinkBaseUrlIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "https://api.beautica.test";
    private static final String CATEGORY_NAME = "ITLINKBASE_CAT";

    @Autowired private CategoryRequestService categoryRequestService;
    @Autowired private ServiceTypeSuggestionService suggestionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate restTemplate;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired private SpringTemplateEngine templateEngine;

    /** A real requester row: both tables FK requested_by_user_id to users. */
    private UUID createRequester() throws Exception {
        return new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder)
                .createSalonOwnerWithSalonAndGetUserId("it-link-" + System.nanoTime() + "@beautica.test");
    }

    @AfterEach
    void cleanRows() {
        // platform_categories / service_type_suggestion are not in cleanDb(); remove only what this test created.
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE category_name = ?", CATEGORY_NAME);
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", CATEGORY_NAME);
    }

    @Test
    @DisplayName("category request link starts with the configured base URL, no double slash")
    void should_buildCategoryRequestLink_fromConfiguredPublicBaseUrl() throws Exception {
        UUID requester = createRequester();

        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, "Лінк-тест", null), requester);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), eq(requester.toString()), eq(CATEGORY_NAME), eq("Лінк-тест"), url.capture());
        assertLink(url.getValue());
    }

    @Test
    @DisplayName("service-type suggestion link starts with the configured base URL, no double slash")
    void should_buildSuggestionLink_fromConfiguredPublicBaseUrl() throws Exception {
        UUID requester = createRequester();

        suggestionService.submitSuggestionInternal(CATEGORY_NAME, "Link Type", "desc", requester);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), eq(requester.toString()), eq(CATEGORY_NAME), eq("Link Type"), eq("desc"), url.capture());
        assertLink(url.getValue());
    }

    @Test
    @DisplayName("rendered category-request e-mail body carries the configured base URL as the review link href")
    void should_renderCategoryEmailBody_withConfiguredPublicBaseUrl() throws Exception {
        UUID requester = createRequester();
        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, "Лінк-тест", null), requester);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), anyString(), anyString(), anyString(), url.capture());

        String body = renderBody(real -> real.sendCategoryRequestNotification(
                "admin@beautica.test", requester.toString(), CATEGORY_NAME, "Лінк-тест", url.getValue()));

        assertThat(body).contains("href=\"" + url.getValue() + "\"");
        assertThat(body).contains("href=\"" + BASE + "/api/v1/service-categories/requests/review/");
        assertThat(body).doesNotContain("localhost");
    }

    @Test
    @DisplayName("rendered service-type-suggestion e-mail body carries the configured base URL as the review link href")
    void should_renderSuggestionEmailBody_withConfiguredPublicBaseUrl() throws Exception {
        UUID requester = createRequester();
        suggestionService.submitSuggestionInternal(CATEGORY_NAME, "Link Type", "desc", requester);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), anyString(), anyString(), anyString(), anyString(), url.capture());

        String body = renderBody(real -> real.sendServiceTypeSuggestionNotification(
                "admin@beautica.test", requester.toString(), CATEGORY_NAME, "Link Type", "desc", url.getValue()));

        assertThat(body).contains("href=\"" + url.getValue() + "\"");
        assertThat(body).contains("href=\"" + BASE + "/api/v1/service-types/suggestions/review?token=");
        assertThat(body).doesNotContain("localhost");
    }

    /**
     * Renders through a real {@link EmailService} built on the context's real Thymeleaf engine (constructed
     * directly, so {@code @Async} does not apply and the call is synchronous); only SMTP is stubbed.
     */
    private String renderBody(Consumer<EmailService> send) throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        send.accept(new EmailService(sender, templateEngine, "noreply@beautica.test"));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        return (String) sent.getValue().getContent();
    }

    private static void assertLink(String link) {
        assertThat(link).startsWith(BASE + "/api/v1/");
        assertThat(link.substring("https://".length())).doesNotContain("//");
        assertThat(link).doesNotContain("localhost");
    }
}
