package com.beautica.favorite.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.favorite.dto.AddFavoriteRequest;
import com.beautica.favorite.dto.FavoriteMasterResponse;
import com.beautica.favorite.dto.FavoriteResponse;
import com.beautica.favorite.dto.FavoriteSalonResponse;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.service.FavoriteService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link FavoriteController} (Phase 19.1).
 *
 * <p>The {@link FavoriteService} is mocked; this slice verifies the HTTP contract —
 * idempotent {@code 200} on POST, {@code 204} on DELETE, {@code 404}/{@code 400}
 * exception mapping, CLIENT-only authorization, and (critically) that the favoriting
 * client id handed to the service comes from the authenticated principal, never from
 * the request body or a query param. ASCII-only placeholder data throughout.
 */
@WebMvcTest(FavoriteController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("FavoriteController — @WebMvcTest slice")
class FavoriteControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FavoriteService favoriteService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private final UUID clientId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private RequestPostProcessor asClient() {
        return authenticatedAs(clientId, "client@beautica.test", Role.CLIENT);
    }

    private String addBody(FavoriteTargetType type) throws Exception {
        return objectMapper.writeValueAsString(new AddFavoriteRequest(type, targetId));
    }

    // ── POST /favorites ──────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /favorites — 200 on create and binds the principal id (not the body)")
    void should_return200AndUsePrincipalId_when_createFavorite() throws Exception {
        when(favoriteService.addFavorite(eq(clientId), eq(FavoriteTargetType.MASTER), eq(targetId)))
                .thenReturn(new FavoriteResponse(
                        UUID.randomUUID(), FavoriteTargetType.MASTER, targetId,
                        Instant.parse("2026-06-18T10:00:00Z")));

        mockMvc.perform(post("/api/v1/favorites")
                        .with(asClient()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(FavoriteTargetType.MASTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.targetId").value(targetId.toString()))
                .andExpect(jsonPath("$.data.targetType").value("MASTER"));

        // The id passed to the service is the principal's — never read from the body.
        verify(favoriteService).addFavorite(eq(clientId), eq(FavoriteTargetType.MASTER), eq(targetId));
    }

    @Test
    @DisplayName("POST /favorites — duplicate is idempotent 200 (service returns the existing row)")
    void should_return200_when_duplicateFavorite() throws Exception {
        // The service is idempotent and returns the pre-existing favorite; from the
        // controller's view a duplicate is indistinguishable from a create — both 200.
        when(favoriteService.addFavorite(eq(clientId), eq(FavoriteTargetType.SALON), eq(targetId)))
                .thenReturn(new FavoriteResponse(
                        UUID.randomUUID(), FavoriteTargetType.SALON, targetId,
                        Instant.parse("2026-06-18T09:00:00Z")));

        mockMvc.perform(post("/api/v1/favorites")
                        .with(asClient()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(FavoriteTargetType.SALON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetType").value("SALON"));
    }

    @Test
    @DisplayName("POST /favorites — 404 when the target does not exist")
    void should_return404_when_targetMissing() throws Exception {
        when(favoriteService.addFavorite(any(), any(), any()))
                .thenThrow(new NotFoundException("Master not found"));

        mockMvc.perform(post("/api/v1/favorites")
                        .with(asClient()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(FavoriteTargetType.MASTER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /favorites — 400 when the target is a salon-employed master")
    void should_return400_when_targetIsSalonMaster() throws Exception {
        when(favoriteService.addFavorite(any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST,
                        "Only independent masters can be favorited"));

        mockMvc.perform(post("/api/v1/favorites")
                        .with(asClient()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(FavoriteTargetType.MASTER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /favorites — 401 when unauthenticated")
    void should_return401_when_unauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/favorites")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(FavoriteTargetType.MASTER)))
                .andExpect(status().isUnauthorized());

        verify(favoriteService, never()).addFavorite(any(), any(), any());
    }

    @Test
    @DisplayName("POST /favorites — 403 when authenticated as a non-CLIENT role")
    void should_return403_when_wrongRole() throws Exception {
        mockMvc.perform(post("/api/v1/favorites")
                        .with(authenticatedAs(UUID.randomUUID(), "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(FavoriteTargetType.MASTER)))
                .andExpect(status().isForbidden());

        verify(favoriteService, never()).addFavorite(any(), any(), any());
    }

    // ── DELETE /favorites ──────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /favorites — 204 idempotent and binds the principal id")
    void should_return204AndUsePrincipalId_when_removeFavorite() throws Exception {
        mockMvc.perform(delete("/api/v1/favorites")
                        .with(asClient()).with(csrf())
                        .param("targetType", "MASTER")
                        .param("targetId", targetId.toString()))
                .andExpect(status().isNoContent());

        verify(favoriteService).removeFavorite(eq(clientId), eq(FavoriteTargetType.MASTER), eq(targetId));
    }

    @Test
    @DisplayName("DELETE /favorites — 401 when unauthenticated")
    void should_return401_when_deleteUnauthenticated() throws Exception {
        mockMvc.perform(delete("/api/v1/favorites")
                        .with(csrf())
                        .param("targetType", "SALON")
                        .param("targetId", targetId.toString()))
                .andExpect(status().isUnauthorized());

        verify(favoriteService, never()).removeFavorite(any(), any(), any());
    }

    // ── GET /favorites/masters ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /favorites/masters — 200 paged list bound to the principal id")
    void should_return200MasterPage_when_listMasters() throws Exception {
        var master = new FavoriteMasterResponse(
                UUID.randomUUID(), "Maria", "Levchenko", "https://cdn/avatar.png",
                "Kyiv", "Pechersk", 4.75, "Manicure");
        when(favoriteService.listMasterFavorites(eq(clientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(master)));

        mockMvc.perform(get("/api/v1/favorites/masters").with(asClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data.length()").value(1))
                .andExpect(jsonPath("$.data.data[0].firstName").value("Maria"))
                .andExpect(jsonPath("$.data.data[0].lastServiceName").value("Manicure"))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(favoriteService).listMasterFavorites(eq(clientId), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /favorites/masters — 401 when unauthenticated")
    void should_return401_when_listMastersUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/favorites/masters"))
                .andExpect(status().isUnauthorized());

        verify(favoriteService, never()).listMasterFavorites(any(), any(Pageable.class));
    }

    // ── GET /favorites/salons ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /favorites/salons — 200 paged list bound to the principal id")
    void should_return200SalonPage_when_listSalons() throws Exception {
        var salon = new FavoriteSalonResponse(
                UUID.randomUUID(), "Salon Bella", "https://cdn/s.png",
                "Odesa", "Prymorskyi", 4.20);
        when(favoriteService.listSalonFavorites(eq(clientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(salon)));

        mockMvc.perform(get("/api/v1/favorites/salons").with(asClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.length()").value(1))
                .andExpect(jsonPath("$.data.data[0].name").value("Salon Bella"))
                .andExpect(jsonPath("$.data.data[0].avgRating").value(4.20));

        verify(favoriteService).listSalonFavorites(eq(clientId), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /favorites/salons — 403 when authenticated as a non-CLIENT role")
    void should_return403_when_listSalonsWrongRole() throws Exception {
        mockMvc.perform(get("/api/v1/favorites/salons")
                        .with(authenticatedAs(UUID.randomUUID(), "master@beautica.test", Role.INDEPENDENT_MASTER)))
                .andExpect(status().isForbidden());

        verify(favoriteService, never()).listSalonFavorites(any(), any(Pageable.class));
    }
}
