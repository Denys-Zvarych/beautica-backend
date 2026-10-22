package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 380 audit #5 — the ETag filter must only ever touch the two public {@code /info} reads. The servlet
 * url-pattern syntax cannot express a mid-path wildcard, so the registration covers the {@code /api/v1/book/*}
 * prefix and the filter narrows itself; this pins both halves.
 */
@DisplayName("BookingInfoEtagFilterConfig — ETag filter scope")
class BookingInfoEtagFilterConfigTest {

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("should_registerOnlyUnderBookPrefix_when_configured")
    void should_registerOnlyUnderBookPrefix_when_configured() {
        var registration = new BookingInfoEtagFilterConfig().bookingInfoEtagFilter();

        assertThat(registration.getUrlPatterns()).containsExactlyInAnyOrder("/api/v1/book/*", "/api/v1/book/s/*");
    }

    @Test
    @DisplayName("should_filterOnlyGetInfoRoutes_when_pathMatches")
    void should_filterOnlyGetInfoRoutes_when_pathMatches() {
        assertThat(BookingInfoEtagFilterConfig.isInfoRequest(request("GET", "/api/v1/book/olena-k-ab12/info"))).isTrue();
        assertThat(BookingInfoEtagFilterConfig.isInfoRequest(request("GET", "/api/v1/book/s/studio-a1b2/info"))).isTrue();
    }

    @Test
    @DisplayName("should_notFilter_when_otherBookRoutesOrMethods")
    void should_notFilter_when_otherBookRoutesOrMethods() {
        assertThat(BookingInfoEtagFilterConfig.isInfoRequest(request("POST", "/api/v1/book/olena-k-ab12/info"))).isFalse();
        assertThat(BookingInfoEtagFilterConfig.isInfoRequest(request("GET", "/api/v1/book/olena-k-ab12/availability"))).isFalse();
        assertThat(BookingInfoEtagFilterConfig.isInfoRequest(request("POST", "/api/v1/book/olena-k-ab12/booking"))).isFalse();
        assertThat(BookingInfoEtagFilterConfig.isInfoRequest(request("GET", "/api/v1/book/cancel/6f1c0c3e-0000-0000-0000-000000000000"))).isFalse();
        assertThat(BookingInfoEtagFilterConfig.isInfoRequest(request("POST", "/api/v1/book/otp/send"))).isFalse();
        assertThat(BookingInfoEtagFilterConfig.isInfoRequest(request("GET", "/api/v1/book/a/b/info"))).isFalse();
        assertThat(BookingInfoEtagFilterConfig.isInfoRequest(request("GET", "/api/v1/masters/x/info"))).isFalse();
    }
}
