package com.beautica.booking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 436 audit-fix (security L1) — the guest cancel body cap, incl. a chunked body with no Content-Length. */
@DisplayName("GuestCancelBodyLimitFilter — body cap on POST /api/v1/book/cancel/*")
class GuestCancelBodyLimitFilterTest {

    private final GuestCancelBodyLimitFilter filter = new GuestCancelBodyLimitFilter(new ObjectMapper());

    @Test
    @DisplayName("bounds a chunked (no Content-Length) body: reading past 4 KB throws, so nothing is parsed")
    void should_throwOnRead_when_chunkedBodyExceedsCap() throws Exception {
        MockHttpServletRequest request = chunked(new byte[GuestCancelBodyLimitFilter.MAX_BODY_BYTES + 1]);
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set((HttpServletRequest) req);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThatThrownBy(() -> seen.get().getInputStream().readAllBytes())
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("passes a chunked body at the cap through unchanged")
    void should_readBody_when_chunkedBodyAtCap() throws Exception {
        byte[] body = "{\"bookingIds\":[]}".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> read = new AtomicReference<>();
        FilterChain chain = (req, res) -> read.set(req.getInputStream().readAllBytes());

        filter.doFilter(chunked(body), new MockHttpServletResponse(), chain);

        assertThat(read.get()).isEqualTo(body);
    }

    @Test
    @DisplayName("413 without calling the chain when the declared Content-Length is over the cap")
    void should_return413_when_contentLengthOverCap() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/book/cancel/abc");
        request.setContent(new byte[GuestCancelBodyLimitFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"success\":false").contains("Request body too large");
        assertThat(called.get()).isFalse();
    }

    @Test
    @DisplayName("413 for a percent-encoded spelling of the route (canc%65l) — matched on the decoded path Spring routes")
    void should_return413_when_routePercentEncoded() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/book/canc%65l/abc");
        request.setContent(new byte[GuestCancelBodyLimitFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(called.get()).isFalse();
    }

    @Test
    @DisplayName("leaves every other route alone, even with a large body")
    void should_notFilter_when_otherRoute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/book/some-slug/booking");
        request.setContent(new byte[GuestCancelBodyLimitFilter.MAX_BODY_BYTES * 4]);
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));

        assertThat(seen.get()).isSameAs(request);
    }

    /** A request whose body has no declared length — what a chunked upload looks like to the servlet. */
    private static MockHttpServletRequest chunked(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/book/cancel/abc") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(body);
        return request;
    }
}
