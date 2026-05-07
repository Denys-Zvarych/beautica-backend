package com.beautica.service.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.auth.filter.AuthRateLimitFilter;
import com.beautica.service.dto.CatalogCategoryResponse;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.service.ServiceCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link ServiceCatalogController}.
 *
 * <p>Migrated from {@code @SpringBootTest(RANDOM_PORT)} + Testcontainers.
 * Catalog data (categories and service types) is stubbed via {@code @MockBean ServiceCatalogService}.
 * No real database is required.
 */
@WebMvcTest(ServiceCatalogController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@DisplayName("ServiceCatalogController — @WebMvcTest slice")
class ServiceCatalogControllerTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceCatalogControllerTest.class);

    // ── Pass-through filter overrides ─────────────────────────────────────────

    @TestConfiguration
    @EnableMethodSecurity
    static class PassThroughFilters {

        @Bean
        @Primary
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
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

        @Bean
        @Primary
        AuthRateLimitFilter authRateLimitFilter() {
            return new AuthRateLimitFilter(null, null) {
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

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/service-categories").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/service-types").permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    // ── Slice infrastructure ──────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ServiceCatalogService serviceCatalogService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private final UUID nailsCategoryId = UUID.randomUUID();
    private final UUID browsCategoryId = UUID.randomUUID();
    private final UUID gelPolishTypeId = UUID.randomUUID();
    private final UUID browShapingTypeId = UUID.randomUUID();

    private List<CatalogCategoryResponse> stubCategories() {
        return List.of(
                new CatalogCategoryResponse(nailsCategoryId, "Нігті", "Nails", 1),
                new CatalogCategoryResponse(browsCategoryId, "Брови", "Brows", 2)
        );
    }

    private List<ServiceTypeResponse> stubServiceTypes() {
        return List.of(
                new ServiceTypeResponse(gelPolishTypeId, nailsCategoryId, "Гель-лак", "Gel Polish", "gel-polish"),
                new ServiceTypeResponse(browShapingTypeId, browsCategoryId, "Корекція брів", "Brow Shaping", "brow-shaping")
        );
    }

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    // ── GET /api/v1/service-categories ────────────────────────────────────────

    @Test
    @DisplayName("GET /service-categories — 200 with list, no auth required")
    void should_return200_when_getCategoriesWithoutAuth() throws Exception {
        when(serviceCatalogService.getCategories()).thenReturn(stubCategories());
        log.debug("Act: GET /api/v1/service-categories — public endpoint, no auth");

        mockMvc.perform(get("/api/v1/service-categories")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("GET /service-categories — returns categories ordered by sortOrder")
    void should_returnCategoriesOrderedBySortOrder_when_getCategoriesCalled() throws Exception {
        when(serviceCatalogService.getCategories()).thenReturn(stubCategories());
        log.debug("Act: GET /api/v1/service-categories — verify sort order");

        mockMvc.perform(get("/api/v1/service-categories")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sortOrder").value(1))
                .andExpect(jsonPath("$.data[1].sortOrder").value(2));
    }

    // ── GET /api/v1/service-types ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /service-types — 200 with all active types, no auth required")
    void should_return200WithAllTypes_when_noParamsProvided() throws Exception {
        when(serviceCatalogService.searchServiceTypes(isNull(), isNull())).thenReturn(stubServiceTypes());
        log.debug("Act: GET /api/v1/service-types — no params, public endpoint");

        mockMvc.perform(get("/api/v1/service-types")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("GET /service-types?categoryId=... — returns only types for that category")
    void should_returnOnlyTypesForCategory_when_categoryIdParamProvided() throws Exception {
        var nailsOnly = List.of(
                new ServiceTypeResponse(gelPolishTypeId, nailsCategoryId, "Гель-лак", "Gel Polish", "gel-polish"));
        when(serviceCatalogService.searchServiceTypes(eq(nailsCategoryId), isNull())).thenReturn(nailsOnly);
        log.debug("Act: GET /api/v1/service-types?categoryId={}", nailsCategoryId);

        mockMvc.perform(get("/api/v1/service-types")
                        .param("categoryId", nailsCategoryId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].categoryId").value(nailsCategoryId.toString()))
                .andExpect(jsonPath("$.data[0].nameUk").value("Гель-лак"));
    }

    @Test
    @DisplayName("GET /service-types?q=М — falls back to all active types when q < 2 chars")
    void should_returnAllTypes_when_qParamHasFewerThanTwoChars() throws Exception {
        // Single-char q is forwarded to the service; the service itself decides to return all types
        when(serviceCatalogService.searchServiceTypes(isNull(), eq("М"))).thenReturn(stubServiceTypes());
        log.debug("Act: GET /api/v1/service-types?q=М — single char query falls back to all-active");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", "М")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("GET /service-types?q=<101-char string> — 400 when q exceeds @Size(max=100)")
    void should_return400_when_qParamExceeds100Chars() throws Exception {
        String longQ = "a".repeat(101);
        log.debug("Act: GET /api/v1/service-types?q=<101 chars> — must return 400");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", longQ)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/v1/service-types/suggest ────────────────────────────────────

    @Test
    @DisplayName("POST /service-types/suggest — 202 when SALON_OWNER submits a valid suggestion")
    void should_return202_when_salonOwnerSuggestsServiceType() throws Exception {
        var userId = UUID.randomUUID();
        doNothing().when(serviceCatalogService).suggestServiceType(any(SuggestServiceTypeRequest.class), eq(userId));

        var request = new SuggestServiceTypeRequest("Ламінування вій", nailsCategoryId, "Опис процедури");
        log.debug("Act: POST /api/v1/service-types/suggest as SALON_OWNER");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        verify(serviceCatalogService).suggestServiceType(any(SuggestServiceTypeRequest.class), eq(userId));
    }

    @Test
    @DisplayName("POST /service-types/suggest — 403 when CLIENT submits a suggestion")
    void should_return403_when_clientSuggestsServiceType() throws Exception {
        var userId = UUID.randomUUID();
        var request = new SuggestServiceTypeRequest("Sneaky Suggest", nailsCategoryId, null);
        log.debug("Act: POST /api/v1/service-types/suggest as CLIENT — must be denied");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when name is blank")
    void should_return400_when_nameIsBlank() throws Exception {
        var userId = UUID.randomUUID();
        String invalidBody = "{\"name\":\"\",\"categoryId\":\"" + nailsCategoryId + "\"}";
        log.debug("Act: POST /api/v1/service-types/suggest with blank name — must return 400");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when categoryId is missing")
    void should_return400_when_categoryIdIsNull() throws Exception {
        var userId = UUID.randomUUID();
        String invalidBody = "{\"name\":\"Нова послуга\"}";
        log.debug("Act: POST /api/v1/service-types/suggest with missing categoryId — must return 400");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 401 when no authentication provided")
    void should_return401_when_noAuthProvided() throws Exception {
        var request = new SuggestServiceTypeRequest("Ламінування вій", nailsCategoryId, null);
        log.debug("Act: POST /api/v1/service-types/suggest without auth — must return 401");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 202 when SALON_ADMIN submits a valid suggestion")
    void should_return202_when_salonAdminSuggestsServiceType() throws Exception {
        var userId = UUID.randomUUID();
        doNothing().when(serviceCatalogService).suggestServiceType(any(), any());

        var request = new SuggestServiceTypeRequest("Ботокс для брів", nailsCategoryId, "Опис");
        log.debug("Act: POST /api/v1/service-types/suggest as SALON_ADMIN");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 202 when INDEPENDENT_MASTER submits a valid suggestion")
    void should_return202_when_independentMasterSuggestsServiceType() throws Exception {
        var userId = UUID.randomUUID();
        doNothing().when(serviceCatalogService).suggestServiceType(any(), any());

        var request = new SuggestServiceTypeRequest("Нарощення нігтів", nailsCategoryId, null);
        log.debug("Act: POST /api/v1/service-types/suggest as INDEPENDENT_MASTER");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 403 when SALON_MASTER submits a suggestion")
    void should_return403_when_salonMasterSuggestsServiceType() throws Exception {
        var userId = UUID.randomUUID();
        var request = new SuggestServiceTypeRequest("Корекція форми брів", browsCategoryId, null);
        log.debug("Act: POST /api/v1/service-types/suggest as SALON_MASTER");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "smaster@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when name exceeds 255 characters")
    void should_return400_when_nameExceeds255Chars() throws Exception {
        var userId = UUID.randomUUID();
        String longName = "a".repeat(256);
        String invalidBody = "{\"name\":\"" + longName + "\",\"categoryId\":\"" + nailsCategoryId + "\"}";
        log.debug("Act: POST /api/v1/service-types/suggest with 256-char name — must return 400");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when description exceeds 1000 characters")
    void should_return400_when_descriptionExceeds1000Chars() throws Exception {
        var userId = UUID.randomUUID();
        String longDescription = "a".repeat(1001);
        String invalidBody = "{\"name\":\"Нова послуга\",\"categoryId\":\"" + nailsCategoryId
                + "\",\"description\":\"" + longDescription + "\"}";
        log.debug("Act: POST /api/v1/service-types/suggest with 1001-char description — must return 400");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /service-types/suggest — response body has success=true and message contains 'Suggestion'")
    void should_return202Body_withSuccessTrue_when_suggestCalled() throws Exception {
        var userId = UUID.randomUUID();
        doNothing().when(serviceCatalogService).suggestServiceType(any(), any());

        var request = new SuggestServiceTypeRequest("Ламінування брів", browsCategoryId, "Деталі");
        log.debug("Act: POST /api/v1/service-types/suggest — assert response body structure");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Suggestion submitted"));
    }

    @Test
    @DisplayName("GET /service-types?q=Гель — returns non-empty list with nameUk containing 'Гель'")
    void should_returnMatchingTypesWithGel_when_qParamIsGel() throws Exception {
        var gelType = new ServiceTypeResponse(gelPolishTypeId, nailsCategoryId, "Гель-лак", "Gel Polish", "gel-polish");
        when(serviceCatalogService.searchServiceTypes(isNull(), eq("Гель"))).thenReturn(List.of(gelType));
        log.debug("Act: GET /api/v1/service-types?q=Гель — strengthened assertion");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", "Гель")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].nameUk").value("Гель-лак"));
    }
}
