package com.beautica.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import java.util.List;

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
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !isInfoRequest(request);
            }
        };
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
}
