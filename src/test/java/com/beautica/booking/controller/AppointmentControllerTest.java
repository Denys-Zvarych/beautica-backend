package com.beautica.booking.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.service.AppointmentService;
import com.beautica.booking.service.AppointmentTransitionService;
import com.beautica.booking.service.BookingService;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link AppointmentController}. Originally scoped to the two NEW
 * per-item routes added by track 30.x: {@code PATCH .../services/{bookingId}/reschedule} (phase
 * 30.5) and {@code PATCH .../services/{bookingId}/cancel} (phase 30.6) — this was the first
 * controller-level test file for {@code AppointmentController}, the pre-existing whole-visit
 * routes having been exercised only at IT level. Widened (cycle-5 audit finding 2, 2026-08-03) to
 * also cover the WHOLE-VISIT {@code PATCH /{appointmentId}/reschedule} route's OWN
 * {@code @PreAuthorize} fix — dropping {@code canRescheduleAppointment} from its SpEL, mirroring
 * the per-item route's own perf audit F1 fix below — so both routes' role-only gate + single
 * service-layer authority check are pinned in the SAME file, by the SAME technique.
 *
 * <p>Mirrors {@code BookingControllerTest}'s security scaffolding exactly: a
 * {@code @TestConfiguration} inner class enabling method security with a stateless
 * {@link JwtAuthenticationFilter} pass-through, and {@code authentication()} injected directly
 * (never {@code @WithMockUser}, which never populates {@code getDetails()}).
 */
@WebMvcTest(AppointmentController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("AppointmentController — @WebMvcTest slice (whole-visit + per-item reschedule/cancel, "
        + "phase 30.5/30.6, cycle-5 finding 2)")
class AppointmentControllerTest {

    private static final String BASE_URL = "/api/v1/appointments";

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

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AppointmentService appointmentService;
    @MockBean private AppointmentTransitionService appointmentTransitionService;
    @MockBean private BookingService bookingService;
    @MockBean(name = "authz") private AuthorizationService authorizationService;
    @MockBean private JwtTokenProvider jwtTokenProvider;

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private String rescheduleUrl(UUID appointmentId, UUID bookingId) {
        return BASE_URL + "/" + appointmentId + "/services/" + bookingId + "/reschedule";
    }

    private String wholeVisitRescheduleUrl(UUID appointmentId) {
        return BASE_URL + "/" + appointmentId + "/reschedule";
    }

    private String cancelUrl(UUID appointmentId, UUID bookingId) {
        return BASE_URL + "/" + appointmentId + "/services/" + bookingId + "/cancel";
    }

    private AppointmentDetailResponse stubDetailResponse(UUID appointmentId) {
        return new AppointmentDetailResponse(
                appointmentId, BookingStatus.CONFIRMED,
                UUID.randomUUID(), "Oksana", "Kovalenko", null,
                "https://cdn.test/avatar.png", Role.INDEPENDENT_MASTER, null,
                java.time.ZonedDateTime.now().plusDays(1),
                java.time.ZonedDateTime.now().plusDays(1).plusHours(2),
                120, new java.math.BigDecimal("1400.00"), null,
                null, java.time.OffsetDateTime.now(), List.of(),
                null, null, "Kyiv", null, null, null, null);
    }

    // ── PATCH /{appointmentId}/services/{bookingId}/reschedule (phase 30.5) ────────────────────

    @Test
    @DisplayName("reschedule item — CLIENT → 200, service invoked with Role.CLIENT (role-only "
            + "@PreAuthorize, perf audit F1: canRescheduleAppointment is no longer part of this "
            + "route's SpEL at all, not merely short-circuited)")
    void should_return200_when_clientReschedulesItem() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        when(appointmentTransitionService.rescheduleAppointmentItem(
                eq(clientId), eq(Role.CLIENT), eq(appointmentId), eq(bookingId), any()))
                .thenReturn(stubDetailResponse(appointmentId));

        mockMvc.perform(patch(rescheduleUrl(appointmentId, bookingId))
                        .with(authenticatedAs(clientId, "client@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isOk());

        verify(appointmentTransitionService).rescheduleAppointmentItem(
                eq(clientId), eq(Role.CLIENT), eq(appointmentId), eq(bookingId), any());
    }

    @Test
    @DisplayName("reschedule item — SALON_OWNER role → 200 (perf audit F1: the controller only gates "
            + "the ROLE now; provider authority over the visit is enforced INSIDE the service via "
            + "AuthorizationService#enforceCanManageAppointment, mocked away in this slice)")
    void should_return200_when_salonOwnerRoleReschedulesItem() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(appointmentTransitionService.rescheduleAppointmentItem(
                eq(ownerId), eq(Role.SALON_OWNER), eq(appointmentId), eq(bookingId), any()))
                .thenReturn(stubDetailResponse(appointmentId));

        mockMvc.perform(patch(rescheduleUrl(appointmentId, bookingId))
                        .with(authenticatedAs(ownerId, "owner@example.com", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isOk());

        verify(appointmentTransitionService).rescheduleAppointmentItem(
                eq(ownerId), eq(Role.SALON_OWNER), eq(appointmentId), eq(bookingId), any());
        verify(authorizationService, never()).canRescheduleAppointment(any(), any());
    }

    @Test
    @DisplayName("reschedule item — INDEPENDENT_MASTER role → 200 (same role-only gate as SALON_OWNER)")
    void should_return200_when_independentMasterRoleReschedulesItem() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        when(appointmentTransitionService.rescheduleAppointmentItem(
                eq(masterId), eq(Role.INDEPENDENT_MASTER), eq(appointmentId), eq(bookingId), any()))
                .thenReturn(stubDetailResponse(appointmentId));

        mockMvc.perform(patch(rescheduleUrl(appointmentId, bookingId))
                        .with(authenticatedAs(masterId, "master@example.com", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("reschedule item — SALON_OWNER without authority over the visit → 403 (perf audit "
            + "F1: this is now decided EXCLUSIVELY inside AppointmentTransitionService via "
            + "enforceCanManageAppointment, never by the controller's @PreAuthorize, which is "
            + "role-only here — same behavioural outcome as before, one fewer query)")
    void should_return403_when_unauthorizedSalonOwnerReschedulesItem() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(appointmentTransitionService.rescheduleAppointmentItem(
                eq(ownerId), eq(Role.SALON_OWNER), eq(appointmentId), eq(bookingId), any()))
                .thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(patch(rescheduleUrl(appointmentId, bookingId))
                        .with(authenticatedAs(ownerId, "owner@example.com", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isForbidden());

        verify(appointmentTransitionService).rescheduleAppointmentItem(
                eq(ownerId), eq(Role.SALON_OWNER), eq(appointmentId), eq(bookingId), any());
    }

    @Test
    @DisplayName("reschedule item — SALON_MASTER → 403, and the service is NEVER invoked (role "
            + "fast-path rejects before the controller even calls into AppointmentTransitionService, "
            + "perf audit F1)")
    void should_return403_when_salonMasterReschedulesItem() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        mockMvc.perform(patch(rescheduleUrl(appointmentId, bookingId))
                        .with(authenticatedAs(UUID.randomUUID(), "salonmaster@example.com", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isForbidden());

        verify(authorizationService, never()).canRescheduleAppointment(any(), any());
        verify(appointmentTransitionService, never()).rescheduleAppointmentItem(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reschedule item — unauthenticated → 401")
    void should_return401_when_unauthenticatedReschedulesItem() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        mockMvc.perform(patch(rescheduleUrl(appointmentId, bookingId))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reschedule item — null newStartsAt → 400, service never invoked")
    void should_return400_when_newStartsAtIsNull() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        mockMvc.perform(patch(rescheduleUrl(appointmentId, bookingId))
                        .with(authenticatedAs(UUID.randomUUID(), "client@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":null}"))
                .andExpect(status().isBadRequest());

        verify(appointmentTransitionService, never()).rescheduleAppointmentItem(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reschedule item — newStartsAt in the past → 400, service never invoked")
    void should_return400_when_newStartsAtIsInThePast() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        mockMvc.perform(patch(rescheduleUrl(appointmentId, bookingId))
                        .with(authenticatedAs(UUID.randomUUID(), "client@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().minusDays(1) + "\"}"))
                .andExpect(status().isBadRequest());

        verify(appointmentTransitionService, never()).rescheduleAppointmentItem(any(), any(), any(), any(), any());
    }

    // ── PATCH /{appointmentId}/reschedule — WHOLE VISIT (cycle-5 audit finding 2) ──────────────

    @Test
    @DisplayName("whole-visit reschedule — CLIENT → 200, service invoked with Role.CLIENT (the "
            + "provider arm — and therefore canRescheduleAppointment — is never evaluated for a "
            + "CLIENT principal; SpEL 'or' short-circuits)")
    void should_return200_when_clientReschedulesWholeVisit() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        when(appointmentTransitionService.rescheduleAppointment(
                eq(clientId), eq(Role.CLIENT), eq(appointmentId), any()))
                .thenReturn(stubDetailResponse(appointmentId));

        mockMvc.perform(patch(wholeVisitRescheduleUrl(appointmentId))
                        .with(authenticatedAs(clientId, "client@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isOk());

        verify(appointmentTransitionService).rescheduleAppointment(
                eq(clientId), eq(Role.CLIENT), eq(appointmentId), any());
        verify(authorizationService, never()).canRescheduleAppointment(any(), any());
    }

    @Test
    @DisplayName("whole-visit reschedule — SALON_OWNER role → 200 (cycle-5 audit finding 2: the "
            + "controller only gates the ROLE now; provider authority over the visit is enforced "
            + "INSIDE the service via AuthorizationService#enforceCanRescheduleBooking, mocked away "
            + "in this slice — canRescheduleAppointment's own findAllCompletionAccessByAppointmentId "
            + "query is never run, closing the double round-trip the finding flagged)")
    void should_return200_when_salonOwnerRoleReschedulesWholeVisit() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(appointmentTransitionService.rescheduleAppointment(
                eq(ownerId), eq(Role.SALON_OWNER), eq(appointmentId), any()))
                .thenReturn(stubDetailResponse(appointmentId));

        mockMvc.perform(patch(wholeVisitRescheduleUrl(appointmentId))
                        .with(authenticatedAs(ownerId, "owner@example.com", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isOk());

        verify(appointmentTransitionService).rescheduleAppointment(
                eq(ownerId), eq(Role.SALON_OWNER), eq(appointmentId), any());
        verify(authorizationService, never()).canRescheduleAppointment(any(), any());
    }

    @Test
    @DisplayName("whole-visit reschedule — INDEPENDENT_MASTER role → 200 (same role-only gate as "
            + "SALON_OWNER)")
    void should_return200_when_independentMasterRoleReschedulesWholeVisit() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        when(appointmentTransitionService.rescheduleAppointment(
                eq(masterId), eq(Role.INDEPENDENT_MASTER), eq(appointmentId), any()))
                .thenReturn(stubDetailResponse(appointmentId));

        mockMvc.perform(patch(wholeVisitRescheduleUrl(appointmentId))
                        .with(authenticatedAs(masterId, "master@example.com", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isOk());

        verify(authorizationService, never()).canRescheduleAppointment(any(), any());
    }

    @Test
    @DisplayName("whole-visit reschedule — SALON_OWNER without authority over the visit → 403 "
            + "(cycle-5 audit finding 2: decided EXCLUSIVELY inside AppointmentTransitionService via "
            + "AuthorizationService#enforceCanRescheduleBooking, never by the controller's "
            + "@PreAuthorize, which is role-only here — same behavioural outcome as before, one "
            + "fewer query)")
    void should_return403_when_unauthorizedSalonOwnerReschedulesWholeVisit() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(appointmentTransitionService.rescheduleAppointment(
                eq(ownerId), eq(Role.SALON_OWNER), eq(appointmentId), any()))
                .thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(patch(wholeVisitRescheduleUrl(appointmentId))
                        .with(authenticatedAs(ownerId, "owner@example.com", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isForbidden());

        verify(appointmentTransitionService).rescheduleAppointment(
                eq(ownerId), eq(Role.SALON_OWNER), eq(appointmentId), any());
        verify(authorizationService, never()).canRescheduleAppointment(any(), any());
    }

    @Test
    @DisplayName("whole-visit reschedule — SALON_MASTER → 403, and the service is NEVER invoked "
            + "(role fast-path rejects before the controller even calls into "
            + "AppointmentTransitionService — SALON_MASTER stays excluded from hasAnyRole exactly "
            + "as before this fix)")
    void should_return403_when_salonMasterReschedulesWholeVisit() throws Exception {
        UUID appointmentId = UUID.randomUUID();

        mockMvc.perform(patch(wholeVisitRescheduleUrl(appointmentId))
                        .with(authenticatedAs(UUID.randomUUID(), "salonmaster@example.com", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isForbidden());

        verify(authorizationService, never()).canRescheduleAppointment(any(), any());
        verify(appointmentTransitionService, never()).rescheduleAppointment(any(), any(), any(), any());
    }

    @Test
    @DisplayName("whole-visit reschedule — unauthenticated → 401")
    void should_return401_when_unauthenticatedReschedulesWholeVisit() throws Exception {
        UUID appointmentId = UUID.randomUUID();

        mockMvc.perform(patch(wholeVisitRescheduleUrl(appointmentId))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":\"" + OffsetDateTime.now().plusDays(1) + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("whole-visit reschedule — null newStartsAt → 400, service never invoked")
    void should_return400_when_wholeVisitNewStartsAtIsNull() throws Exception {
        UUID appointmentId = UUID.randomUUID();

        mockMvc.perform(patch(wholeVisitRescheduleUrl(appointmentId))
                        .with(authenticatedAs(UUID.randomUUID(), "client@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"newStartsAt\":null}"))
                .andExpect(status().isBadRequest());

        verify(appointmentTransitionService, never()).rescheduleAppointment(any(), any(), any(), any());
    }

    // ── PATCH /{appointmentId}/services/{bookingId}/cancel (phase 30.6) ────────────────────────

    @Test
    @DisplayName("cancel item — CLIENT → 204, delegates to BookingService#cancelAppointmentItem")
    void should_return204_when_clientCancelsItem() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, "не потрібно");

        mockMvc.perform(patch(cancelUrl(appointmentId, bookingId))
                        .with(authenticatedAs(clientId, "client@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(bookingService).cancelAppointmentItem(clientId, appointmentId, bookingId, req);
    }

    @Test
    @DisplayName("cancel item — SALON_OWNER → 403 (CLIENT-only route; provider uses the decline route instead)")
    void should_return403_when_salonOwnerCancelsItem() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);

        mockMvc.perform(patch(cancelUrl(appointmentId, bookingId))
                        .with(authenticatedAs(UUID.randomUUID(), "owner@example.com", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verify(bookingService, never()).cancelAppointmentItem(any(), any(), any(), any());
    }

    @Test
    @DisplayName("cancel item — SALON_MASTER → 403")
    void should_return403_when_salonMasterCancelsItem() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);

        mockMvc.perform(patch(cancelUrl(appointmentId, bookingId))
                        .with(authenticatedAs(UUID.randomUUID(), "salonmaster@example.com", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verify(bookingService, never()).cancelAppointmentItem(any(), any(), any(), any());
    }

    @Test
    @DisplayName("cancel item — missing cancellationReason → 400, service never invoked")
    void should_return400_when_cancellationReasonMissing() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        mockMvc.perform(patch(cancelUrl(appointmentId, bookingId))
                        .with(authenticatedAs(UUID.randomUUID(), "client@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"comment\":\"не потрібно\"}"))
                .andExpect(status().isBadRequest());

        verify(bookingService, never()).cancelAppointmentItem(any(), any(), any(), any());
    }

    @Test
    @DisplayName("cancel item — unauthenticated → 401")
    void should_return401_when_unauthenticatedCancelsItem() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        CancelBookingRequest req = new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);

        mockMvc.perform(patch(cancelUrl(appointmentId, bookingId))
                        .with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }
}
