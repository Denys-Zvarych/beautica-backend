package com.beautica.booking.controller;

import com.beautica.booking.service.BookingService;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.security.AuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.List;
import java.time.ZonedDateTime;
import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.config.WebMvcTestSupport;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.data.domain.Page;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("BookingController — @WebMvcTest slice")
class BookingControllerTest {

    private static final Logger log = LoggerFactory.getLogger(BookingControllerTest.class);
    private static final String BOOKINGS_URL = "/api/v1/bookings";

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
    @MockBean private BookingService bookingService;
    @MockBean(name = "authz") private AuthorizationService authorizationService;
    @MockBean private JwtTokenProvider jwtTokenProvider;

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private BookingResponse stubResponse(UUID bookingId, UUID clientId, UUID masterId, UUID serviceId) {
        return new BookingResponse(
                bookingId, clientId, masterId, serviceId, "Manicure",
                BookingStatus.PENDING,
                ZonedDateTime.now().plusDays(1),
                ZonedDateTime.now().plusDays(1).plusMinutes(60),
                new BigDecimal("500.00"), 60,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private BookingDetailResponse stubDetailResponse(UUID bookingId, UUID clientId, UUID masterId, UUID serviceId) {
        return new BookingDetailResponse(
                bookingId, clientId, masterId, serviceId, "Manicure",
                BookingStatus.PENDING,
                ZonedDateTime.now().plusDays(1),
                ZonedDateTime.now().plusDays(1).plusMinutes(60),
                new BigDecimal("500.00"), 60,
                OffsetDateTime.now(ZoneOffset.UTC),
                "Oksana", "Kovalenko", "Natalia", "Lysenko", null, null
        );
    }

    // ── POST / ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST / — 201 when CLIENT submits valid booking request")
    void should_return201_when_validCreateBookingRequest() throws Exception {
        var clientId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateBookingRequest(masterId, serviceId, ZonedDateTime.now().plusDays(1), null));
        when(bookingService.createBooking(eq(clientId), any(), any()))
                .thenReturn(stubResponse(bookingId, clientId, masterId, serviceId));

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST / — 403 when SALON_OWNER attempts to book")
    void should_return403_when_ownerTriesToCreateBooking() throws Exception {
        var ownerId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), ZonedDateTime.now().plusDays(1), null));

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST / — 401 when no Authorization header")
    void should_return401_when_noTokenOnCreateBooking() throws Exception {
        var body = objectMapper.writeValueAsString(
                new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), ZonedDateTime.now().plusDays(1), null));

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST / — 409 when the requested time is no longer available")
    void should_return409_when_slotAlreadyTaken() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), ZonedDateTime.now().plusDays(1), null));
        when(bookingService.createBooking(any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "Time slot not available"));

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST / — 400 when masterId is absent")
    void should_return400_when_missingRequiredFields() throws Exception {
        var clientId = UUID.randomUUID();
        var body = "{\"masterServiceId\":\"" + UUID.randomUUID() + "\",\"startsAt\":\"2027-01-01T10:00:00+02:00\"}";

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST / — 400 when startsAt is in the past (@Future constraint)")
    void should_return400_when_startsAtIsInThePast() throws Exception {
        var clientId = UUID.randomUUID();
        var body = "{\"masterId\":\"" + UUID.randomUUID()
                + "\",\"masterServiceId\":\"" + UUID.randomUUID()
                + "\",\"startsAt\":\"2000-01-01T10:00:00+02:00\"}";

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST / — 400 when idempotencyKey contains invalid characters (@Pattern constraint)")
    void should_return400_when_idempotencyKeyHasInvalidFormat() throws Exception {
        var clientId = UUID.randomUUID();
        var body = "{\"masterId\":\"" + UUID.randomUUID()
                + "\",\"masterServiceId\":\"" + UUID.randomUUID()
                + "\",\"startsAt\":\"2027-01-01T10:00:00+02:00\""
                + ",\"idempotencyKey\":\"invalid key with spaces\"}";

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── GET /{bookingId} ─────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /{bookingId} — 200 when caller is authorized to view the booking")
    void should_return200_when_authorizedGetBooking() throws Exception {
        var userId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.getBooking(eq(userId), eq(bookingId)))
                .thenReturn(stubDetailResponse(bookingId, userId, UUID.randomUUID(), UUID.randomUUID()));

        mockMvc.perform(get(BOOKINGS_URL + "/" + bookingId)
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(bookingId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /{bookingId} — 403 when caller is not authorized")
    void should_return403_when_unauthorizedGetBooking() throws Exception {
        var userId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.getBooking(any(), any())).thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(get(BOOKINGS_URL + "/" + bookingId)
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ── PATCH /{bookingId}/confirm ────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /{bookingId}/confirm — 204 when salon owner confirms booking")
    void should_return204_when_ownerConfirmsBooking() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.confirmBooking(any(), eq(bookingId))).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/confirm")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/confirm — 204 when SALON_MASTER confirms booking")
    void should_return204_when_salonMasterConfirmsBooking() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.confirmBooking(any(), eq(bookingId))).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/confirm")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.SALON_MASTER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/confirm — 403 when CLIENT tries to confirm")
    void should_return403_when_clientConfirmsBooking() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.confirmBooking(any(), eq(bookingId)))
                .thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/confirm")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── PATCH /{bookingId}/cancel ─────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /{bookingId}/cancel — 204 when CLIENT cancels their own booking")
    void should_return204_when_clientCancelsBooking() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.cancelBooking(any(), eq(bookingId), any())).thenReturn(null);
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, null));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/cancel")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/confirm — 400 when service throws on invalid transition")
    void should_return400_when_invalidStatusTransition() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.confirmBooking(any(), eq(bookingId)))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST, "Invalid status transition"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/confirm")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // ── PATCH /{bookingId}/decline ────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 204 when authorized SALON_OWNER declines booking")
    void should_return204_when_authorizedDeclineBooking() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, null));
        when(bookingService.declineBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 204 when SALON_MASTER declines booking")
    void should_return204_when_salonMasterDeclinesBooking() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, null));
        when(bookingService.declineBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 403 when CLIENT attempts to decline booking")
    void should_return403_when_clientDeclinesBooking() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, null));
        when(bookingService.declineBooking(any(), eq(bookingId), any()))
                .thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ── PATCH /{bookingId}/complete ───────────────────────────────────────────

    @Test
    @DisplayName("PATCH /{bookingId}/complete — 204 when authorized SALON_OWNER completes booking")
    void should_return204_when_authorizedCompleteBooking() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.completeBooking(any(), eq(bookingId))).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/complete")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/complete — 204 when SALON_MASTER completes booking")
    void should_return204_when_salonMasterCompletesBooking() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.completeBooking(any(), eq(bookingId))).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/complete")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.SALON_MASTER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/complete — 403 when CLIENT attempts to complete booking")
    void should_return403_when_clientCompletesBooking() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.completeBooking(any(), eq(bookingId)))
                .thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/complete")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── PATCH /{bookingId}/not-complete ──────────────────────────────────────

    @Test
    @DisplayName("PATCH /{bookingId}/not-complete — 204 when authorized SALON_OWNER marks not-completed")
    void should_return204_when_authorizedNotCompleteBooking() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, null));
        when(bookingService.notCompleteBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/not-complete — 204 when SALON_MASTER marks booking not-completed")
    void should_return204_when_salonMasterMarksNotCompleted() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, null));
        when(bookingService.notCompleteBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/not-complete — 403 when CLIENT attempts to mark not-completed")
    void should_return403_when_clientNotCompletesBooking() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, null));
        when(bookingService.notCompleteBooking(any(), eq(bookingId), any()))
                .thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ── PATCH /{bookingId}/cancel (additional) ────────────────────────────────

    @Test
    @DisplayName("PATCH /{bookingId}/cancel — 403 when SALON_OWNER attempts to cancel (role guard)")
    void should_return403_when_ownerTriesToCancelBooking() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, null));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/cancel")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/cancel — 403 when CLIENT tries to cancel another client's booking")
    void should_return403_when_clientCancelsAnotherClientsBooking() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, null));
        doThrow(new ForbiddenException("Access denied"))
                .when(bookingService).cancelBooking(any(), any(), any());

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/cancel")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ── GET /me ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /me — 200 when authenticated CLIENT lists their bookings")
    void should_return200_when_authenticatedListMyBookings() throws Exception {
        var clientId = UUID.randomUUID();
        when(bookingService.listBookings(any(), any(), any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /me — 200 and status param is forwarded to the service when ?status=PENDING is supplied")
    void should_return200_and_passStatusParam_when_statusQueryParamProvided() throws Exception {
        var clientId = UUID.randomUUID();
        when(bookingService.listBookings(any(), any(), eq(BookingStatus.PENDING), any())).thenReturn(Page.empty());

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .param("status", "PENDING")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(bookingService).listBookings(any(), any(), eq(BookingStatus.PENDING), any());
    }

    @Test
    @DisplayName("GET /me — 401 when no Authorization header")
    void should_return401_when_unauthenticatedListMyBookings() throws Exception {
        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

}
