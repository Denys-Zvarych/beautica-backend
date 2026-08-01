package com.beautica.booking.controller;

import com.beautica.booking.service.BookingService;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
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
import java.time.LocalDate;
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

    private BookingDetailResponse stubDetailResponse(UUID bookingId, UUID clientId, UUID masterId, UUID serviceId) {
        return new BookingDetailResponse(
                bookingId, clientId, masterId, serviceId, "Manicure",
                BookingStatus.CONFIRMED,
                ZonedDateTime.now().plusDays(1),
                ZonedDateTime.now().plusDays(1).plusMinutes(60),
                new BigDecimal("500.00"), null, 60,
                OffsetDateTime.now(ZoneOffset.UTC),
                "Oksana", "Kovalenko", "Natalia", "Lysenko",
                // masterProfessionalTitle (additive)
                "Перукар-стиліст",
                null, null,
                // Phase 25.8: clientCancellationNote (additive)
                null,
                // Phase 19.3 enrichment fields
                null, com.beautica.auth.Role.INDEPENDENT_MASTER, null,
                "Kyiv", null, null, null,
                // locationNote (additive)
                null,
                "MANICURE", false,
                // providerCanReviewClient (additive) — viewer-aware, always false in this
                // controller-slice fixture (BookingService is mocked; the value never reaches
                // real computation here)
                false,
                // appointmentId (BE-5 additive) — legacy single-service booking has no visit
                null,
                // clientAvatarUrl (additive) — the provider timeline's client photo
                "https://cdn.test/client-avatar.png"
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
                new CreateBookingRequest(masterId, serviceId, ZonedDateTime.now().plusDays(1), null, null));
        when(bookingService.createBooking(eq(clientId), any(), any()))
                .thenReturn(stubDetailResponse(bookingId, clientId, masterId, serviceId));

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                // Phase 19.3/25.x-equivalent — POST /bookings now returns the enriched
                // BookingDetailResponse instead of the lean BookingResponse (contract change
                // under test here), so the confirmation payload carries the master's name
                // without a follow-up GET /bookings/{id}.
                .andExpect(jsonPath("$.data.masterFirstName").value("Natalia"))
                .andExpect(jsonPath("$.data.masterLastName").value("Lysenko"));
    }

    @Test
    @DisplayName("POST / — 403 when SALON_OWNER attempts to book")
    void should_return403_when_ownerTriesToCreateBooking() throws Exception {
        var ownerId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), ZonedDateTime.now().plusDays(1), null, null));

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
                new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), ZonedDateTime.now().plusDays(1), null, null));

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
                new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), ZonedDateTime.now().plusDays(1), null, null));
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

    @Test
    @DisplayName("POST / — 400 when clientComment exceeds 1000 characters (@Size constraint)")
    void should_return400_when_clientCommentExceeds1000Chars() throws Exception {
        var clientId = UUID.randomUUID();
        var oversizedComment = "x".repeat(1001);
        var body = "{\"masterId\":\"" + UUID.randomUUID()
                + "\",\"masterServiceId\":\"" + UUID.randomUUID()
                + "\",\"startsAt\":\"2027-01-01T10:00:00+02:00\""
                + ",\"clientComment\":\"" + oversizedComment + "\"}";

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST / — 400 when Idempotency-Key header contains path-traversal characters")
    void should_return400_when_idempotencyKeyHeaderContainsInvalidChars() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), ZonedDateTime.now().plusDays(1), null, null));

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .header("Idempotency-Key", "../../../etc/passwd")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST / — 201 when Idempotency-Key header is absent")
    void should_return201_when_idempotencyKeyHeaderIsNull() throws Exception {
        var clientId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateBookingRequest(masterId, serviceId, ZonedDateTime.now().plusDays(1), null, null));
        when(bookingService.createBooking(eq(clientId), any(), any()))
                .thenReturn(stubDetailResponse(bookingId, clientId, masterId, serviceId));

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST / — 400 when Idempotency-Key header exceeds 64 characters")
    void should_return400_when_idempotencyKeyHeaderExceeds64Chars() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), ZonedDateTime.now().plusDays(1), null, null));
        var oversizedKey = "A".repeat(65);

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .header("Idempotency-Key", oversizedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST / — 400 when Idempotency-Key header contains control characters")
    void should_return400_when_idempotencyKeyHeaderContainsControlChars() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), ZonedDateTime.now().plusDays(1), null, null));

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .header("Idempotency-Key", "valid-prefix\tinvalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST / — 201 when Idempotency-Key header has exactly 64 characters")
    void should_return201_when_idempotencyKeyHeaderHasExactly64Chars() throws Exception {
        var clientId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var key64 = "A".repeat(64);
        var body = objectMapper.writeValueAsString(
                new CreateBookingRequest(masterId, serviceId, ZonedDateTime.now().plusDays(1), null, null));
        when(bookingService.createBooking(eq(clientId), any(), any()))
                .thenReturn(stubDetailResponse(bookingId, clientId, masterId, serviceId));

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .header("Idempotency-Key", key64)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
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
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
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

    @Test
    @DisplayName("GET /{bookingId} — 404 when booking does not exist")
    void should_return404_when_bookingDoesNotExist() throws Exception {
        var userId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.getBooking(any(), any()))
                .thenThrow(new NotFoundException("Booking not found"));

        mockMvc.perform(get(BOOKINGS_URL + "/" + bookingId)
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                // GlobalExceptionHandler returns a generic message (B3 hardening — no model leakage)
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // TODO(24.7): PATCH /{bookingId}/confirm no longer exists (track 24.x auto-confirm —
    // bookings are born CONFIRMED). backend-qa should add a test asserting the route 404s.

    // ── PATCH /{bookingId}/cancel ─────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /{bookingId}/cancel — 204 when CLIENT cancels their own booking")
    void should_return204_when_clientCancelsBooking() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(bookingService.cancelBooking(any(), eq(bookingId), any())).thenReturn(null);
        var body = objectMapper.writeValueAsString(new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/cancel")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 400 when service throws on invalid transition")
    void should_return400_when_invalidStatusTransition() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.declineBooking(any(), eq(bookingId), any()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST, "Invalid status transition"));
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, "Провайдер недоступний"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── PATCH /{bookingId}/decline ────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 204 when authorized SALON_OWNER declines booking")
    void should_return204_when_authorizedDeclineBooking() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, "Провайдер недоступний"));
        // Slice test: the @authz SpEL gate in @PreAuthorize is the predicate under test here —
        // the service ownership guard is mocked out, so admit this owner explicitly.
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.declineBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Phase 25.9: PATCH /{bookingId}/decline — 204 when comment is absent (the note is "
            + "optional for all roles, reverses Phase 25.2's required-comment decision)")
    void should_return204_when_declineCalledWithNoComment() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null));
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.declineBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 204 when INDEPENDENT_MASTER declines booking")
    void should_return204_when_independentMasterDeclinesBooking() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, "Провайдер недоступний"));
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.declineBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    // Phase 24.2: SALON_ADMIN now passes the controller role gate (hasAnyRole(...,'SALON_ADMIN',...))
    // — this exercises the "role admitted but @authz.canCancelBooking denies" path (e.g. an admin
    // NOT assigned to the booking's salon), not a blanket SALON_ADMIN denial.
    // TODO(24.7): add the full provider-cancel authz matrix (assigned SALON_ADMIN ✅ 204,
    // unassigned SALON_ADMIN ❌ 403) — see phase 24.2/24.7 plan.
    @Test
    @DisplayName("PATCH /{bookingId}/decline — 403 when SALON_MASTER attempts to decline booking")
    void should_return403_when_salonMasterDeclinesBooking() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, "Тестовий коментар"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 403 when SALON_ADMIN is not assigned to the booking's salon")
    void should_return403_when_salonAdminAttemptsToDecline() throws Exception {
        var adminId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, "Тестовий коментар"));
        // Not stubbed → defaults false: an unassigned SALON_ADMIN fails @authz.canCancelBooking.
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(false);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(adminId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 403 when CLIENT attempts to decline booking")
    void should_return403_when_clientDeclinesBooking() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, "Тестовий коментар"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 400 when cancellationReason is absent")
    void should_return400_when_declineCalledWithoutReason() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, "Тестовий коментар"));
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.declineBooking(any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST,
                        "Cancellation reason required for declining a booking"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── PATCH /{bookingId}/complete ───────────────────────────────────────────

    @Test
    @DisplayName("PATCH /{bookingId}/complete — 204 when authorized SALON_OWNER completes booking")
    void should_return204_when_authorizedCompleteBooking() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        // Slice test: the @authz SpEL gate in @PreAuthorize is the predicate under test here —
        // the service ownership guard is mocked out, so admit this owner explicitly.
        when(authorizationService.canCompleteBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.completeBooking(any(), eq(bookingId))).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/complete")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/complete — 204 when INDEPENDENT_MASTER completes booking")
    void should_return204_when_independentMasterCompletesBooking() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        // Slice test: the @authz SpEL gate in @PreAuthorize is the predicate under test here —
        // the service ownership guard is mocked out, so admit this master explicitly.
        when(authorizationService.canCompleteBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.completeBooking(any(), eq(bookingId))).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/complete")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/complete — 403 when SALON_MASTER attempts to complete booking")
    void should_return403_when_salonMasterCompletesBooking() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/complete")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.SALON_MASTER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/complete — 403 when SALON_ADMIN attempts to complete booking")
    void should_return403_when_salonAdminAttemptsToComplete() throws Exception {
        var adminId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/complete")
                        .with(authenticatedAs(adminId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf()))
                .andExpect(status().isForbidden());
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
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, "Тестовий коментар"));
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.notCompleteBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Phase 25.9: PATCH /{bookingId}/not-complete — 204 when comment is absent (the note "
            + "is optional for all roles, reverses Phase 25.2's required-comment decision)")
    void should_return204_when_notCompleteCalledWithNoComment() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, null));
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.notCompleteBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/not-complete — 204 when INDEPENDENT_MASTER marks booking not-completed")
    void should_return204_when_independentMasterMarksNotCompleted() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, "Клієнт не з'явився"));
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.notCompleteBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/not-complete — 403 when SALON_MASTER attempts to mark booking not-completed")
    void should_return403_when_salonMasterMarksNotCompleted() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, "Тестовий коментар"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // Phase 24.2: SALON_ADMIN passes the controller role gate. Unlike /decline, the /not-complete
    // annotation is role-only (§D — the redundant SpEL @authz.canCancelBooking ownership clause was
    // dropped), so ownership for an admin NOT assigned to the booking's salon is enforced by the
    // service-layer @authz.enforceCanCancelBooking guard in BookingService#notCompleteBooking. Here
    // that mocked service throws ForbiddenException, which the controller propagates as 403.
    // TODO(24.7): add the assigned-SALON_ADMIN ✅ 204 case to the authz matrix.
    @Test
    @DisplayName("PATCH /{bookingId}/not-complete — 403 when SALON_ADMIN is not assigned to the booking's salon")
    void should_return403_when_salonAdminAttemptsToNotComplete() throws Exception {
        var adminId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, "Тестовий коментар"));
        when(bookingService.notCompleteBooking(any(), eq(bookingId), any()))
                .thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(adminId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/not-complete — 403 when CLIENT attempts to mark not-completed")
    void should_return403_when_clientNotCompletesBooking() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, "Тестовий коментар"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/not-complete — 400 when cancellationReason is absent")
    void should_return400_when_notCompleteCalledWithoutReason() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new StatusUpdateRequest(null, "Тестовий коментар"));
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.notCompleteBooking(any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST, "Cancellation reason required"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── PATCH /{bookingId}/cancel (additional) ────────────────────────────────

    @Test
    @DisplayName("PATCH /{bookingId}/cancel — 400 when cancellationReason is missing from request body")
    void should_return400_when_cancelRequestMissingReason() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = "{\"cancellationReason\":null}";

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/cancel")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/cancel — 403 when SALON_OWNER attempts to cancel (role guard)")
    void should_return403_when_ownerTriesToCancelBooking() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null));

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
        var body = objectMapper.writeValueAsString(new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null));
        doThrow(new ForbiddenException("Access denied"))
                .when(bookingService).cancelBooking(any(), any(), any());

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/cancel")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ── PATCH /{bookingId}/reschedule (Phase 19.2) ───────────────────────────

    @Test
    @DisplayName("PATCH /{bookingId}/reschedule — 200 when CLIENT reschedules and the principal id (not the body) is the actor")
    void should_return200_andUsePrincipalAsActor_when_clientReschedules() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var newStartsAt = ZonedDateTime.now().plusDays(2).toOffsetDateTime();
        var body = objectMapper.writeValueAsString(new RescheduleBookingRequest(newStartsAt));
        when(bookingService.rescheduleBooking(eq(clientId), eq(Role.CLIENT), eq(bookingId), any()))
                .thenReturn(stubDetailResponse(bookingId, clientId, UUID.randomUUID(), UUID.randomUUID()));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(bookingId.toString()));

        // Actor id handed to the service is the security principal's id — never derived from the body.
        var actorCaptor = org.mockito.ArgumentCaptor.forClass(UUID.class);
        org.mockito.Mockito.verify(bookingService)
                .rescheduleBooking(actorCaptor.capture(), eq(Role.CLIENT), eq(bookingId), any());
        org.assertj.core.api.Assertions.assertThat(actorCaptor.getValue()).isEqualTo(clientId);
    }

    /**
     * Phase 27.2 (REVERSES the previously-locked "reschedule is client-only" decision): the
     * provider arm of the union {@code @PreAuthorize} ({@code hasAnyRole(...) and
     * @authz.canRescheduleBooking(...)}) now grants a SALON_OWNER with authority over the booking.
     */
    @Test
    @DisplayName("Phase 27.2: PATCH /{bookingId}/reschedule — 200 when an AUTHORIZED SALON_OWNER reschedules "
            + "(reverses the previously-locked client-only decision)")
    void should_return200_when_authorizedOwnerReschedules() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new RescheduleBookingRequest(ZonedDateTime.now().plusDays(2).toOffsetDateTime()));
        when(authorizationService.canRescheduleBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.rescheduleBooking(eq(ownerId), eq(Role.SALON_OWNER), eq(bookingId), any()))
                .thenReturn(stubDetailResponse(bookingId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(bookingService)
                .rescheduleBooking(eq(ownerId), eq(Role.SALON_OWNER), eq(bookingId), any());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/reschedule — 403 when a SALON_OWNER with NO authority over the booking "
            + "attempts to reschedule (canRescheduleBooking denies — service is never reached)")
    void should_return403_when_unauthorizedOwnerAttemptsToReschedule() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new RescheduleBookingRequest(ZonedDateTime.now().plusDays(2).toOffsetDateTime()));
        when(authorizationService.canRescheduleBooking(any(), eq(bookingId))).thenReturn(false);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never())
                .rescheduleBooking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Phase 27.2: PATCH /{bookingId}/reschedule — 200 when an AUTHORIZED INDEPENDENT_MASTER "
            + "reschedules their own booking")
    void should_return200_when_authorizedIndependentMasterReschedules() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new RescheduleBookingRequest(ZonedDateTime.now().plusDays(2).toOffsetDateTime()));
        when(authorizationService.canRescheduleBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.rescheduleBooking(eq(masterId), eq(Role.INDEPENDENT_MASTER), eq(bookingId), any()))
                .thenReturn(stubDetailResponse(bookingId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(bookingService)
                .rescheduleBooking(eq(masterId), eq(Role.INDEPENDENT_MASTER), eq(bookingId), any());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/reschedule — 403 when an INDEPENDENT_MASTER with NO authority over the "
            + "booking attempts to reschedule (a foreign master's booking)")
    void should_return403_when_unauthorizedMasterAttemptsToReschedule() throws Exception {
        var masterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new RescheduleBookingRequest(ZonedDateTime.now().plusDays(2).toOffsetDateTime()));
        when(authorizationService.canRescheduleBooking(any(), eq(bookingId))).thenReturn(false);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never())
                .rescheduleBooking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/reschedule — 403 when a SALON_MASTER (read-only role) attempts to reschedule")
    void should_return403_when_salonMasterAttemptsToReschedule() throws Exception {
        var salonMasterId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new RescheduleBookingRequest(ZonedDateTime.now().plusDays(2).toOffsetDateTime()));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule")
                        .with(authenticatedAs(salonMasterId, "salonmaster@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        // SALON_MASTER matches neither hasRole('CLIENT') nor the provider hasAnyRole(...) set, so
        // the SpEL denies before @authz.canRescheduleBooking is ever evaluated.
        org.mockito.Mockito.verify(authorizationService, org.mockito.Mockito.never())
                .canRescheduleBooking(any(), any());
        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never())
                .rescheduleBooking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/reschedule — 401 when no Authorization header")
    void should_return401_when_noTokenOnReschedule() throws Exception {
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new RescheduleBookingRequest(ZonedDateTime.now().plusDays(2).toOffsetDateTime()));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/reschedule — 400 when newStartsAt is in the past (@Future constraint)")
    void should_return400_when_rescheduleNewStartsAtInPast() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = "{\"newStartsAt\":\"2000-01-01T10:00:00+02:00\"}";

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never())
                .rescheduleBooking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/reschedule — 400 when newStartsAt is absent (@NotNull constraint)")
    void should_return400_when_rescheduleNewStartsAtMissing() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = "{}";

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/reschedule — 409 when the service reports a conflicting slot or invalid state")
    void should_return409_when_rescheduleConflicts() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new RescheduleBookingRequest(ZonedDateTime.now().plusDays(2).toOffsetDateTime()));
        when(bookingService.rescheduleBooking(any(), eq(Role.CLIENT), eq(bookingId), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "Slot not available"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/reschedule — 403 when CLIENT reschedules another client's booking (service ownership guard)")
    void should_return403_when_clientReschedulesAnotherClientsBooking() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new RescheduleBookingRequest(ZonedDateTime.now().plusDays(2).toOffsetDateTime()));
        when(bookingService.rescheduleBooking(any(), eq(Role.CLIENT), eq(bookingId), any()))
                .thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule")
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
        when(bookingService.getMyBookings(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(com.beautica.common.PageResponse.of(java.util.List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /me — 200 and status param is forwarded to the service as a 1-element list "
            + "when a single ?status=CANCELLED is supplied (Phase 26.1 backward compatibility)")
    void should_return200_and_passStatusParam_when_statusQueryParamProvided() throws Exception {
        var clientId = UUID.randomUUID();
        when(bookingService.getMyBookings(any(), any(), eq(java.util.List.of(BookingStatus.CANCELLED)), any(), any(), any(), any(), any()))
                .thenReturn(com.beautica.common.PageResponse.of(java.util.List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .param("status", "CANCELLED")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(bookingService)
                .getMyBookings(any(), any(), eq(java.util.List.of(BookingStatus.CANCELLED)), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /me — repeated ?status=A&status=B binds to a 2-element list forwarded to the service (Phase 26.1)")
    void should_return200_and_passMultiStatusParam_when_repeatedStatusQueryParamProvided() throws Exception {
        var clientId = UUID.randomUUID();
        when(bookingService.getMyBookings(any(), any(), eq(java.util.List.of(BookingStatus.CANCELLED, BookingStatus.DECLINED)), any(), any(), any(), any(), any()))
                .thenReturn(com.beautica.common.PageResponse.of(java.util.List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .param("status", "CANCELLED", "DECLINED")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(bookingService).getMyBookings(any(), any(), eq(java.util.List.of(BookingStatus.CANCELLED, BookingStatus.DECLINED)), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /me — the actor id passed to the service is the security principal, never a client-supplied value")
    void should_usePrincipalAsActor_when_listingMyBookings() throws Exception {
        var principalId = UUID.randomUUID();
        when(bookingService.getMyBookings(eq(principalId), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(com.beautica.common.PageResponse.of(java.util.List.of(), 0, 20, 0L, 0));

        // An attacker-controlled "clientId" query param must be ignored — the actor is the principal.
        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .param("clientId", UUID.randomUUID().toString())
                        .with(authenticatedAs(principalId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        var actorCaptor = org.mockito.ArgumentCaptor.forClass(UUID.class);
        org.mockito.Mockito.verify(bookingService)
                .getMyBookings(actorCaptor.capture(), any(), any(), any(), any(), any(), any(), any());
        org.assertj.core.api.Assertions.assertThat(actorCaptor.getValue()).isEqualTo(principalId);
    }

    @Test
    @DisplayName("GET /me — 200 returns a PageResponse of enriched BookingDetailResponse rows (canReview + salonName + masterType in the body)")
    void should_returnEnrichedDetailRows_when_listMyBookingsNonEmpty() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var row = stubDetailResponse(bookingId, clientId, UUID.randomUUID(), UUID.randomUUID());
        when(bookingService.getMyBookings(eq(clientId), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(com.beautica.common.PageResponse.of(java.util.List.of(row), 0, 20, 1L, 1));

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.data[0].id").value(bookingId.toString()))
                .andExpect(jsonPath("$.data.data[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.data[0].masterType").value("INDEPENDENT_MASTER"))
                .andExpect(jsonPath("$.data.data[0].cityLabel").value("Kyiv"))
                .andExpect(jsonPath("$.data.data[0].masterProfessionalTitle").value("Перукар-стиліст"))
                .andExpect(jsonPath("$.data.data[0].canReview").value(false));
    }

    @Test
    @DisplayName("GET /me — 401 when no Authorization header")
    void should_return401_when_unauthenticatedListMyBookings() throws Exception {
        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /me — 200 when SALON_OWNER lists their bookings")
    void should_return200_when_salonOwnerListsBookings() throws Exception {
        var ownerId = UUID.randomUUID();
        when(bookingService.getMyBookings(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(com.beautica.common.PageResponse.of(java.util.List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /me — 200 when INDEPENDENT_MASTER lists their bookings")
    void should_return200_when_independentMasterListsBookings() throws Exception {
        var masterId = UUID.randomUUID();
        when(bookingService.getMyBookings(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(com.beautica.common.PageResponse.of(java.util.List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /me — Phase 26.6: a requested size above spring.data.web.pageable.max-page-size "
            + "(100) is CLAMPED to 100, not served as-is and not rejected with a 400 — pins Spring "
            + "Data's own clamping behavior against a regression of the application.yml property.")
    void should_clampPageSizeTo100_when_sizeExceedsMaxPageSize() throws Exception {
        var clientId = UUID.randomUUID();
        when(bookingService.getMyBookings(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(com.beautica.common.PageResponse.of(java.util.List.of(), 0, 100, 0L, 0));

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .param("size", "100000")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        var pageableCaptor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(bookingService)
                .getMyBookings(any(), any(), any(), any(), any(), any(), any(), pageableCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("GET /me — page=999999 still clamps to 1000 (Anti-Bug §J deep-OFFSET guard, "
            + "BookingController.java:127-130) — existing-cap regression pin, Phase 26.6")
    void should_clampPageNumberTo1000_when_pageExceeds1000() throws Exception {
        var clientId = UUID.randomUUID();
        when(bookingService.getMyBookings(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(com.beautica.common.PageResponse.of(java.util.List.of(), 1000, 20, 0L, 0));

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .param("page", "999999")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        var pageableCaptor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(bookingService)
                .getMyBookings(any(), any(), any(), any(), any(), any(), any(), pageableCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1000);
    }

    // ── GET /me/booked-days (Phase 26.5 — day-rail dot set) ──────────────────────

    @Test
    @DisplayName("GET /me/booked-days — 200, returns the distinct/ascending date list from the service")
    void should_return200_when_authenticatedListMyBookedDays() throws Exception {
        var clientId = UUID.randomUUID();
        when(bookingService.getMyBookedDays(any(), any(), any(), any()))
                .thenReturn(java.util.List.of(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 20)));

        mockMvc.perform(get(BOOKINGS_URL + "/me/booked-days")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0]").value("2026-07-05"))
                .andExpect(jsonPath("$.data[1]").value("2026-07-20"));
    }

    @Test
    @DisplayName("GET /me/booked-days — the actor id passed to the service is the security principal, "
            + "never a client-supplied value")
    void should_usePrincipalAsActor_when_listingMyBookedDays() throws Exception {
        var principalId = UUID.randomUUID();
        when(bookingService.getMyBookedDays(eq(principalId), any(), any(), any()))
                .thenReturn(java.util.List.of());

        mockMvc.perform(get(BOOKINGS_URL + "/me/booked-days")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31")
                        .with(authenticatedAs(principalId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        var actorCaptor = org.mockito.ArgumentCaptor.forClass(UUID.class);
        org.mockito.Mockito.verify(bookingService)
                .getMyBookedDays(actorCaptor.capture(), any(), any(), any());
        org.assertj.core.api.Assertions.assertThat(actorCaptor.getValue()).isEqualTo(principalId);
    }

    @Test
    @DisplayName("GET /me/booked-days — 401 when no Authorization header")
    void should_return401_when_unauthenticatedListMyBookedDays() throws Exception {
        mockMvc.perform(get(BOOKINGS_URL + "/me/booked-days")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /me/booked-days — 400 when 'from' is missing (range is required, unlike /me)")
    void should_return400_when_fromMissingForBookedDays() throws Exception {
        var clientId = UUID.randomUUID();

        mockMvc.perform(get(BOOKINGS_URL + "/me/booked-days")
                        .param("to", "2026-07-31")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never())
                .getMyBookedDays(any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /me/booked-days — 400 when 'to' is missing (range is required, unlike /me)")
    void should_return400_when_toMissingForBookedDays() throws Exception {
        var clientId = UUID.randomUUID();

        mockMvc.perform(get(BOOKINGS_URL + "/me/booked-days")
                        .param("from", "2026-07-01")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never())
                .getMyBookedDays(any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /{bookingId} still routes to getBooking and is not shadowed by /me/booked-days "
            + "(routing collision guard — /me vs /{bookingId} ambiguity is a live footgun here)")
    void should_routeToGetBooking_when_pathIsNotBookedDays() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var row = stubDetailResponse(bookingId, clientId, UUID.randomUUID(), UUID.randomUUID());
        when(bookingService.getBooking(eq(clientId), eq(bookingId))).thenReturn(row);

        mockMvc.perform(get(BOOKINGS_URL + "/" + bookingId)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(bookingId.toString()));

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never())
                .getMyBookedDays(any(), any(), any(), any());
    }

    // ── QA-CRITICAL: clientComment @Pattern — control-char guard ─────────────

    @Test
    @DisplayName("POST / — 400 when clientComment contains a control character (NUL byte)")
    void should_return400_when_clientCommentContainsControlCharacter() throws Exception {
        var clientId = UUID.randomUUID();
        //   is the NUL control character — matches \p{Cntrl} and must be rejected by @Pattern
        var body = "{\"masterId\":\"" + UUID.randomUUID()
                + "\",\"masterServiceId\":\"" + UUID.randomUUID()
                + "\",\"startsAt\":\"2027-01-01T10:00:00+02:00\""
                + ",\"clientComment\":\"valid prefix\\u0000embedded null\"}";

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never()).createBooking(any(), any(), any());
    }

    @Test
    @DisplayName("POST / — 201 when clientComment contains only printable Ukrainian text")
    void should_return201_when_clientCommentIsPrintableUkrainianText() throws Exception {
        var clientId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        // Ukrainian printable text — all chars are outside \p{Cntrl}, so @Pattern must pass
        var body = "{\"masterId\":\"" + masterId
                + "\",\"masterServiceId\":\"" + serviceId
                + "\",\"startsAt\":\"2027-01-01T10:00:00+02:00\""
                + ",\"clientComment\":\"Будь ласка, тихіше\"}";
        when(bookingService.createBooking(eq(clientId), any(), any()))
                .thenReturn(stubDetailResponse(bookingId, clientId, masterId, serviceId));

        mockMvc.perform(post(BOOKINGS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // ── QA-MEDIUM-1: enum validation — ?status param ─────────────────────────

    @Test
    @DisplayName("GET /me — 400 when ?status contains an unrecognised enum value")
    void should_return400_when_statusParamIsInvalidEnum() throws Exception {
        var clientId = UUID.randomUUID();

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .param("status", "GARBAGE")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── QA (Phase 26.4 audit): serviceId cap + malformed UUID ─────────────────

    @Test
    @DisplayName("GET /me — 400 (not 500), and the service is never invoked, when ?serviceId is "
            + "repeated 51 times — the controller's @Size(max = 50) must reject before the request "
            + "ever reaches BookingService.getMyBookings")
    void should_return400_when_serviceIdFilterExceeds50() throws Exception {
        var clientId = UUID.randomUUID();
        var params = new org.springframework.util.LinkedMultiValueMap<String, String>();
        for (int i = 0; i < 51; i++) {
            params.add("serviceId", UUID.randomUUID().toString());
        }

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .params(params)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never())
                .getMyBookings(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /me — exactly 50 ?serviceId values is accepted (200) — proves the bound is "
            + "\">50 rejects\", not \">=50 rejects\" (no off-by-one against the @Size(max = 50) cap)")
    void should_return200_when_serviceIdFilterIsExactly50() throws Exception {
        var clientId = UUID.randomUUID();
        var params = new org.springframework.util.LinkedMultiValueMap<String, String>();
        for (int i = 0; i < 50; i++) {
            params.add("serviceId", UUID.randomUUID().toString());
        }
        when(bookingService.getMyBookings(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(com.beautica.common.PageResponse.of(java.util.List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .params(params)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /me — 400 (not 500), and the service is never invoked, when ?serviceId is a "
            + "malformed UUID — the 400 body must NOT echo the malformed value back to the caller")
    void should_return400_when_serviceIdIsMalformedUuid() throws Exception {
        var clientId = UUID.randomUUID();

        var result = mockMvc.perform(get(BOOKINGS_URL + "/me")
                        .param("serviceId", "not-a-uuid")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .as("a MethodArgumentTypeMismatchException 400 must never echo the caller-supplied "
                        + "malformed value back into the response body")
                .doesNotContain("not-a-uuid");

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never())
                .getMyBookings(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── QA-MEDIUM-2: enum validation — decline cancellationReason ────────────

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 400 when cancellationReason is an unknown enum value")
    void should_return400_when_declineHasInvalidCancellationReasonEnum() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var body = "{\"cancellationReason\":\"INVALID_REASON\",\"comment\":\"Валідний коментар\"}";

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── QA-HIGH: CancelBookingRequest.comment @Pattern — control-char guard ───

    @Test
    @DisplayName("PATCH /{bookingId}/cancel — 400 when comment contains a control character (NUL byte)")
    void should_return400_when_cancelCommentContainsControlCharacter() throws Exception {
        var clientId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        // NUL byte in comment — @Pattern(^[^\p{Cntrl}]*$) must reject before service is reached
        var body = "{\"cancellationReason\":\"CLIENT_CANCELLED\""
                + ",\"comment\":\"valid prefix\\u0000embedded null\"}";

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/cancel")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never()).cancelBooking(any(), any(), any());
    }

    // ── QA-HIGH: StatusUpdateRequest.comment @Pattern — decline control-char guard

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 204 when comment contains a newline (multi-line note is allowed)")
    void should_return204_when_declineCommentContainsNewline() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        // Newline (0x0A) is whitespace, not a forbidden C0 control char — @Pattern must accept it
        var body = "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\""
                + ",\"comment\":\"line one\\nline two\"}";
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.declineBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/decline — 400 when comment contains a forbidden control character (NUL byte)")
    void should_return400_when_declineCommentContainsControlCharacter() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        // NUL byte (0x00) — a forbidden C0 control char, must be rejected at the controller boundary
        var body = "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\""
                + ",\"comment\":\"legit comment\\u0000embedded null\"}";

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/decline")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never()).declineBooking(any(), any(), any());
    }

    // ── QA-HIGH: StatusUpdateRequest.comment @Pattern — not-complete control-char guard

    @Test
    @DisplayName("PATCH /{bookingId}/not-complete — 204 when comment contains a tab (whitespace is allowed)")
    void should_return204_when_notCompleteCommentContainsTab() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        // Tab (0x09) is whitespace, not a forbidden C0 control char — @Pattern must accept it
        var body = "{\"cancellationReason\":\"CLIENT_NO_SHOW\""
                + ",\"comment\":\"no-show note\\ttab-separated\"}";
        when(authorizationService.canCancelBooking(any(), eq(bookingId))).thenReturn(true);
        when(bookingService.notCompleteBooking(any(), eq(bookingId), any())).thenReturn(null);

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /{bookingId}/not-complete — 400 when comment contains a forbidden control character (NUL byte)")
    void should_return400_when_notCompleteCommentContainsControlCharacter() throws Exception {
        var ownerId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        // NUL byte (0x00) — a forbidden C0 control char, must be rejected before the service is reached
        var body = "{\"cancellationReason\":\"CLIENT_NO_SHOW\""
                + ",\"comment\":\"no-show note\\u0000tab-injected\"}";

        mockMvc.perform(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete")
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        org.mockito.Mockito.verify(bookingService, org.mockito.Mockito.never()).notCompleteBooking(any(), any(), any());
    }

}
