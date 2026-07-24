package com.beautica.client.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.client.dto.BudgetBand;
import com.beautica.client.dto.PassportResponse;
import com.beautica.client.dto.TimelineItemResponse;
import com.beautica.client.service.ClientPassportService;
import com.beautica.common.PageResponse;
import com.beautica.config.WebMvcTestSupport;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link ClientController} (Phase 19.5 BEAUTI PASSPORT +
 * BEAUTY TIMELINE). The {@link ClientPassportService} is mocked; this slice pins the HTTP
 * contract — the 200 response shape for both endpoints, CLIENT-only authorization
 * (401 unauthenticated, 403 wrong role), and (critically) that the client id handed to
 * the service is always the authenticated principal's, never a path/query parameter, so
 * one client can never read another's passport. ASCII-only placeholder data throughout.
 */
@WebMvcTest(ClientController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("ClientController — @WebMvcTest slice")
class ClientControllerTest {

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

    @MockBean
    private ClientPassportService clientPassportService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private final UUID clientId = UUID.randomUUID();

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private RequestPostProcessor asClient() {
        return authenticatedAs(clientId, "client@beautica.test", Role.CLIENT);
    }

    // ── GET /clients/me/passport ─────────────────────────────────────────────────

    @Test
    @DisplayName("GET /passport — 200 shape; client id taken from the principal, not a parameter")
    void should_return200PassportAndUsePrincipalId_when_authenticatedClient() throws Exception {
        var passport = new PassportResponse(
                List.of("MANICURE", "PEDICURE"),
                List.of("Pechersk"),
                new BudgetBand(new BigDecimal("325.50"), new BigDecimal("150.00"),
                        new BigDecimal("600.00"), "UAH"),
                7);
        when(clientPassportService.getPassport(clientId)).thenReturn(passport);

        mockMvc.perform(get("/api/v1/clients/me/passport").with(asClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.favoriteProcedures[0]").value("MANICURE"))
                .andExpect(jsonPath("$.data.favoriteDistricts[0]").value("Pechersk"))
                .andExpect(jsonPath("$.data.budget.avg").value(325.50))
                .andExpect(jsonPath("$.data.budget.currency").value("UAH"))
                .andExpect(jsonPath("$.data.bookingsConsidered").value(7));

        // The id resolved from the principal — there is no clientId path/query parameter.
        verify(clientPassportService).getPassport(eq(clientId));
    }

    @Test
    @DisplayName("GET /passport — 200 empty-state shape (empty lists, null budget)")
    void should_return200EmptyState_when_noCompletedBookings() throws Exception {
        when(clientPassportService.getPassport(clientId))
                .thenReturn(new PassportResponse(List.of(), List.of(), null, 0));

        mockMvc.perform(get("/api/v1/clients/me/passport").with(asClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoriteProcedures.length()").value(0))
                .andExpect(jsonPath("$.data.budget").doesNotExist())
                .andExpect(jsonPath("$.data.bookingsConsidered").value(0));
    }

    @Test
    @DisplayName("GET /passport — 401 when unauthenticated")
    void should_return401Passport_when_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/clients/me/passport"))
                .andExpect(status().isUnauthorized());

        verify(clientPassportService, never()).getPassport(any());
    }

    @Test
    @DisplayName("GET /passport — 403 when authenticated as a non-CLIENT role")
    void should_return403Passport_when_wrongRole() throws Exception {
        mockMvc.perform(get("/api/v1/clients/me/passport")
                        .with(authenticatedAs(UUID.randomUUID(), "owner@beautica.test", Role.SALON_OWNER)))
                .andExpect(status().isForbidden());

        verify(clientPassportService, never()).getPassport(any());
    }

    // ── GET /clients/me/timeline ─────────────────────────────────────────────────

    @Test
    @DisplayName("GET /timeline — 200 paged shape; client id taken from the principal")
    void should_return200TimelineAndUsePrincipalId_when_authenticatedClient() throws Exception {
        var item = new TimelineItemResponse(
                UUID.randomUUID(), "MANICURE", "MANICURE",
                LocalDate.of(2026, 6, 18), UUID.randomUUID(), "Classic Manicure");
        when(clientPassportService.getTimeline(eq(clientId), any()))
                .thenReturn(PageResponse.of(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/clients/me/timeline").with(asClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data.length()").value(1))
                .andExpect(jsonPath("$.data.data[0].categoryKey").value("MANICURE"))
                .andExpect(jsonPath("$.data.data[0].serviceName").value("Classic Manicure"))
                .andExpect(jsonPath("$.data.data[0].date").value("2026-06-18"))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(clientPassportService).getTimeline(eq(clientId), any());
    }

    @Test
    @DisplayName("GET /timeline — 401 when unauthenticated")
    void should_return401Timeline_when_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/clients/me/timeline"))
                .andExpect(status().isUnauthorized());

        verify(clientPassportService, never()).getTimeline(any(), any());
    }

    @Test
    @DisplayName("GET /timeline — 403 when authenticated as a non-CLIENT role")
    void should_return403Timeline_when_wrongRole() throws Exception {
        mockMvc.perform(get("/api/v1/clients/me/timeline")
                        .with(authenticatedAs(UUID.randomUUID(), "master@beautica.test", Role.INDEPENDENT_MASTER)))
                .andExpect(status().isForbidden());

        verify(clientPassportService, never()).getTimeline(any(), any());
    }
}
