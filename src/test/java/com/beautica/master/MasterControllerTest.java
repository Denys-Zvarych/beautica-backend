package com.beautica.master;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.auth.filter.AuthRateLimitFilter;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.controller.MasterController;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.dto.WorkingHoursResponse;
import com.beautica.master.entity.MasterType;
import com.beautica.master.service.MasterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link MasterController}.
 *
 * <p>Migrated from {@code @SpringBootTest(RANDOM_PORT)} + Testcontainers.
 * {@link AuthorizationService} is mocked to control {@code @PreAuthorize} outcomes
 * without requiring a real database. {@link MasterService} is mocked for all
 * business-logic interactions.
 */
@WebMvcTest(MasterController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@DisplayName("MasterController — @WebMvcTest slice")
class MasterControllerTest {

    private static final Logger log = LoggerFactory.getLogger(MasterControllerTest.class);
    private static final String MASTERS_URL = "/api/v1/masters";

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
                            .requestMatchers(HttpMethod.GET, "/api/v1/masters/{masterId}").permitAll()
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
    private MasterService masterService;

    @MockBean(name = "authz")
    private AuthorizationService authorizationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private MasterDetailResponse stubMasterDetail(UUID masterId, UUID userId) {
        return new MasterDetailResponse(
                masterId, userId, "Oksana", "Kovalenko", null, null, null,
                BigDecimal.ZERO, 0, MasterType.INDEPENDENT_MASTER, null, List.of());
    }

    // ── GET /{masterId} — public ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /{masterId} — 200 without authentication (public endpoint)")
    void should_return200_when_publicGetMasterDetail() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(masterService.getMasterDetail(masterId)).thenReturn(stubMasterDetail(masterId, userId));

        log.debug("Act: GET {}/{} without credentials — public endpoint", MASTERS_URL, masterId);
        mockMvc.perform(get(MASTERS_URL + "/" + masterId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /{masterId} — 404 when master does not exist")
    void should_return404_when_getMasterWithUnknownId() throws Exception {
        var unknownMasterId = UUID.randomUUID();
        when(masterService.getMasterDetail(unknownMasterId))
                .thenThrow(new NotFoundException("Master not found"));

        log.debug("Act: GET {}/{} for a master that does not exist", MASTERS_URL, unknownMasterId);
        mockMvc.perform(get(MASTERS_URL + "/" + unknownMasterId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // ── GET /by-salon/{salonId} — protected ──────────────────────────────────

    @Test
    @DisplayName("GET /by-salon/{salonId} — 401 when no Authorization header")
    void should_return401_when_noToken_on_getMastersBySalon() throws Exception {
        var salonId = UUID.randomUUID();

        log.debug("Act: GET {}/by-salon/{} without credentials — must be rejected with 401",
                MASTERS_URL, salonId);
        mockMvc.perform(get(MASTERS_URL + "/by-salon/" + salonId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── PATCH /{masterId}/working-hours — protected ────────────────────────────

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 403 when SALON_MASTER role is used")
    void should_return403_when_unauthorizedActorPatchesWorkingHours() throws Exception {
        var masterId = UUID.randomUUID();
        var salonMasterUserId = UUID.randomUUID();
        // canManageMasterSchedule short-circuits false for SALON_MASTER role
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(false);

        var requests = List.of(new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true));
        String body = objectMapper.writeValueAsString(requests);

        log.debug("Act: PATCH {}/{}/working-hours as SALON_MASTER — must be denied", MASTERS_URL, masterId);
        mockMvc.perform(patch(MASTERS_URL + "/" + masterId + "/working-hours")
                        .with(authenticatedAs(salonMasterUserId, "smaster@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 401 when no Authorization header")
    void should_return401_when_noTokenOnPatchWorkingHours() throws Exception {
        var masterId = UUID.randomUUID();
        var requests = List.of(new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true));
        String body = objectMapper.writeValueAsString(requests);

        log.debug("Act: PATCH {}/{}/working-hours without credentials", MASTERS_URL, masterId);
        mockMvc.perform(patch(MASTERS_URL + "/" + masterId + "/working-hours")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 400 when body has invalid dayOfWeek (0 fails @Min(1))")
    void should_return400_when_invalidWorkingHoursBodyOnPatchWorkingHours() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);

        // dayOfWeek is absent → defaults to 0, which fails @Min(1) constraint
        String body = "[{\"startTime\":\"09:00\",\"endTime\":\"17:00\",\"isActive\":true}]";

        log.debug("Act: PATCH {}/{}/working-hours with invalid body (dayOfWeek=0) — must be rejected with 400",
                MASTERS_URL, masterId);
        mockMvc.perform(patch(MASTERS_URL + "/" + masterId + "/working-hours")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 400 when startTime is after endTime")
    void should_return400_when_workingHoursStartTimeIsAfterEndTime() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);

        // startTime 17:00 > endTime 09:00 — must fail @AssertTrue isTimeRangeValid
        String body = "[{\"dayOfWeek\":1,\"startTime\":\"17:00\",\"endTime\":\"09:00\",\"isActive\":true}]";
        log.debug("Act: PATCH {}/{}/working-hours with startTime after endTime", MASTERS_URL, masterId);

        mockMvc.perform(patch(MASTERS_URL + "/" + masterId + "/working-hours")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── POST /{masterId}/schedule-exceptions ──────────────────────────────────

    @Test
    @DisplayName("POST /{masterId}/schedule-exceptions — 400 when required 'date' field is absent")
    void should_return400_when_missingDateInScheduleExceptionRequest() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);

        // date is @NotNull — omitting it must fail Bean Validation with 400
        String body = "{\"reason\":\"HOLIDAY\"}";

        log.debug("Act: POST {}/{}/schedule-exceptions with missing 'date' field — must be rejected with 400",
                MASTERS_URL, masterId);
        mockMvc.perform(post(MASTERS_URL + "/" + masterId + "/schedule-exceptions")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /{masterId}/schedule-exceptions — 400 when date is in the past")
    void should_return400_when_scheduleExceptionDateIsInThePast() throws Exception {
        var masterId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);

        // date in 2020 — clearly in the past, fails @FutureOrPresent
        String body = "{\"date\":\"2020-01-01\",\"reason\":\"HOLIDAY\"}";
        log.debug("Act: POST {}/{}/schedule-exceptions with past date 2020-01-01", MASTERS_URL, masterId);

        mockMvc.perform(post(MASTERS_URL + "/" + masterId + "/schedule-exceptions")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── DELETE /{masterId} ────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /{masterId} — 204 when salon owner deactivates their master")
    void should_return204_when_authorizedActorDeactivatesMaster() throws Exception {
        var ownerUserId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        when(authorizationService.canManageMaster(any(), eq(masterId))).thenReturn(true);
        doNothing().when(masterService).deactivateMaster(eq(ownerUserId), eq(masterId));

        log.debug("Act: DELETE {}/{} as SALON_OWNER", MASTERS_URL, masterId);
        mockMvc.perform(delete(MASTERS_URL + "/" + masterId)
                        .with(authenticatedAs(ownerUserId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /{masterId} — 403 when CLIENT attempts to deactivate a master")
    void should_return403_when_clientAttemptsToDeactivateMaster() throws Exception {
        var clientUserId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        when(authorizationService.canManageMaster(any(), eq(masterId))).thenReturn(false);

        log.debug("Act: DELETE {}/{} as CLIENT — must be denied with 403", MASTERS_URL, masterId);
        mockMvc.perform(delete(MASTERS_URL + "/" + masterId)
                        .with(authenticatedAs(clientUserId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /{masterId}/schedule-exceptions/{date} ─────────────────────────

    @Test
    @DisplayName("DELETE /{masterId}/schedule-exceptions/{date} — 204 when owner removes exception")
    void should_return204_when_ownerRemovesScheduleException() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var exceptionDate = LocalDate.of(2026, 6, 1);
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(true);
        doNothing().when(masterService).removeScheduleException(eq(userId), eq(masterId), eq(exceptionDate));

        log.debug("Act: DELETE {}/{}/schedule-exceptions/{} as INDEPENDENT_MASTER", MASTERS_URL, masterId, exceptionDate);
        mockMvc.perform(delete(MASTERS_URL + "/" + masterId + "/schedule-exceptions/" + exceptionDate)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /{masterId}/schedule-exceptions/{date} — 401 when no Authorization header")
    void should_return401_when_noTokenOnDeleteScheduleException() throws Exception {
        var masterId = UUID.randomUUID();

        mockMvc.perform(delete(MASTERS_URL + "/" + masterId + "/schedule-exceptions/2026-06-01")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /{masterId}/schedule-exceptions/{date} — 403 when SALON_MASTER calls endpoint")
    void should_return403_when_salonMasterDeletesScheduleException() throws Exception {
        var salonMasterUserId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        when(authorizationService.canManageMasterSchedule(any(), eq(masterId))).thenReturn(false);

        log.debug("Act: DELETE {}/{}/schedule-exceptions/2026-06-01 as SALON_MASTER — must be denied", MASTERS_URL, masterId);
        mockMvc.perform(delete(MASTERS_URL + "/" + masterId + "/schedule-exceptions/2026-06-01")
                        .with(authenticatedAs(salonMasterUserId, "smaster@beautica.test", Role.SALON_MASTER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
