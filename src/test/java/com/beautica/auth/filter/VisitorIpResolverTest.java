package com.beautica.auth.filter;

import com.beautica.config.BookingInfoWorkerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 382 audit #10 — Worker-forwarded visitor IP, same trust rule as the guest-info rate limit. */
@DisplayName("VisitorIpResolver — CF-Connecting-IP only behind a valid Worker key")
class VisitorIpResolverTest {

    private static final String KEY = "k".repeat(40);

    private static VisitorIpResolver resolver(String key) {
        BookingInfoWorkerProperties props = new BookingInfoWorkerProperties();
        props.setWorkerKey(key);
        return new VisitorIpResolver(props);
    }

    private static MockHttpServletRequest request(String workerKey, String cfIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.16.0.9");
        request.addHeader("X-Forwarded-For", "198.51.100.20");
        if (workerKey != null) {
            request.addHeader("X-Beautica-Worker-Key", workerKey);
        }
        if (cfIp != null) {
            request.addHeader("CF-Connecting-IP", cfIp);
        }
        return request;
    }

    @Test
    @DisplayName("should_useForwardedVisitorIp_when_workerKeyMatches")
    void should_useForwardedVisitorIp_when_workerKeyMatches() {
        assertThat(resolver(KEY).resolve(request(KEY, "203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(resolver(KEY).resolve(request(KEY, " 2001:db8::1 "))).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("should_fallBackToClientIp_when_keyWrongMissingOrFeatureOff")
    void should_fallBackToClientIp_when_keyWrongMissingOrFeatureOff() {
        assertThat(resolver(KEY).resolve(request("wrong-key-wrong-key-wrong-key-wrong-key", "203.0.113.7")))
                .isEqualTo("198.51.100.20");
        assertThat(resolver(KEY).resolve(request(null, "203.0.113.7"))).isEqualTo("198.51.100.20");
        assertThat(resolver("").resolve(request(KEY, "203.0.113.7"))).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("should_fallBackToClientIp_when_forwardedValueIsNotAnIpLiteral")
    void should_fallBackToClientIp_when_forwardedValueIsNotAnIpLiteral() {
        assertThat(resolver(KEY).resolve(request(KEY, "not-an-ip"))).isEqualTo("198.51.100.20");
        assertThat(resolver(KEY).resolve(request(KEY, "evil.example.com"))).isEqualTo("198.51.100.20");
        assertThat(resolver(KEY).resolve(request(KEY, null))).isEqualTo("198.51.100.20");
    }
}
