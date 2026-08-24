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
import com.beautica.favorite.dto.FavoriteServiceResponse;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.service.FavoriteService;
import com.beautica.service.entity.PriceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import java.math.BigDecimal;
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

    /**
     * RETARGETED by mobile Phase 111 (was {@code should_return400_when_targetIsSalonMaster}). A
     * salon-employed master is no longer a rejected target, so the message this stubbed no longer
     * exists; the surviving 400 on the MASTER arm is the INACTIVE guard. What this test actually
     * pins is unchanged — that a {@code BusinessException(BAD_REQUEST)} from the service maps to
     * a 400 body with {@code success=false}, not the domain rule itself (which
     * {@code FavoriteServiceTest} owns).
     */
    @Test
    @DisplayName("POST /favorites — 400 when the target master is inactive")
    void should_return400_when_targetMasterInactive() throws Exception {
        when(favoriteService.addFavorite(any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST,
                        "Only an active master can be favorited"));

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
        UUID salonId = UUID.randomUUID();
        var master = new FavoriteMasterResponse(
                UUID.randomUUID(), "Maria", "Levchenko", "https://cdn/avatar.png",
                "Kyiv", "Pechersk", 4.75, salonId, "Salon Bella",
                "Khreshchatyk St", "12B", "entry code 4321",
                // Deliberately NOT the same category the salon fixture below uses: the two
                // arms serialise through separate DTOs, and identical fixture values would let
                // a copy-paste error between them pass unnoticed.
                "HAIRCUT", "Стрижка");
        when(favoriteService.listMasterFavorites(eq(clientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(master)));

        mockMvc.perform(get("/api/v1/favorites/masters").with(asClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data.length()").value(1))
                .andExpect(jsonPath("$.data.data[0].firstName").value("Maria"))
                .andExpect(jsonPath("$.data.data[0].street").value("Khreshchatyk St"))
                .andExpect(jsonPath("$.data.data[0].buildingNo").value("12B"))
                .andExpect(jsonPath("$.data.data[0].locationNote").value("entry code 4321"))
                // The affiliation line the card renders for a salon-employed master. It never
                // rendered before this pair existed on the DTO, so assert BOTH reach the wire —
                // salonName alone would leave the client parsing an id out of a display name.
                .andExpect(jsonPath("$.data.data[0].salonId").value(salonId.toString()))
                .andExpect(jsonPath("$.data.data[0].salonName").value("Salon Bella"))
                // Phase 111 removed lastServiceName from the design and the DTO — assert it is
                // gone from the wire, not merely absent from the assertions above.
                .andExpect(jsonPath("$.data.data[0].lastServiceName").doesNotExist())
                // The category FILTER axis. Both halves must reach the wire: the client keys
                // its chip identity off the code and draws the chip from the label, so a DTO
                // that serialised only one would render an unlabelled or unmatchable chip.
                .andExpect(jsonPath("$.data.data[0].categoryCode").value("HAIRCUT"))
                .andExpect(jsonPath("$.data.data[0].categoryLabel").value("Стрижка"))
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
                "Odesa", "Prymorskyi", 4.20, "Derybasivska St", "7", "2nd floor",
                "MANICURE", "Манікюр");
        when(favoriteService.listSalonFavorites(eq(clientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(salon)));

        mockMvc.perform(get("/api/v1/favorites/salons").with(asClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.length()").value(1))
                .andExpect(jsonPath("$.data.data[0].name").value("Salon Bella"))
                .andExpect(jsonPath("$.data.data[0].avgRating").value(4.20))
                .andExpect(jsonPath("$.data.data[0].street").value("Derybasivska St"))
                .andExpect(jsonPath("$.data.data[0].buildingNo").value("7"))
                .andExpect(jsonPath("$.data.data[0].locationNote").value("2nd floor"))
                // The salon arm carries the SAME category axis as the master arm — the approved
                // design filters both kinds through one chip row, so a salon DTO that omitted
                // these would make every chip hide every salon.
                .andExpect(jsonPath("$.data.data[0].categoryCode").value("MANICURE"))
                .andExpect(jsonPath("$.data.data[0].categoryLabel").value("Манікюр"));

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

    // ── GET /favorites/services — the BEAUTY WISH LIST (Phase 31.4) ─────────────

    private static FavoriteServiceResponse wishListRow() {
        return new FavoriteServiceResponse(
                FavoriteServiceResponse.SourceType.MASTER,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Manicure",
                "Maria", "Levchenko", "https://cdn/avatar.png",
                90, PriceType.RANGE,
                new BigDecimal("600.00"), new BigDecimal("900.00"), "vid 600 do 900",
                null, null, null);
    }

    @Test
    @DisplayName("GET /favorites/services — 200 paged wish list bound to the principal id")
    void should_return200ServicePage_when_listServices() throws Exception {
        var row = wishListRow();
        when(favoriteService.listServiceFavorites(eq(clientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row)));

        mockMvc.perform(get("/api/v1/favorites/services").with(asClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data.length()").value(1))
                .andExpect(jsonPath("$.data.data[0].masterServiceId").value(row.masterServiceId().toString()))
                .andExpect(jsonPath("$.data.data[0].masterId").value(row.masterId().toString()))
                .andExpect(jsonPath("$.data.data[0].serviceName").value("Manicure"))
                .andExpect(jsonPath("$.data.data[0].durationMinutes").value(90))
                .andExpect(jsonPath("$.data.data[0].priceType").value("RANGE"))
                .andExpect(jsonPath("$.data.data[0].priceMax").value(900.00))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(favoriteService).listServiceFavorites(eq(clientId), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /favorites/services — honours @PageableDefault(size = 20) when no params are sent")
    void should_useDefaultPageSize_when_noPageableParams() throws Exception {
        when(favoriteService.listServiceFavorites(eq(clientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/favorites/services").with(asClient()))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(favoriteService).listServiceFavorites(eq(clientId), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("GET /favorites/services — 401 when unauthenticated")
    void should_return401_when_listServicesUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/favorites/services"))
                .andExpect(status().isUnauthorized());

        verify(favoriteService, never()).listServiceFavorites(any(), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /favorites/services — 403 for a SALON_OWNER")
    void should_return403_when_listServicesAsSalonOwner() throws Exception {
        mockMvc.perform(get("/api/v1/favorites/services")
                        .with(authenticatedAs(UUID.randomUUID(), "owner@beautica.test", Role.SALON_OWNER)))
                .andExpect(status().isForbidden());

        verify(favoriteService, never()).listServiceFavorites(any(), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /favorites/services — 403 for a SALON_MASTER")
    void should_return403_when_listServicesAsSalonMaster() throws Exception {
        mockMvc.perform(get("/api/v1/favorites/services")
                        .with(authenticatedAs(UUID.randomUUID(), "staff@beautica.test", Role.SALON_MASTER)))
                .andExpect(status().isForbidden());

        verify(favoriteService, never()).listServiceFavorites(any(), any(Pageable.class));
    }

    @Test
    @DisplayName("POST /favorites — accepts targetType=SERVICE and binds the principal id")
    void should_return200_when_favoritingAService() throws Exception {
        when(favoriteService.addFavorite(eq(clientId), eq(FavoriteTargetType.SERVICE), eq(targetId)))
                .thenReturn(new FavoriteResponse(
                        UUID.randomUUID(), FavoriteTargetType.SERVICE, targetId,
                        Instant.parse("2026-08-07T10:00:00Z")));

        mockMvc.perform(post("/api/v1/favorites")
                        .with(asClient()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(FavoriteTargetType.SERVICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetType").value("SERVICE"));

        verify(favoriteService).addFavorite(eq(clientId), eq(FavoriteTargetType.SERVICE), eq(targetId));
    }

    @Test
    @DisplayName("DELETE /favorites — 204 idempotent for targetType=SERVICE")
    void should_return204_when_unfavoritingAService() throws Exception {
        mockMvc.perform(delete("/api/v1/favorites")
                        .with(asClient()).with(csrf())
                        .param("targetType", "SERVICE")
                        .param("targetId", targetId.toString()))
                .andExpect(status().isNoContent());

        verify(favoriteService).removeFavorite(eq(clientId), eq(FavoriteTargetType.SERVICE), eq(targetId));
    }
}
