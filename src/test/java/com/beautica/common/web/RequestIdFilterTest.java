package com.beautica.common.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RequestIdFilter — Phase 371 D5")
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static MockHttpServletRequest request(String inboundId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/salons");
        if (inboundId != null) {
            request.addHeader(RequestIdFilter.HEADER, inboundId);
        }
        return request;
    }

    @Test
    @DisplayName("no inbound header — a UUID is generated, set on the response and MDC")
    void should_generateUuid_when_headerAbsent() throws Exception {
        MockHttpServletRequest request = request(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcInChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        String header = response.getHeader(RequestIdFilter.HEADER);
        assertThat(UUID.fromString(header)).isNotNull();
        assertThat(mdcInChain.get()).isEqualTo(header);
    }

    @Test
    @DisplayName("valid inbound header — echoed unchanged")
    void should_echoInboundId_when_headerValid() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInChain = new AtomicReference<>();

        filter.doFilter(request("mobile-1a2B.c_d-9"), response,
                (req, res) -> mdcInChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("mobile-1a2B.c_d-9");
        assertThat(mdcInChain.get()).isEqualTo("mobile-1a2B.c_d-9");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a\r\nX-Evil: 1",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", // 65 chars
            "short",
            "has space 12345",
            "abcdefgh\n",
            ""})
    @DisplayName("invalid inbound header — replaced by a generated UUID, never echoed")
    void should_replaceInboundId_when_headerInvalid(String inbound) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(inbound), response, (req, res) -> { });

        String header = response.getHeader(RequestIdFilter.HEADER);
        assertThat(header).isNotEqualTo(inbound);
        assertThat(UUID.fromString(header)).isNotNull();
    }

    @Test
    @DisplayName("8-char inbound header — accepted (lower bound inclusive)")
    void should_acceptId_when_exactly8Chars() throws Exception {
        String id = "abcd1234";
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(id), response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
    }

    @Test
    @DisplayName("7-char inbound header — rejected one below the lower bound, UUID generated instead")
    void should_replaceId_when_exactly7Chars() throws Exception {
        String id = "abcd123";
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(id), response, (req, res) -> { });

        String header = response.getHeader(RequestIdFilter.HEADER);
        assertThat(header).isNotEqualTo(id);
        assertThat(UUID.fromString(header)).isNotNull();
    }

    @Test
    @DisplayName("64-char inbound header — accepted (upper bound inclusive)")
    void should_acceptId_when_exactly64Chars() throws Exception {
        String id = "a".repeat(64);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(id), response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
    }

    @Test
    @DisplayName("MDC is empty after the chain")
    void should_clearMdc_when_chainCompletes() throws Exception {
        filter.doFilter(request(null), new MockHttpServletResponse(), (req, res) -> { });

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("MDC is empty after the chain even when the chain throws")
    void should_clearMdc_when_chainThrows() {
        FilterChain throwing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request(null), new MockHttpServletResponse(), throwing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
