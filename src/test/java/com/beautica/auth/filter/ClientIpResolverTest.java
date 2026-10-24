package com.beautica.auth.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClientIpResolver — the one per-IP rule shared by the rate-limit buckets and Turnstile (phase 382 D5)")
class ClientIpResolverTest {

    private static MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    @DisplayName("should_useRightmostForwardedEntry_when_headerHasSeveral")
    void should_useRightmostForwardedEntry_when_headerHasSeveral() {
        assertThat(ClientIpResolver.resolve(request("6.6.6.6, 203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("should_skipEmptyTrailingEntries_when_headerEndsWithComma")
    void should_skipEmptyTrailingEntries_when_headerEndsWithComma() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.7, "))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("should_fallBackToRemoteAddr_when_noHeaderBlankHeaderOrOversizedEntry")
    void should_fallBackToRemoteAddr_when_noHeaderBlankHeaderOrOversizedEntry() {
        assertThat(ClientIpResolver.resolve(request(null))).isEqualTo("10.1.2.3");
        assertThat(ClientIpResolver.resolve(request("   "))).isEqualTo("10.1.2.3");
        assertThat(ClientIpResolver.resolve(request("a".repeat(46)))).isEqualTo("10.1.2.3");
    }
}
