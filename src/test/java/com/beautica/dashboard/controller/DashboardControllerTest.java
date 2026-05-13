package com.beautica.dashboard.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.dashboard.dto.RevenueByDateDto;
import com.beautica.dashboard.dto.RevenueByMasterDto;
import com.beautica.dashboard.dto.RevenueByServiceDto;
import com.beautica.dashboard.dto.RevenueResponse;
import com.beautica.dashboard.service.DashboardService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link DashboardController}.
 *
 * <p>Follows the established slice-test pattern from {@code MediaControllerTest}: pass-through
 * filter overrides via {@link WebMvcTestSupport}, in-class {@link SecurityFilterChain} with
 * {@code @EnableMethodSecurity} so the class-level {@code @PreAuthorize} is exercised, and
 * {@code SecurityMockMvcRequestPostProcessors.authentication()} for principal injection.
 *
 * <p>{@code @WithMockUser} is intentionally avoided — it never populates
 * {@link UsernamePasswordAuthenticationToken#getDetails()} with the UUID that
 * {@code DashboardController.extractUserId} reads.
 */
@WebMvcTest(DashboardController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("DashboardController — @WebMvcTest slice")
class DashboardControllerTest {

    private static final Logger log = LoggerFactory.getLogger(DashboardControllerTest.class);
    private static final String REVENUE_URL = "/api/v1/dashboard/revenue";

    // ── Security configuration ────────────────────────────────────────────────

    /**
     * Mirrors production security for the dashboard endpoint: all requests must be
     * authenticated, unauthenticated ones get 401 (not the Spring default 403).
     * Method security is enabled so the class-level {@code @PreAuthorize} fires.
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurity {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                                                    JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/api/v1/dashboard/**").authenticated()
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

    @MockBean
    private DashboardService dashboardService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a {@link UsernamePasswordAuthenticationToken} carrying the user UUID in
     * {@code getDetails()} — matching the contract {@code DashboardController.extractUserId}
     * expects (Anti-Bug Playbook § B).
     */
    private static RequestPostProcessor authenticatedAs(UUID userId, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    /**
     * Returns an empty {@link RevenueResponse} suitable for mock stubbing when the
     * concrete revenue values are not under test.
     */
    private static RevenueResponse emptyResponse() {
        return new RevenueResponse(0L, BigDecimal.ZERO,
                List.<RevenueByMasterDto>of(),
                List.<RevenueByServiceDto>of(),
                List.<RevenueByDateDto>of());
    }

    // ── 1. SALON_OWNER — happy path ────────────────────────────────────────────

    @Test
    @DisplayName("GET /revenue — 200 when SALON_OWNER queries the dashboard")
    void should_return200_when_salonOwnerQueriesDashboard() throws Exception {
        var userId = UUID.randomUUID();

        when(dashboardService.getRevenueSummary(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(emptyResponse());

        log.debug("Act: GET {} as SALON_OWNER user={}", REVENUE_URL, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .with(authenticatedAs(userId, Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── 2. INDEPENDENT_MASTER — happy path ───────────────────────────────────

    @Test
    @DisplayName("GET /revenue — 200 when INDEPENDENT_MASTER queries the dashboard")
    void should_return200_when_independentMasterQueriesDashboard() throws Exception {
        var userId = UUID.randomUUID();

        when(dashboardService.getRevenueSummary(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(emptyResponse());

        log.debug("Act: GET {} as INDEPENDENT_MASTER user={}", REVENUE_URL, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .with(authenticatedAs(userId, Role.INDEPENDENT_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── 3. CLIENT — must be denied ────────────────────────────────────────────

    @Test
    @DisplayName("GET /revenue — 403 when CLIENT queries the dashboard")
    void should_return403_when_clientQueriesDashboard() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: GET {} as CLIENT user={} — @PreAuthorize must deny with 403", REVENUE_URL, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .with(authenticatedAs(userId, Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ── 4. SALON_MASTER — must be denied ─────────────────────────────────────

    @Test
    @DisplayName("GET /revenue — 403 when SALON_MASTER queries the dashboard")
    void should_return403_when_salonMasterQueriesDashboard() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: GET {} as SALON_MASTER user={} — @PreAuthorize must deny with 403", REVENUE_URL, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .with(authenticatedAs(userId, Role.SALON_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ── 5. No token — must be 401 ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /revenue — 401 when no Authorization header is supplied")
    void should_return401_when_noTokenOnDashboard() throws Exception {
        log.debug("Act: GET {} without authentication — unauthenticated request must return 401", REVENUE_URL);
        mockMvc.perform(get(REVENUE_URL)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── 6. Date range > 365 days — service throws 400 ────────────────────────

    @Test
    @DisplayName("GET /revenue — 400 when date range exceeds 365 days")
    void should_return400_when_dateRangeExceeds365Days() throws Exception {
        var userId = UUID.randomUUID();

        when(dashboardService.getRevenueSummary(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST, "Date range must not exceed 365 days"));

        log.debug("Act: GET {}?from=2020-01-01&to=2022-01-01 as SALON_OWNER user={}", REVENUE_URL, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .param("from", "2020-01-01")
                        .param("to", "2022-01-01")
                        .with(authenticatedAs(userId, Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── 7. No date params — service defaults to last 30 days ─────────────────

    @Test
    @DisplayName("GET /revenue — 200 when no date params provided (service defaults to last 30 days)")
    void should_return200_when_noDateParamsProvided() throws Exception {
        var userId = UUID.randomUUID();

        when(dashboardService.getRevenueSummary(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(emptyResponse());

        log.debug("Act: GET {} without from/to params as SALON_OWNER user={}", REVENUE_URL, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .with(authenticatedAs(userId, Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalCompletedBookings").value(0));
    }

    // ── 8a. INDEPENDENT_MASTER filters by another master's UUID — service must 403 ──

    @Test
    @DisplayName("GET /revenue — 403 when INDEPENDENT_MASTER passes another master's UUID as filterMasterId")
    void should_return403_when_independentMasterFiltersOtherMasterId() throws Exception {
        var userId      = UUID.randomUUID();
        var otherMasterId = UUID.randomUUID();

        when(dashboardService.getRevenueSummary(
                any(), any(), any(), any(), eq(otherMasterId), any(), any()))
                .thenThrow(new ForbiddenException("Independent master may not filter by another master"));

        log.debug("Act: GET {}?filterMasterId={} as INDEPENDENT_MASTER user={} — service enforces IDOR guard",
                REVENUE_URL, otherMasterId, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .param("filterMasterId", otherMasterId.toString())
                        .with(authenticatedAs(userId, Role.INDEPENDENT_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ── 9. SALON_ADMIN — must be denied ──────────────────────────────────────

    @Test
    @DisplayName("GET /revenue — 403 when SALON_ADMIN queries the dashboard")
    void should_return403_when_salonAdminQueriesDashboard() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: GET {} as SALON_ADMIN user={} — @PreAuthorize must deny with 403", REVENUE_URL, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .with(authenticatedAs(userId, Role.SALON_ADMIN))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ── 10. serviceDefId query param — controller passes it through ───────────

    @Test
    @DisplayName("GET /revenue — 200 when serviceDefId query param is provided")
    void should_return200_when_serviceDefIdProvided() throws Exception {
        var userId   = UUID.randomUUID();
        var svcDefId = UUID.randomUUID();

        when(dashboardService.getRevenueSummary(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(emptyResponse());

        log.debug("Act: GET {}?serviceDefId={} as SALON_OWNER user={}", REVENUE_URL, svcDefId, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .param("serviceDefId", svcDefId.toString())
                        .with(authenticatedAs(userId, Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── 8. filterMasterId query param — controller passes it through ──────────

    @Test
    @DisplayName("GET /revenue — 200 when filterMasterId query param is provided")
    void should_return200_when_filterMasterIdProvided() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        when(dashboardService.getRevenueSummary(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(emptyResponse());

        log.debug("Act: GET {}?filterMasterId={} as SALON_OWNER user={}", REVENUE_URL, masterId, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .param("filterMasterId", masterId.toString())
                        .with(authenticatedAs(userId, Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── 11. salonId query param — controller passes Optional to service ────────

    @Test
    @DisplayName("GET /revenue — 200 when salonId query param is provided for SALON_OWNER")
    void should_return200_when_salonIdFilterProvided() throws Exception {
        var userId  = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        when(dashboardService.getRevenueSummary(any(), any(), any(), any(), any(), any(),
                eq(Optional.of(salonId))))
                .thenReturn(emptyResponse());

        log.debug("Act: GET {}?salonId={} as SALON_OWNER user={}", REVENUE_URL, salonId, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .param("salonId", salonId.toString())
                        .with(authenticatedAs(userId, Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── B4. Extreme date params — controller validates bounds ─────────────────

    @Test
    @DisplayName("GET /revenue — 400 when 'from' param is more than 10 years in the past")
    void should_return400_when_dateParamIsExtremelyFarInThePast() throws Exception {
        var userId = UUID.randomUUID();

        // 2000-01-01 is >10 years before today (2026-05-13)
        log.debug("Act: GET {}?from=2000-01-01 as SALON_OWNER user={} — must be rejected with 400",
                REVENUE_URL, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .param("from", "2000-01-01")
                        .param("to", "2000-01-31")
                        .with(authenticatedAs(userId, Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── 12. salonId owned by another owner — service must 403 ─────────────────

    @Test
    @DisplayName("GET /revenue — 403 when SALON_OWNER passes a salonId they do not own")
    void should_return403_when_owner_filters_by_salon_they_do_not_own() throws Exception {
        var userId        = UUID.randomUUID();
        var foreignSalonId = UUID.randomUUID();

        when(dashboardService.getRevenueSummary(any(), any(), any(), any(), any(), any(),
                eq(Optional.of(foreignSalonId))))
                .thenThrow(new ForbiddenException("Salon does not belong to the authenticated owner"));

        log.debug("Act: GET {}?salonId={} as SALON_OWNER user={} — must be 403 (not their salon)",
                REVENUE_URL, foreignSalonId, userId);
        mockMvc.perform(get(REVENUE_URL)
                        .param("salonId", foreignSalonId.toString())
                        .with(authenticatedAs(userId, Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
