package com.beautica.config;

import com.beautica.auth.AccessTokenDenylist;
import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.auth.filter.AuthRateLimitFilter;
import com.beautica.service.controller.InternalCategoryController;
import com.beautica.service.dto.PlatformCategoryUsageResponse;
import com.beautica.service.service.InternalCategoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link InternalApiKeyFilter}.
 *
 * <p>Verifies the three gate-keeping scenarios:
 * <ul>
 *   <li>Missing header → 401</li>
 *   <li>Wrong header value → 401</li>
 *   <li>Correct header value → passes through to the downstream controller (200)</li>
 * </ul>
 */
@WebMvcTest(InternalCategoryController.class)
@TestPropertySource(properties = {
        "app.frontend.base-url=http://localhost:3000",
        "app.internal-api-key=test-internal-api-key",
        "spring.main.allow-bean-definition-overriding=true"
})
@Import(InternalApiKeyFilterTest.TestConfig.class)
@DisplayName("InternalApiKeyFilter — @WebMvcTest slice")
class InternalApiKeyFilterTest {

    private static final String VALID_KEY = "test-internal-api-key";
    private static final String WRONG_KEY  = "wrong-key";

    // ── Infrastructure ─────────────────────────────────────────────────────────

    @TestConfiguration
    static class TestConfig {

        /**
         * Minimal security chain: disable CSRF, stateless sessions, permit all.
         * The filter under test handles auth for /internal/** — Spring Security must
         * not reject these as 401 before the filter runs.
         */
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter,
                InternalApiKeyFilter internalApiKeyFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterBefore(internalApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }

        /**
         * Pass-through JwtAuthenticationFilter — tests bypass JWT validation;
         * auth goes via the InternalApiKeyFilter instead.
         */
        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                                        AccessTokenDenylist accessTokenDenylist,
                                                        TokensValidAfterCache tokensValidAfterCache) {
            return new JwtAuthenticationFilter(jwtTokenProvider, accessTokenDenylist, tokensValidAfterCache) {
                @Override
                protected void doFilterInternal(HttpServletRequest req,
                                                HttpServletResponse res,
                                                FilterChain chain)
                        throws jakarta.servlet.ServletException, IOException {
                    chain.doFilter(req, res);
                }
            };
        }

        /**
         * Pass-through AuthRateLimitFilter.
         */
        @Bean
        @SuppressWarnings("unchecked")
        AuthRateLimitFilter authRateLimitFilter() {
            LoadingCache<String, Bucket> dummy = Mockito.mock(LoadingCache.class);
            return new AuthRateLimitFilter(
                    dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy,
                    dummy, dummy, dummy) {
                @Override
                protected void doFilterInternal(HttpServletRequest req,
                                                HttpServletResponse res,
                                                FilterChain chain)
                        throws jakarta.servlet.ServletException, IOException {
                    chain.doFilter(req, res);
                }

                @Override
                public boolean shouldNotFilter(HttpServletRequest request) {
                    return true;
                }
            };
        }

        @Bean
        InternalApiKeyProperties internalApiKeyProperties() {
            InternalApiKeyProperties props = new InternalApiKeyProperties();
            props.setInternalApiKey(VALID_KEY);
            return props;
        }

        @Bean
        InternalApiKeyFilter internalApiKeyFilter(InternalApiKeyProperties props,
                                                  ObjectMapper objectMapper) {
            return new InternalApiKeyFilter(props, objectMapper);
        }
    }

    // ── Slice infrastructure ───────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InternalCategoryService internalCategoryService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private AccessTokenDenylist accessTokenDenylist;

    @MockBean
    private TokensValidAfterCache tokensValidAfterCache;

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns 401 when X-Internal-Key header is missing")
    void should_return401_when_headerMissing() throws Exception {
        mockMvc.perform(get("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Unauthorized"));
    }

    @Test
    @DisplayName("returns 401 when X-Internal-Key header has the wrong value")
    void should_return401_when_headerValueIsWrong() throws Exception {
        mockMvc.perform(get("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .header("X-Internal-Key", WRONG_KEY)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Unauthorized"));
    }

    @Test
    @DisplayName("passes through to downstream controller when X-Internal-Key is correct")
    void should_return200_when_headerValueIsCorrect() throws Exception {
        when(internalCategoryService.listWithUsageCounts())
                .thenReturn(List.of(new PlatformCategoryUsageResponse("MANICURE", true, 5L)));

        mockMvc.perform(get("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .header("X-Internal-Key", VALID_KEY)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("MANICURE"));
    }
}
