package com.beautica.service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AdminRequestsPortalLink — portal requests URL from FRONTEND_BASE_URL (B545)")
class AdminRequestsPortalLinkTest {

    @Test
    @DisplayName("https origin → {origin}/admin/requests")
    void should_appendRequestsPath_when_originIsHttps() {
        AdminRequestsPortalLink link = new AdminRequestsPortalLink("https://beautica.app");

        String url = link.requestsUrl();

        assertThat(url).isEqualTo("https://beautica.app/admin/requests");
    }

    @Test
    @DisplayName("origin with one or more trailing slashes → no double slash before /admin/requests")
    void should_normalizeTrailingSlashes_when_originEndsWithSlash() {
        AdminRequestsPortalLink single = new AdminRequestsPortalLink("https://beautica.app/");
        AdminRequestsPortalLink doubleSlash = new AdminRequestsPortalLink("https://beautica.app//");

        String singleUrl = single.requestsUrl();
        String doubleUrl = doubleSlash.requestsUrl();

        assertThat(singleUrl).isEqualTo("https://beautica.app/admin/requests");
        assertThat(doubleUrl).isEqualTo("https://beautica.app/admin/requests");
    }

    @Test
    @DisplayName("local dev origin http://localhost:3000 is allowed")
    void should_allowLocalhost_when_devOrigin() {
        AdminRequestsPortalLink link = new AdminRequestsPortalLink("http://localhost:3000");

        String url = link.requestsUrl();

        assertThat(url).isEqualTo("http://localhost:3000/admin/requests");
    }

    @Test
    @DisplayName("plain-http or scheme-less origin fails fast at construction")
    void should_throw_when_originIsNotHttpsOrLocalhost() {
        assertThatThrownBy(() -> new AdminRequestsPortalLink("http://beautica.app"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AdminRequestsPortalLink("javascript:alert(1)"))
                .isInstanceOf(IllegalStateException.class);
    }
}
