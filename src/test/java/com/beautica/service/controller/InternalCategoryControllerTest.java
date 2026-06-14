package com.beautica.service.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.filter.AuthRateLimitFilter;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.InternalApiKeyFilter;
import com.beautica.config.InternalApiKeyProperties;
import com.beautica.service.dto.CreatePlatformCategoryRequest;
import com.beautica.service.dto.PlatformCategoryResponse;
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
import org.springframework.http.HttpStatus;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link InternalCategoryController}.
 *
 * <p>Tests both the filter (key gate) and the controller behaviour for each
 * endpoint/scenario.
 */
@WebMvcTest(InternalCategoryController.class)
@TestPropertySource(properties = {
        "app.frontend.base-url=http://localhost:3000",
        "app.internal-api-key=test-internal-api-key",
        "spring.main.allow-bean-definition-overriding=true"
})
@Import(InternalCategoryControllerTest.TestConfig.class)
@DisplayName("InternalCategoryController — @WebMvcTest slice")
class InternalCategoryControllerTest {

    private static final String VALID_KEY = "test-internal-api-key";

    // ── Infrastructure ─────────────────────────────────────────────────────────

    @TestConfiguration
    static class TestConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter,
                InternalApiKeyFilter internalApiKeyFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/api/v1/internal/**").permitAll()
                            .anyRequest().authenticated())
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterBefore(internalApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
            return new JwtAuthenticationFilter(jwtTokenProvider) {
                @Override
                protected void doFilterInternal(HttpServletRequest req,
                                                HttpServletResponse res,
                                                FilterChain chain)
                        throws jakarta.servlet.ServletException, IOException {
                    chain.doFilter(req, res);
                }
            };
        }

        @Bean
        @SuppressWarnings("unchecked")
        AuthRateLimitFilter authRateLimitFilter() {
            LoadingCache<String, Bucket> dummy = Mockito.mock(LoadingCache.class);
            return new AuthRateLimitFilter(
                    dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy, dummy) {
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

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InternalCategoryService internalCategoryService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── GET /api/v1/internal/service-categories ────────────────────────────────

    @Test
    @DisplayName("GET with valid key returns 200 with list including seeded categories")
    void should_return200WithList_when_validKeyAndGetRequested() throws Exception {
        when(internalCategoryService.listWithUsageCounts()).thenReturn(List.of(
                new PlatformCategoryUsageResponse("MANICURE", true, 12L),
                new PlatformCategoryUsageResponse("PEDICURE", true, 3L),
                new PlatformCategoryUsageResponse("NAIL_ART",  false, 0L)
        ));

        mockMvc.perform(get("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .header("X-Internal-Key", VALID_KEY)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].name").value("MANICURE"))
                .andExpect(jsonPath("$.data[0].usageCount").value(12))
                .andExpect(jsonPath("$.data[2].active").value(false));
    }

    @Test
    @DisplayName("GET without key returns 401")
    void should_return401_when_getWithoutKey() throws Exception {
        mockMvc.perform(get("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/internal/service-categories ───────────────────────────────

    @Test
    @DisplayName("POST with valid key and valid name returns 201")
    void should_return201_when_validKeyAndValidNamePosted() throws Exception {
        var created = new PlatformCategoryResponse(8L, "NAIL_ART", true);
        when(internalCategoryService.create(any(CreatePlatformCategoryRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .header("X-Internal-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NAIL_ART\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("NAIL_ART"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    @DisplayName("POST with valid key and duplicate name returns 409")
    void should_return409_when_validKeyAndDuplicateName() throws Exception {
        when(internalCategoryService.create(any(CreatePlatformCategoryRequest.class)))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "Category already exists: MANICURE"));

        mockMvc.perform(post("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .header("X-Internal-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"MANICURE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST with valid key but invalid name format returns 400")
    void should_return400_when_validKeyButInvalidNameFormat() throws Exception {
        // "nail art" contains a space — violates ^[A-Z][A-Z0-9_]*$ pattern
        mockMvc.perform(post("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .header("X-Internal-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"nail art\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST with valid key but lowercase name returns 400")
    void should_return400_when_validKeyButLowercaseName() throws Exception {
        mockMvc.perform(post("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .header("X-Internal-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"manicure\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST without key returns 401")
    void should_return401_when_postWithoutKey() throws Exception {
        mockMvc.perform(post("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NAIL_ART\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST with valid key but blank name returns 400")
    void should_return400_when_blankName() throws Exception {
        mockMvc.perform(post("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .header("X-Internal-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST with wrong key returns 401")
    void should_return401_when_wrongKey() throws Exception {
        mockMvc.perform(post("/api/v1/internal/service-categories")
                        .servletPath("/api/v1/internal/service-categories")
                        .header("X-Internal-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NAIL_ART\"}"))
                .andExpect(status().isUnauthorized());
    }
}
