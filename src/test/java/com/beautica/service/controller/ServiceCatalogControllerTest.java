package com.beautica.service.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.service.dto.CatalogCategoryResponse;
import com.beautica.service.dto.PlatformServiceTypeResponse;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.service.ServiceCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import static org.mockito.Mockito.never;
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
@Import(WebMvcTestSupport.class)
@DisplayName("ServiceCatalogController — @WebMvcTest slice")
class ServiceCatalogControllerTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceCatalogControllerTest.class);

    // ── Security configuration ────────────────────────────────────────────────

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {

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
    // System-B category-name slugs for POST /service-types/suggest (the service is
    // mocked in this @WebMvcTest, so any non-blank slug satisfies @NotBlank/@Pattern).
    private static final String NAILS_CATEGORY_NAME = "NAIL_SERVICE";
    private static final String BROWS_CATEGORY_NAME = "BROWS";
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
    @DisplayName("q param shorter than 3 chars returns 400 validation error")
    void should_return400_when_qParamHasFewerThanThreeChars() throws Exception {
        log.debug("Act: GET /api/v1/service-types?q=М — single char query must return 400");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", "М")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
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

    @Test
    @DisplayName("GET /service-types?q=Гель&categoryId=... — 200 and non-empty content when both params provided")
    void should_return200_when_bothQAndCategoryIdProvided() throws Exception {
        var result = List.of(
                new ServiceTypeResponse(gelPolishTypeId, nailsCategoryId, "Гель-лак", "Gel Polish", "gel-polish"));
        when(serviceCatalogService.searchServiceTypes(eq(nailsCategoryId), eq("Гель"))).thenReturn(result);
        log.debug("Act: GET /api/v1/service-types?q=Гель&categoryId={}", nailsCategoryId);

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", "Гель")
                        .param("categoryId", nailsCategoryId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].nameUk").value("Гель-лак"));
    }

    @Test
    @DisplayName("GET /service-types?categoryId=<unknown> — 404 when category does not exist")
    void should_return404_when_categoryIdDoesNotExist() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(serviceCatalogService.searchServiceTypes(eq(unknownId), isNull()))
                .thenThrow(new NotFoundException("Category not found"));

        mockMvc.perform(get("/api/v1/service-types")
                        .param("categoryId", unknownId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                // GlobalExceptionHandler returns a generic message (B3 hardening — no model leakage)
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // ── POST /api/v1/service-types/suggest ────────────────────────────────────

    @Test
    @DisplayName("POST /service-types/suggest — 202 when SALON_OWNER submits a valid suggestion")
    void should_return202_when_salonOwnerSuggestsServiceType() throws Exception {
        var userId = UUID.randomUUID();
        doNothing().when(serviceCatalogService).suggestServiceType(any(SuggestServiceTypeRequest.class), eq(userId));

        var request = new SuggestServiceTypeRequest("Ламінування вій", NAILS_CATEGORY_NAME, "Опис процедури");
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
        var request = new SuggestServiceTypeRequest("Sneaky Suggest", NAILS_CATEGORY_NAME, null);
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
        var request = new SuggestServiceTypeRequest("", NAILS_CATEGORY_NAME, null);
        log.debug("Act: POST /api/v1/service-types/suggest with blank name — must return 400");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(serviceCatalogService, never()).suggestServiceType(any(), any());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when categoryName is missing (Phase 16.7: slug, not UUID)")
    void should_return400_when_categoryNameIsMissing() throws Exception {
        var userId = UUID.randomUUID();
        // Phase 16.7 re-keyed the DTO from a System-A UUID categoryId to a System-B
        // categoryName slug. A body carrying neither field must fail @NotBlank.
        String invalidBody = "{\"name\":\"Нова послуга\"}";
        log.debug("Act: POST /api/v1/service-types/suggest with missing categoryName — must return 400");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());

        verify(serviceCatalogService, never()).suggestServiceType(any(), any());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when categoryName is blank (@NotBlank)")
    void should_return400_when_suggestCategoryNameIsBlank() throws Exception {
        var userId = UUID.randomUUID();
        var request = new SuggestServiceTypeRequest("Нова послуга", "", null);
        log.debug("Act: POST /api/v1/service-types/suggest with blank categoryName — must return 400");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(serviceCatalogService, never()).suggestServiceType(any(), any());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 with standard ApiResponse error shape when slug is unknown/inactive")
    void should_return400WithApiResponseError_when_categoryNameUnknownOrInactive() throws Exception {
        var userId = UUID.randomUUID();
        // The service resolves the slug against platform_categories (APPROVED+active);
        // an unknown/inactive slug throws BusinessException(400) which GlobalExceptionHandler
        // renders as the standard {success:false, message:...} ApiResponse.
        org.mockito.Mockito.doThrow(new com.beautica.common.exception.BusinessException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Unknown category: GHOST_CATEGORY"))
                .when(serviceCatalogService).suggestServiceType(any(SuggestServiceTypeRequest.class), eq(userId));
        var request = new SuggestServiceTypeRequest("Нова послуга", "GHOST_CATEGORY", null);
        log.debug("Act: POST /api/v1/service-types/suggest with unknown slug — service rejects with 400");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when categoryName contains a control character")
    void should_return400_when_suggestCategoryNameContainsControlChar() throws Exception {
        var userId = UUID.randomUUID();
        // NUL byte inside the slug — @Pattern(^[^\\p{Cntrl}]*$) must reject before the service runs.
        String invalidBody = "{\"name\":\"Нова послуга\",\"categoryName\":\"NAIL\\u0000SERVICE\"}";
        log.debug("Act: POST /api/v1/service-types/suggest with NUL in categoryName — must return 400");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());

        verify(serviceCatalogService, never()).suggestServiceType(any(), any());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when name contains a control character")
    void should_return400_when_nameContainsControlChar() throws Exception {
        var userId = UUID.randomUUID();
        String invalidBody = "{\"name\":\"Bad\\u0000Name\",\"categoryName\":\"" + NAILS_CATEGORY_NAME + "\"}";
        log.debug("Act: POST /api/v1/service-types/suggest with NUL in name — must return 400");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());

        verify(serviceCatalogService, never()).suggestServiceType(any(), any());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 400 when categoryName exceeds @Size(max=64)")
    void should_return400_when_categoryNameExceeds64Chars() throws Exception {
        var userId = UUID.randomUUID();
        var request = new SuggestServiceTypeRequest("Нова послуга", "A".repeat(65), null);
        log.debug("Act: POST /api/v1/service-types/suggest with 65-char categoryName — must return 400");

        mockMvc.perform(post("/api/v1/service-types/suggest")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(serviceCatalogService, never()).suggestServiceType(any(), any());
    }

    @Test
    @DisplayName("POST /service-types/suggest — 401 when no authentication provided")
    void should_return401_when_noAuthProvided() throws Exception {
        var request = new SuggestServiceTypeRequest("Ламінування вій", NAILS_CATEGORY_NAME, null);
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

        var request = new SuggestServiceTypeRequest("Ботокс для брів", NAILS_CATEGORY_NAME, "Опис");
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

        var request = new SuggestServiceTypeRequest("Нарощення нігтів", NAILS_CATEGORY_NAME, null);
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
        var request = new SuggestServiceTypeRequest("Корекція форми брів", BROWS_CATEGORY_NAME, null);
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
        String invalidBody = "{\"name\":\"" + longName + "\",\"categoryName\":\"" + NAILS_CATEGORY_NAME + "\"}";
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
        String invalidBody = "{\"name\":\"Нова послуга\",\"categoryName\":\"" + NAILS_CATEGORY_NAME
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

        var request = new SuggestServiceTypeRequest("Ламінування брів", BROWS_CATEGORY_NAME, "Деталі");
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
    @DisplayName("GET /service-types?q=<3-char string> — 200 when q has exactly three characters")
    void should_return200_when_qParamHasExactlyThreeChars() throws Exception {
        var result = List.of(
                new ServiceTypeResponse(gelPolishTypeId, nailsCategoryId, "Гель-лак", "Gel Polish", "gel-polish"));
        when(serviceCatalogService.searchServiceTypes(isNull(), eq("abc"))).thenReturn(result);
        log.debug("Act: GET /api/v1/service-types?q=abc — exactly 3 chars must return 200");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", "abc")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /service-types?q=<2-char string> — 400 when q has fewer than three characters")
    void should_return400_when_qParamHasTwoChars() throws Exception {
        log.debug("Act: GET /api/v1/service-types?q=ab — 2 chars must return 400");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", "ab")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
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

    @Test
    @DisplayName("GET /service-types?q=<script>alert(1)</script> — 400 when q contains HTML tag characters")
    void should_return400_when_qContainsScriptTag() throws Exception {
        log.debug("Act: GET /api/v1/service-types?q=<script>... — angle brackets must be rejected by @Pattern");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", "<script>alert(1)</script>")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /service-types?q=\\\"alert\\\" — 400 when q contains double-quote characters")
    void should_return400_when_qContainsDoubleQuote() throws Exception {
        log.debug("Act: GET /api/v1/service-types?q=\"alert\" — double-quote must be rejected by @Pattern");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", "\"alert\"")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── q @Size message contract (regression) ─────────────────────────────────
    // The @Size(min=3,max=100) now carries the readable message
    // "Search query must be 3–100 characters". AuthController/ServiceCatalogController
    // are @Validated, so the @RequestParam violation surfaces as a
    // ConstraintViolationException keyed by the leaf property name "q"
    // (GlobalExceptionHandler.handleConstraintViolation). These two lock the exact
    // wire message the mobile ErrorMapperInterceptor renders inline.

    @Test
    @DisplayName("GET /service-types?q=ab — errors.q carries the readable @Size message when q is too short (regression)")
    void should_returnReadableSizeMessage_when_qParamTooShort() throws Exception {
        log.debug("Act: GET /api/v1/service-types?q=ab — readable @Size(min=3) message must surface under errors.q");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", "ab")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.q").value("Search query must be 3–100 characters"));

        verify(serviceCatalogService, never()).searchServiceTypes(any(), any());
    }

    @Test
    @DisplayName("GET /service-types?q=<101-char> — errors.q carries the readable @Size message when q is too long (regression)")
    void should_returnReadableSizeMessage_when_qParamTooLong() throws Exception {
        String longQ = "a".repeat(101);
        log.debug("Act: GET /api/v1/service-types?q=<101 chars> — readable @Size(max=100) message must surface under errors.q");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", longQ)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.q").value("Search query must be 3–100 characters"));

        verify(serviceCatalogService, never()).searchServiceTypes(any(), any());
    }

    @Test
    @DisplayName("GET /service-types?q=<Cyrillic+spaces> — 200 when q contains only valid Cyrillic and spaces")
    void should_return200_when_qContainsValidCyrillicAndSpaces() throws Exception {
        var result = List.of(
                new ServiceTypeResponse(gelPolishTypeId, nailsCategoryId, "Гель-лак", "Gel Polish", "gel-polish"));
        when(serviceCatalogService.searchServiceTypes(isNull(), eq("Корекція брів"))).thenReturn(result);
        log.debug("Act: GET /api/v1/service-types?q=Корекція брів — valid Cyrillic + space must be accepted");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", "Корекція брів")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── GET /api/v1/service-types?categoryName=... (Phase 16.2) ───────────────
    // The params="categoryName" discriminant on @GetMapping routes ONLY requests
    // carrying categoryName to getServiceTypesByPlatformCategory(...). Requests
    // with categoryId/q (or neither) continue to the legacy getServiceTypes(...)
    // handler — the routing-isolation test below pins that contract.

    private List<PlatformServiceTypeResponse> stubPlatformTypes() {
        return List.of(
                new PlatformServiceTypeResponse(gelPolishTypeId, "lash-classic", "Нарощування вій (класика)", "EYELASH"),
                new PlatformServiceTypeResponse(browShapingTypeId, "lash-lamination", "Ламінування вій", "EYELASH"));
    }

    @Test
    @DisplayName("GET /service-types?categoryName=EYELASH — 200 with PlatformServiceType shape {id,slug,nameUk,categoryName}, no auth")
    void should_return200WithPlatformTypeShape_when_categoryNameProvided() throws Exception {
        when(serviceCatalogService.findServiceTypesByPlatformCategory(eq("EYELASH")))
                .thenReturn(stubPlatformTypes());
        log.debug("Act: GET /api/v1/service-types?categoryName=EYELASH — public, expect platform-type shape");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("categoryName", "EYELASH")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(gelPolishTypeId.toString()))
                .andExpect(jsonPath("$.data[0].slug").value("lash-classic"))
                .andExpect(jsonPath("$.data[0].nameUk").value("Нарощування вій (класика)"))
                .andExpect(jsonPath("$.data[0].categoryName").value("EYELASH"))
                // The legacy ServiceTypeResponse fields (categoryId, nameEn) must NOT leak
                // into this contract — the DTO is deliberately separate (anti-bug §I).
                .andExpect(jsonPath("$.data[0].categoryId").doesNotExist())
                .andExpect(jsonPath("$.data[0].nameEn").doesNotExist());

        verify(serviceCatalogService).findServiceTypesByPlatformCategory("EYELASH");
    }

    @Test
    @DisplayName("GET /service-types?categoryName=NOPE — 200 empty list (unknown slug is NOT 404)")
    void should_return200EmptyList_when_categoryNameIsUnknown() throws Exception {
        when(serviceCatalogService.findServiceTypesByPlatformCategory(eq("NOPE")))
                .thenReturn(List.of());
        log.debug("Act: GET /api/v1/service-types?categoryName=NOPE — unknown slug returns empty, not 404");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("categoryName", "NOPE")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("GET /service-types?categoryName= — 400 when categoryName is present but blank (@NotBlank)")
    void should_return400_when_categoryNameIsBlank() throws Exception {
        log.debug("Act: GET /api/v1/service-types?categoryName= — present-but-blank must return 400");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("categoryName", "")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(serviceCatalogService, never()).findServiceTypesByPlatformCategory(any());
    }

    @Test
    @DisplayName("GET /service-types?categoryName=<101-char> — 400 when categoryName exceeds @Size(max=100)")
    void should_return400_when_categoryNameExceeds100Chars() throws Exception {
        String longName = "A".repeat(101);
        log.debug("Act: GET /api/v1/service-types?categoryName=<101 chars> — must return 400");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("categoryName", longName)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(serviceCatalogService, never()).findServiceTypesByPlatformCategory(any());
    }

    @Test
    @DisplayName("GET /service-types?categoryName=<100-char> — 200 at the exact @Size(max=100) boundary")
    void should_return200_when_categoryNameIsExactly100Chars() throws Exception {
        String boundaryName = "A".repeat(100);
        when(serviceCatalogService.findServiceTypesByPlatformCategory(eq(boundaryName)))
                .thenReturn(List.of());
        log.debug("Act: GET /api/v1/service-types?categoryName=<100 chars> — boundary must be accepted (200)");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("categoryName", boundaryName)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(serviceCatalogService).findServiceTypesByPlatformCategory(boundaryName);
    }

    @Test
    @DisplayName("GET /service-types?categoryName=<script> — 400 when categoryName contains HTML angle brackets")
    void should_return400_when_categoryNameContainsHtmlChars() throws Exception {
        log.debug("Act: GET /api/v1/service-types?categoryName=<script> — angle brackets rejected by @Pattern");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("categoryName", "<script>")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(serviceCatalogService, never()).findServiceTypesByPlatformCategory(any());
    }

    @Test
    @DisplayName("GET /service-types?categoryName=A\\tB — 400 when categoryName contains a control character")
    void should_return400_when_categoryNameContainsControlChar() throws Exception {
        log.debug("Act: GET /api/v1/service-types?categoryName=A<TAB>B — control char rejected by @Pattern");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("categoryName", "A\tB")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(serviceCatalogService, never()).findServiceTypesByPlatformCategory(any());
    }

    @Test
    @DisplayName("Routing discriminant — categoryId request hits the legacy handler, NOT the platform-category handler")
    void should_routeToLegacyHandler_when_categoryIdProvidedNotCategoryName() throws Exception {
        var nailsOnly = List.of(
                new ServiceTypeResponse(gelPolishTypeId, nailsCategoryId, "Гель-лак", "Gel Polish", "gel-polish"));
        when(serviceCatalogService.searchServiceTypes(eq(nailsCategoryId), isNull())).thenReturn(nailsOnly);
        log.debug("Act: GET /api/v1/service-types?categoryId={} — must route to legacy searchServiceTypes", nailsCategoryId);

        mockMvc.perform(get("/api/v1/service-types")
                        .param("categoryId", nailsCategoryId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].categoryId").value(nailsCategoryId.toString()));

        // The legacy handler ran; the platform-category handler must NOT have been touched.
        verify(serviceCatalogService).searchServiceTypes(eq(nailsCategoryId), isNull());
        verify(serviceCatalogService, never()).findServiceTypesByPlatformCategory(any());
    }

    @Test
    @DisplayName("Routing discriminant — q request hits the legacy handler, NOT the platform-category handler")
    void should_routeToLegacyHandler_when_qProvidedNotCategoryName() throws Exception {
        when(serviceCatalogService.searchServiceTypes(isNull(), eq("Гель"))).thenReturn(List.of());
        log.debug("Act: GET /api/v1/service-types?q=Гель — must route to legacy searchServiceTypes");

        mockMvc.perform(get("/api/v1/service-types")
                        .param("q", "Гель")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(serviceCatalogService).searchServiceTypes(isNull(), eq("Гель"));
        verify(serviceCatalogService, never()).findServiceTypesByPlatformCategory(any());
    }
}
