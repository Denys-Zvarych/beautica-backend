package com.beautica.service.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.service.dto.ApprovedCategoryResponse;
import com.beautica.service.dto.CategoryRequestResponse;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.service.CategoryRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CategoryRequestController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("CategoryRequestController — @WebMvcTest slice")
class CategoryRequestControllerTest {

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
                            // GET /approved and POST /requests both require authentication;
                            // role enforcement on /requests is via @PreAuthorize.
                            .anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private CategoryRequestService categoryRequestService;
    @MockBean private JwtTokenProvider jwtTokenProvider;

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    // ── POST /requests ────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return201_when_salonOwnerSubmitsRequest")
    void should_return201_when_salonOwnerSubmitsRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        when(categoryRequestService.submitRequest(any(CreateCategoryRequestRequest.class), eq(userId)))
                .thenReturn(new CategoryRequestResponse("NAIL_ART", "Нейл-арт", "PENDING"));
        var body = new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт");

        mockMvc.perform(post("/api/v1/service-categories/requests")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.name").value("NAIL_ART"));
    }

    @Test
    @DisplayName("should_return403_when_clientSubmitsRequest")
    void should_return403_when_clientSubmitsRequest() throws Exception {
        var body = new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт");

        mockMvc.perform(post("/api/v1/service-categories/requests")
                        .with(authenticatedAs(UUID.randomUUID(), "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("should_return403_when_salonMasterSubmitsRequest")
    void should_return403_when_salonMasterSubmitsRequest() throws Exception {
        var body = new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт");

        mockMvc.perform(post("/api/v1/service-categories/requests")
                        .with(authenticatedAs(UUID.randomUUID(), "smaster@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("should_return401_when_unauthenticatedSubmitsRequest")
    void should_return401_when_unauthenticatedSubmitsRequest() throws Exception {
        var body = new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт");

        mockMvc.perform(post("/api/v1/service-categories/requests")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("should_return400_when_nameViolatesPattern")
    void should_return400_when_nameViolatesPattern() throws Exception {
        String invalid = "{\"name\":\"nail art\",\"displayName\":\"Нейл-арт\"}";

        mockMvc.perform(post("/api/v1/service-categories/requests")
                        .with(authenticatedAs(UUID.randomUUID(), "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should_return400_when_displayNameBlank")
    void should_return400_when_displayNameBlank() throws Exception {
        String invalid = "{\"name\":\"NAIL_ART\",\"displayName\":\"\"}";

        mockMvc.perform(post("/api/v1/service-categories/requests")
                        .with(authenticatedAs(UUID.randomUUID(), "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest());
    }

    // ── GET /approved ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return401_when_unauthenticatedListsApproved")
    void should_return401_when_unauthenticatedListsApproved() throws Exception {
        mockMvc.perform(get("/api/v1/service-categories/approved")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("should_return200WithList_when_authenticatedListsApproved")
    void should_return200WithList_when_authenticatedListsApproved() throws Exception {
        when(categoryRequestService.listApproved())
                .thenReturn(List.of(new ApprovedCategoryResponse("MANICURE", "Манікюр")));

        mockMvc.perform(get("/api/v1/service-categories/approved")
                        .with(authenticatedAs(UUID.randomUUID(), "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("MANICURE"))
                .andExpect(jsonPath("$.data[0].displayName").value("Манікюр"));
    }
}
