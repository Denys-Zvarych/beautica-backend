package com.beautica.notification;

import com.beautica.service.service.AdminRequestsPortalLink;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Properties;

import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.NOTIFICATION_ONLY_FOOTER;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.PORTAL_BUTTON;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.RETIRED_LINK_MARKERS;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.categoryRequestSubject;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.portalHref;
import static com.beautica.notification.CatalogRequestOperatorEmailExpectedCopy.portalUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B545 — the operator's "new category request" e-mail is notification-only: the request facts and one «Відкрити
 * заявки» button to {@code {FRONTEND_BASE_URL}/admin/requests}; no token, no review / approve / reject link. Renders
 * the production {@code email/category-request} template through a real Thymeleaf engine; only SMTP is mocked.
 */
@DisplayName("Category request operator e-mail — notification-only (B545)")
class CategoryRequestNotificationEmailTest {

    private static final String FRONTEND_BASE_URL = "https://beautica.app";

    @Test
    @DisplayName("links to {FRONTEND_BASE_URL}/admin/requests; carries no token, /review, /approve or /reject")
    void should_linkOnlyToPortal_when_categoryRequestEmailRendered() throws Exception {
        String portalUrl = new AdminRequestsPortalLink(FRONTEND_BASE_URL).requestsUrl();
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        new EmailService(mailSender, realEngine(), "noreply@beautica.app").sendCategoryRequestNotification(
                "admin@beautica.app", "user-545", "NAIL_ART", "Нейл-арт", portalUrl);

        String html = message.getContent().toString();
        assertThat(portalUrl).isEqualTo(portalUrl(FRONTEND_BASE_URL));
        assertThat(message.getSubject()).isEqualTo(categoryRequestSubject("NAIL_ART"));
        assertThat(html).contains(portalHref(FRONTEND_BASE_URL), PORTAL_BUTTON, NOTIFICATION_ONLY_FOOTER,
                "NAIL_ART", "Нейл-арт", "user-545");
        assertThat(html).doesNotContain(RETIRED_LINK_MARKERS.toArray(String[]::new));
    }

    static SpringTemplateEngine realEngine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
