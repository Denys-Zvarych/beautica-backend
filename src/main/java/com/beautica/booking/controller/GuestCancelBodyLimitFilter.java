package com.beautica.booking.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.web.RequestMatchPath;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Phase 436 audit-fix (security L1) — caps the body of the public {@code POST /api/v1/book/cancel/{token}} at
 * {@value #MAX_BODY_BYTES} bytes BEFORE Jackson parses it, so a multi-MB {@code bookingIds} array is never built
 * into a list ({@code @Size(max = 10)} runs only after parsing). Scoped to that one route; every other request passes
 * through untouched.
 *
 * <ul>
 *   <li>A declared {@code Content-Length} above the cap → 413 with the standard {@link ApiResponse} error body, the
 *       chain (and the body) never touched.</li>
 *   <li>No / understated {@code Content-Length} (chunked): the body stream is bounded — reading past the cap throws
 *       an {@link IOException}, which Spring maps to {@code HttpMessageNotReadableException} → the generic 400.</li>
 * </ul>
 *
 * <p>A real request is ~400 bytes (10 UUIDs). Ordered right after {@code RequestIdFilter} so the 413 carries the
 * request id like every other error body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
@RequiredArgsConstructor
public class GuestCancelBodyLimitFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 4 * 1024;
    static final String CANCEL_PATTERN = "/api/v1/book/cancel/*";
    static final String TOO_LARGE_MESSAGE = "Request body too large";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        // Decoded + normalized, as Spring MVC routes it: `canc%65l` must not slip past the cap.
        return !MATCHER.match(CANCEL_PATTERN, RequestMatchPath.of(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(TOO_LARGE_MESSAGE));
            return;
        }
        chain.doFilter(new BoundedBodyRequest(request), response);
    }

    /** The request with its body stream bounded to {@link #MAX_BODY_BYTES}. */
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        private BoundedBodyRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new BoundedInputStream(super.getInputStream());
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8.name() : encoding));
        }
    }

    /** Throws once more than {@link #MAX_BODY_BYTES} bytes have been read. */
    private static final class BoundedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long read;

        private BoundedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > MAX_BODY_BYTES) {
                throw new IOException(TOO_LARGE_MESSAGE);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
