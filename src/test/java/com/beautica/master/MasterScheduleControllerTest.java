package com.beautica.master;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.master.controller.MasterController;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.ScheduleOverrideResponse;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WeeklyScheduleResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.master.service.MasterService;
import com.beautica.user.UserService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for the Phase 15.5 schedule endpoints on {@link MasterController}.
 *
 * <p>Verifies the read/write {@code @PreAuthorize} predicates (canReadMasterSchedule /
 * canManageMasterSchedule) and the request-level input gates. The {@link AuthorizationService}
 * bean is mocked to drive each predicate outcome; the {@link MasterScheduleService} is mocked
 * for the delegated business calls.
 */
@WebMvcTest(MasterController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("MasterController — schedule endpoints (@WebMvcTest slice)")
class MasterScheduleControllerTest {

    private static final String BASE = "/api/v1/masters";

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
                            .requestMatchers(HttpMethod.GET, "/api/v1/masters/{masterId}").permitAll()
                            .anyRequest().authenticated())
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
    private MasterService masterService;

    @MockBean
    private SlotCalculationService slotCalculationService;

    @MockBean
    private MasterScheduleService masterScheduleService;

    @MockBean(name = "authz")
    private AuthorizationService authz;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserService userService;

    private static RequestPostProcessor as(UUID userId, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken("u@beautica.test", null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private String weeklyBody() throws Exception {
        var req = new WeeklyScheduleRequest(
                LocalDate.now().plusDays(1), null,
                List.of(new WeeklyScheduleDayRequest(1,
                        List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0))))));
        return objectMapper.writeValueAsString(req);
    }

    private String customOverrideBody(LocalDate date) throws Exception {
        var req = new ScheduleOverrideRequest(date, ScheduleExceptionKind.CUSTOM_HOURS, null, null,
                List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(13, 0))));
        return objectMapper.writeValueAsString(req);
    }

    // ── Reads: canReadMasterSchedule ──────────────────────────────────────────

    @Test
    @DisplayName("GET weekly-schedules — 200 when read predicate allows")
    void getWeekly_allowed() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canReadMasterSchedule(any(), any())).thenReturn(true);
        when(masterScheduleService.listWeeklySchedules(any())).thenReturn(List.of());
        mockMvc.perform(get(BASE + "/" + masterId + "/weekly-schedules")
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET weekly-schedules — 200 when SALON_MASTER reads (read-only role permitted)")
    void getWeekly_salonMasterAllowed() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canReadMasterSchedule(any(), any())).thenReturn(true);
        when(masterScheduleService.listWeeklySchedules(any())).thenReturn(List.of());
        mockMvc.perform(get(BASE + "/" + masterId + "/weekly-schedules")
                        .with(as(UUID.randomUUID(), Role.SALON_MASTER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET weekly-schedules — 403 when read predicate denies")
    void getWeekly_denied() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canReadMasterSchedule(any(), any())).thenReturn(false);
        mockMvc.perform(get(BASE + "/" + masterId + "/weekly-schedules")
                        .with(as(UUID.randomUUID(), Role.CLIENT)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET weekly-schedules — 401 when unauthenticated")
    void getWeekly_anonymous() throws Exception {
        var masterId = UUID.randomUUID();
        mockMvc.perform(get(BASE + "/" + masterId + "/weekly-schedules"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET overrides — 200 when read predicate allows")
    void getOverrides_allowed() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canReadMasterSchedule(any(), any())).thenReturn(true);
        when(masterScheduleService.listOverrides(any(), any(), any())).thenReturn(List.of());
        mockMvc.perform(get(BASE + "/" + masterId + "/overrides")
                        .param("from", LocalDate.now().toString())
                        .param("to", LocalDate.now().plusDays(7).toString())
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET overrides — 403 when read predicate denies")
    void getOverrides_denied() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canReadMasterSchedule(any(), any())).thenReturn(false);
        mockMvc.perform(get(BASE + "/" + masterId + "/overrides")
                        .param("from", LocalDate.now().toString())
                        .param("to", LocalDate.now().plusDays(7).toString())
                        .with(as(UUID.randomUUID(), Role.CLIENT)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET overrides — 400 when range params absent")
    void getOverrides_missingParams() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canReadMasterSchedule(any(), any())).thenReturn(true);
        mockMvc.perform(get(BASE + "/" + masterId + "/overrides")
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET effective-schedule — 200 when read predicate allows")
    void getEffective_allowed() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canReadMasterSchedule(any(), any())).thenReturn(true);
        when(masterScheduleService.resolveEffectiveRange(any(), any(), any())).thenReturn(List.of());
        mockMvc.perform(get(BASE + "/" + masterId + "/effective-schedule")
                        .param("from", LocalDate.now().toString())
                        .param("to", LocalDate.now().plusDays(7).toString())
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET effective-schedule — 403 when read predicate denies")
    void getEffective_denied() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canReadMasterSchedule(any(), any())).thenReturn(false);
        mockMvc.perform(get(BASE + "/" + masterId + "/effective-schedule")
                        .param("from", LocalDate.now().toString())
                        .param("to", LocalDate.now().plusDays(7).toString())
                        .with(as(UUID.randomUUID(), Role.CLIENT)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET effective-schedule — 400 when range params absent")
    void getEffective_missingParams() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canReadMasterSchedule(any(), any())).thenReturn(true);
        mockMvc.perform(get(BASE + "/" + masterId + "/effective-schedule")
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER)))
                .andExpect(status().isBadRequest());
    }

    // ── Writes: canManageMasterSchedule ───────────────────────────────────────

    @Test
    @DisplayName("POST weekly-schedules — 201 when manage predicate allows")
    void createWeekly_allowed() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canManageMasterSchedule(any(), any())).thenReturn(true);
        when(masterScheduleService.upsertWeeklySchedule(any(), any(), any(), any()))
                .thenReturn(new WeeklyScheduleResponse(LocalDate.now(), null, List.of()));
        mockMvc.perform(post(BASE + "/" + masterId + "/weekly-schedules")
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weeklyBody()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST weekly-schedules — 403 when manage predicate denies (SALON_MASTER)")
    void createWeekly_denied() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canManageMasterSchedule(any(), any())).thenReturn(false);
        mockMvc.perform(post(BASE + "/" + masterId + "/weekly-schedules")
                        .with(as(UUID.randomUUID(), Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weeklyBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST weekly-schedules — 401 when unauthenticated")
    void createWeekly_anonymous() throws Exception {
        var masterId = UUID.randomUUID();
        mockMvc.perform(post(BASE + "/" + masterId + "/weekly-schedules")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weeklyBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST weekly-schedules — 400 when validFrom is absent")
    void createWeekly_missingValidFrom() throws Exception {
        var masterId = UUID.randomUUID();
        when(authz.canManageMasterSchedule(any(), any())).thenReturn(true);
        String body = "{\"days\":[{\"dayOfWeek\":1,"
                + "\"intervals\":[{\"startTime\":\"09:00:00\",\"endTime\":\"17:00:00\"}]}]}";
        mockMvc.perform(post(BASE + "/" + masterId + "/weekly-schedules")
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT weekly-schedules/{id} — 200 when manage predicate allows")
    void updateWeekly_allowed() throws Exception {
        var masterId = UUID.randomUUID();
        var scheduleId = UUID.randomUUID();
        when(authz.canManageMasterSchedule(any(), any())).thenReturn(true);
        when(masterScheduleService.upsertWeeklySchedule(any(), any(), any(), any()))
                .thenReturn(new WeeklyScheduleResponse(LocalDate.now(), null, List.of()));
        mockMvc.perform(put(BASE + "/" + masterId + "/weekly-schedules/" + scheduleId)
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weeklyBody()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE weekly-schedules/{id} — 204 when manage predicate allows")
    void deleteWeekly_allowed() throws Exception {
        var masterId = UUID.randomUUID();
        var scheduleId = UUID.randomUUID();
        when(authz.canManageMasterSchedule(any(), any())).thenReturn(true);
        doNothing().when(masterScheduleService).deleteWeeklySchedule(any(), any(), any());
        mockMvc.perform(delete(BASE + "/" + masterId + "/weekly-schedules/" + scheduleId)
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE weekly-schedules/{id} — 403 when manage predicate denies")
    void deleteWeekly_denied() throws Exception {
        var masterId = UUID.randomUUID();
        var scheduleId = UUID.randomUUID();
        when(authz.canManageMasterSchedule(any(), any())).thenReturn(false);
        mockMvc.perform(delete(BASE + "/" + masterId + "/weekly-schedules/" + scheduleId)
                        .with(as(UUID.randomUUID(), Role.SALON_MASTER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT overrides/{date} — 200 when manage allows and path matches body date")
    void putOverride_allowed() throws Exception {
        var masterId = UUID.randomUUID();
        var date = LocalDate.now().plusDays(1);
        when(authz.canManageMasterSchedule(any(), any())).thenReturn(true);
        when(masterScheduleService.upsertOverride(any(), any(), any()))
                .thenReturn(new ScheduleOverrideResponse(
                        date, ScheduleExceptionKind.CUSTOM_HOURS, null, null, List.of()));
        mockMvc.perform(put(BASE + "/" + masterId + "/overrides/" + date)
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customOverrideBody(date)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT overrides/{date} — 400 when path date differs from body date")
    void putOverride_dateMismatch() throws Exception {
        var masterId = UUID.randomUUID();
        var bodyDate = LocalDate.now().plusDays(1);
        var pathDate = LocalDate.now().plusDays(2);
        when(authz.canManageMasterSchedule(any(), any())).thenReturn(true);
        mockMvc.perform(put(BASE + "/" + masterId + "/overrides/" + pathDate)
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customOverrideBody(bodyDate)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT overrides/{date} — 400 when DAY_OFF body omits reason")
    void putOverride_dayOffWithoutReason() throws Exception {
        var masterId = UUID.randomUUID();
        var date = LocalDate.now().plusDays(1);
        when(authz.canManageMasterSchedule(any(), any())).thenReturn(true);
        String body = "{\"date\":\"" + date + "\",\"kind\":\"DAY_OFF\"}";
        mockMvc.perform(put(BASE + "/" + masterId + "/overrides/" + date)
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT overrides/{date} — 403 when manage predicate denies")
    void putOverride_denied() throws Exception {
        var masterId = UUID.randomUUID();
        var date = LocalDate.now().plusDays(1);
        when(authz.canManageMasterSchedule(any(), any())).thenReturn(false);
        mockMvc.perform(put(BASE + "/" + masterId + "/overrides/" + date)
                        .with(as(UUID.randomUUID(), Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customOverrideBody(date)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE overrides/{date} — 204 when manage predicate allows")
    void deleteOverride_allowed() throws Exception {
        var masterId = UUID.randomUUID();
        var date = LocalDate.now().plusDays(1);
        when(authz.canManageMasterSchedule(any(), any())).thenReturn(true);
        doNothing().when(masterScheduleService).clearOverride(any(), any(), any());
        mockMvc.perform(delete(BASE + "/" + masterId + "/overrides/" + date)
                        .with(as(UUID.randomUUID(), Role.INDEPENDENT_MASTER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }
}
