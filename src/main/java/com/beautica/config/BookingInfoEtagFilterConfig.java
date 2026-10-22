package com.beautica.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 380 D5 — weak {@code ETag} + {@code If-None-Match} → {@code 304} for exactly the two public
 * booking-page routes {@code GET /api/v1/book/{slug}/info} and {@code GET /api/v1/book/s/{slug}/info},
 * via Spring's {@link ShallowEtagHeaderFilter} (one mechanism, no hand-rolled hashing). The servlet
 * url-pattern syntax cannot express a mid-path wildcard, so the filter is registered on the
 * {@code /api/v1/book/*} prefix and narrows itself to the two Ant patterns below; every other request
 * passes through untouched.
 *
 * <p>The filter only ETags 2xx responses, so 404s keep Spring Security's default {@code no-store}.
 */
@Configuration
public class BookingInfoEtagFilterConfig {

    static final List<String> INFO_PATTERNS = List.of(
            "/api/v1/book/{slug}/info",
            "/api/v1/book/s/{slug}/info");

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> bookingInfoEtagFilter() {
        ShallowEtagHeaderFilter filter = new WildcardAwareEtagFilter();
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/v1/book/*", "/api/v1/book/s/*");
        registration.setName("bookingInfoEtagFilter");
        return registration;
    }

    static boolean isInfoRequest(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return INFO_PATTERNS.stream().anyMatch(pattern -> MATCHER.match(pattern, path));
    }

    /**
     * {@link ShallowEtagHeaderFilter} that also answers {@code If-None-Match: *} (RFC 9110 §13.1.2: {@code *} is
     * satisfied by any current representation) with a 304 that carries the SAME weak ETag a plain 200 would.
     *
     * <p>The stock filter only compares concrete validators, and the ETag is only known after the body has been
     * rendered. So for a wildcard request it hands the stock logic a request whose {@code If-None-Match} reads, on
     * demand, as the ETag the filter has just generated for this very response — the stock comparison then matches
     * and emits the 304 (ETag and {@code Cache-Control} headers included) exactly as for an explicit validator.
     * Non-2xx responses never generate an ETag, so a 404 stays a 404.
     */
    static final class WildcardAwareEtagFilter extends ShallowEtagHeaderFilter {

        private static final String WILDCARD = "*";
        private final ThreadLocal<AtomicReference<String>> generated = new ThreadLocal<>();

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !isInfoRequest(request);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
            if (ifNoneMatch == null || !WILDCARD.equals(ifNoneMatch.strip())) {
                super.doFilterInternal(request, response, chain);
                return;
            }
            AtomicReference<String> etag = new AtomicReference<>();
            generated.set(etag);
            try {
                super.doFilterInternal(new GeneratedEtagRequest(request, etag), response, chain);
            } finally {
                generated.remove();
            }
        }

        @Override
        protected String generateETagHeaderValue(InputStream inputStream, boolean isWeak) throws IOException {
            String value = super.generateETagHeaderValue(inputStream, isWeak);
            AtomicReference<String> holder = generated.get();
            if (holder != null) {
                holder.set(value);
            }
            return value;
        }
    }

    /** Presents {@code If-None-Match} as the ETag generated for this response, once it exists. */
    private static final class GeneratedEtagRequest extends HttpServletRequestWrapper {

        private final AtomicReference<String> etag;

        GeneratedEtagRequest(HttpServletRequest request, AtomicReference<String> etag) {
            super(request);
            this.etag = etag;
        }

        @Override
        public String getHeader(String name) {
            return HttpHeaders.IF_NONE_MATCH.equalsIgnoreCase(name) ? etag.get() : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (!HttpHeaders.IF_NONE_MATCH.equalsIgnoreCase(name)) {
                return super.getHeaders(name);
            }
            String value = etag.get();
            return value == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(value));
        }
    }
}
