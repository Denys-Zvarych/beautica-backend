package com.beautica.notification;

import com.beautica.service.service.AdminRequestsPortalLink;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.NOTIFICATION_ONLY_FOOTER;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.PORTAL_BUTTON;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.RETIRED_LINK_MARKERS;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.portalHref;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.suggestionSubject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B545 — the operator's "new service-type suggestion" e-mail is notification-only: the suggestion facts and one
 * «Відкрити заявки» button to {@code {FRONTEND_BASE_URL}/admin/requests}; no token, no review / approve / reject link.
 * Renders the production {@code email/service-type-suggestion} template through a real Thymeleaf engine.
 */
@DisplayName("Service-type suggestion operator e-mail — notification-only (B545)")
class ServiceTypeSuggestionNotificationEmailTest {

    private static final String FRONTEND_BASE_URL = "https://staging.beautica.test";

    @Test
    @DisplayName("links to {FRONTEND_BASE_URL}/admin/requests; carries no token, /review, /approve or /reject")
    void should_linkOnlyToPortal_when_suggestionEmailRendered() throws Exception {
        String portalUrl = new AdminRequestsPortalLink(FRONTEND_BASE_URL).requestsUrl();
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        new EmailService(mailSender, CategoryRequestNotificationEmailTest.realEngine(), "noreply@beautica.app")
                .sendServiceTypeSuggestionNotification(
                        "admin@beautica.app", "user-545", "HAIRDRESSING", "Гарячий рушник", "опис", portalUrl);

        String html = message.getContent().toString();
        assertThat(message.getSubject()).isEqualTo(suggestionSubject("Гарячий рушник"));
        assertThat(html).contains(portalHref(FRONTEND_BASE_URL), PORTAL_BUTTON, NOTIFICATION_ONLY_FOOTER,
                "HAIRDRESSING", "Гарячий рушник", "опис", "user-545");
        assertThat(html).doesNotContain(RETIRED_LINK_MARKERS.toArray(String[]::new));
    }
}
