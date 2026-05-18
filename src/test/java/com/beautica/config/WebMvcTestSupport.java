package com.beautica.config;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.filter.AuthRateLimitFilter;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Shared {@code @TestConfiguration} for all {@code @WebMvcTest} slices.
 *
 * <p>Replaces the two {@code @Component} filters that Spring Boot auto-registers
 * with stateless pass-through implementations. Without this override, Mockito
 * mocks of these filters swallow the request and return an empty 200, preventing
 * the DispatcherServlet from ever being reached.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @WebMvcTest(MyController.class)
 * @Import(WebMvcTestSupport.class)
 * class MyControllerTest { ... }
 * }</pre>
 *
 * <p>Tests that need public (unauthenticated) endpoints or method-security
 * ({@code @PreAuthorize}) must additionally declare their own
 * {@code @TestConfiguration}-inner class with a {@code SecurityFilterChain} bean
 * and annotate that inner class with {@code @EnableMethodSecurity}.
 *
 * <h2>Authentication in tests</h2>
 * Inject auth directly via
 * {@code SecurityMockMvcRequestPostProcessors.authentication()} — do not use
 * {@code @WithMockUser}, which never populates the {@code details} field read by
 * controllers via {@code authentication.getDetails()}.
 */
@TestConfiguration
public class WebMvcTestSupport {

    /**
     * Pass-through {@link JwtAuthenticationFilter}: never parses a real JWT.
     * Tests inject authentication directly via the {@code authentication()}
     * post-processor, so no JWT validation is needed in the filter chain.
     */
    @Bean
    @Primary
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        return new JwtAuthenticationFilter(jwtTokenProvider) {
            @Override
            protected void doFilterInternal(HttpServletRequest req,
                                            HttpServletResponse res,
                                            FilterChain chain)
                    throws ServletException, IOException {
                chain.doFilter(req, res);
            }
        };
    }

    /**
     * Pass-through {@link AuthRateLimitFilter}: rate limiting is not under test
     * in a controller slice. {@link #shouldNotFilter} returns {@code true} so
     * the filter is skipped entirely by the servlet container.
     */
    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public AuthRateLimitFilter authRateLimitFilter() {
        LoadingCache<String, Bucket> dummy = Mockito.mock(LoadingCache.class);
        return new AuthRateLimitFilter(dummy, dummy, dummy, dummy, dummy, dummy) {
            @Override
            protected void doFilterInternal(HttpServletRequest req,
                                            HttpServletResponse res,
                                            FilterChain chain)
                    throws ServletException, IOException {
                chain.doFilter(req, res);
            }

            @Override
            public boolean shouldNotFilter(HttpServletRequest request) {
                return true;
            }
        };
    }
}
