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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.UUID;
import java.util.function.Consumer;

import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.NOTIFICATION_ONLY_FOOTER;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.PORTAL_BUTTON;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.RETIRED_LINK_MARKERS;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.portalUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B545 (SVC-06) — the operator's request e-mails are notification-only: the one link is the portal's requests page,
 * built from the bound {@code app.frontend.base-url} ({@code FRONTEND_BASE_URL}), and the rendered body carries no
 * token and no review / approve / reject link. Real context, real property binding; only SMTP is stubbed.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Request e-mails link to the portal built from app.frontend.base-url, never a token link")
class RequestEmailPortalLinkIT extends AbstractIntegrationTest {

    private static final String CATEGORY_NAME = "ITLINKBASE_CAT";

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @Autowired private CategoryRequestService categoryRequestService;
    @Autowired private ServiceTypeSuggestionService suggestionService;
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
    @DisplayName("category request e-mail: the link is {FRONTEND_BASE_URL}/admin/requests; body has no token link")
    void should_linkCategoryEmailToPortal_when_requestSubmitted() throws Exception {
        UUID requester = createRequester();

        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, "Лінк-тест", null), requester);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), eq(requester.toString()), eq(CATEGORY_NAME), eq("Лінк-тест"), url.capture());
        assertThat(url.getValue()).isEqualTo(portalUrl(frontendBaseUrl));
        String body = renderBody(real -> real.sendCategoryRequestNotification(
                "admin@beautica.test", requester.toString(), CATEGORY_NAME, "Лінк-тест", url.getValue()));
        assertNotificationOnly(body, url.getValue());
    }

    @Test
    @DisplayName("service-type suggestion e-mail: the link is {FRONTEND_BASE_URL}/admin/requests;"
            + " body has no token link")
    void should_linkSuggestionEmailToPortal_when_suggestionSubmitted() throws Exception {
        UUID requester = createRequester();

        suggestionService.submitSuggestionInternal(CATEGORY_NAME, "Link Type", "desc", requester);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), eq(requester.toString()), eq(CATEGORY_NAME), eq("Link Type"), eq("desc"), url.capture());
        assertThat(url.getValue()).isEqualTo(portalUrl(frontendBaseUrl));
        String body = renderBody(real -> real.sendServiceTypeSuggestionNotification(
                "admin@beautica.test", requester.toString(), CATEGORY_NAME, "Link Type", "desc", url.getValue()));
        assertNotificationOnly(body, url.getValue());
    }

    private static void assertNotificationOnly(String body, String portalUrl) {
        assertThat(body).contains("href=\"" + portalUrl + "\"", PORTAL_BUTTON, NOTIFICATION_ONLY_FOOTER);
        assertThat(body).doesNotContain(RETIRED_LINK_MARKERS.toArray(String[]::new));
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
}
