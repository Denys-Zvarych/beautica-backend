package com.beautica.auth.filter;

import com.beautica.config.ClientIpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClientIpResolver — the one per-IP rule shared by the rate-limit buckets, login lockout, Turnstile and Telegram")
class ClientIpResolverTest {

    private static final String REMOTE_ADDR = "10.1.2.3";

    private static final ClientIpResolver TRUSTING = new ClientIpResolver(new ClientIpProperties(true));
    private static final ClientIpResolver UNTRUSTING = new ClientIpResolver(new ClientIpProperties(false));

    private static MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(REMOTE_ADDR);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    @DisplayName("should_useRightmostForwardedEntry_when_headerHasSeveral")
    void should_useRightmostForwardedEntry_when_headerHasSeveral() {
        assertThat(TRUSTING.resolve(request("6.6.6.6, 203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("should_useRightmostEntryAcrossLines_when_headerSentAsSeveralLines")
    void should_useRightmostEntryAcrossLines_when_headerSentAsSeveralLines() {
        MockHttpServletRequest twoLines = request("6.6.6.6");
        twoLines.addHeader("X-Forwarded-For", "203.0.113.7");

        String resolved = TRUSTING.resolve(twoLines);

        assertThat(resolved).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("should_skipEmptyTrailingEntries_when_headerEndsWithComma")
    void should_skipEmptyTrailingEntries_when_headerEndsWithComma() {
        assertThat(TRUSTING.resolve(request("203.0.113.7, "))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("should_fallBackToRemoteAddr_when_noHeaderBlankHeaderOrOversizedEntry")
    void should_fallBackToRemoteAddr_when_noHeaderBlankHeaderOrOversizedEntry() {
        assertThat(TRUSTING.resolve(request(null))).isEqualTo(REMOTE_ADDR);
        assertThat(TRUSTING.resolve(request("   "))).isEqualTo(REMOTE_ADDR);
        assertThat(TRUSTING.resolve(request(" , , "))).isEqualTo(REMOTE_ADDR);
        assertThat(TRUSTING.resolve(request("a".repeat(46)))).isEqualTo(REMOTE_ADDR);
    }

    @Test
    @DisplayName("should_useRemoteAddr_when_rightmostXffInvalid")
    void should_useRemoteAddr_when_rightmostXffInvalid() {
        String[] invalidRightmost = {"not-an-ip", "example.com", "1.2.3", "999.1.1.1", "spoofed",
                "2001:0db8:0000:0000:0000:0000:0000:0000:0001:2"};

        for (String rightmost : invalidRightmost) {
            String resolved = TRUSTING.resolve(request("203.0.113.7, " + rightmost));

            assertThat(resolved).as("rightmost <%s>", rightmost).isEqualTo(REMOTE_ADDR);
        }
    }

    @Test
    @DisplayName("should_ignoreSpoofedXRealIp_when_xffPresent")
    void should_ignoreSpoofedXRealIp_when_xffPresent() {
        MockHttpServletRequest spoofed = request("198.51.100.1, 203.0.113.7");
        spoofed.addHeader("X-Real-IP", "192.0.2.99");

        String resolved = TRUSTING.resolve(spoofed);

        assertThat(resolved).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("should_ignoreXRealIp_when_xffAbsent")
    void should_ignoreXRealIp_when_xffAbsent() {
        MockHttpServletRequest spoofed = request(null);
        spoofed.addHeader("X-Real-IP", "192.0.2.99");

        String resolved = TRUSTING.resolve(spoofed);

        assertThat(resolved).isEqualTo(REMOTE_ADDR);
    }

    @Test
    @DisplayName("should_canonicalizeIpv6_when_differentNotation")
    void should_canonicalizeIpv6_when_differentNotation() {
        String[] notations = {"2001:db8::7", "2001:DB8::7", "2001:0db8:0000:0000:0000:0000:0000:0007",
                " 2001:db8:0:0:0:0:0:7 "};

        for (String notation : notations) {
            String resolved = TRUSTING.resolve(request("6.6.6.6, " + notation));

            assertThat(resolved).as("notation <%s>", notation).isEqualTo("2001:db8:0:0:0:0:0:7");
        }
    }

    @Test
    @DisplayName("should_ignoreLeftXff_when_rotated")
    void should_ignoreLeftXff_when_rotated() {
        String[] rotatedLeft = {"1.1.1.1", "2.2.2.2, 3.3.3.3", "spoofed"};

        for (String left : rotatedLeft) {
            String resolved = TRUSTING.resolve(request(left + ", 203.0.113.7"));

            assertThat(resolved).as("left entries <%s>", left).isEqualTo("203.0.113.7");
        }
    }

    @Test
    @DisplayName("should_useSocketAddressOnly_when_forwardedHeadersNotTrusted")
    void should_useSocketAddressOnly_when_forwardedHeadersNotTrusted() {
        MockHttpServletRequest spoofed = request("6.6.6.6, 203.0.113.7");
        spoofed.addHeader("X-Real-IP", "198.51.100.9");

        String resolved = UNTRUSTING.resolve(spoofed);

        assertThat(resolved).isEqualTo(REMOTE_ADDR);
    }
}
