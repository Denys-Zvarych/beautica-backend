package com.beautica.booking.controller;

import com.beautica.booking.domain.GuestCancelToken;
import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.BookingSlugInfoResponse;
import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.dto.GuestBookingRequest;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.booking.dto.SalonBookingSlugInfoResponse;
import com.beautica.booking.dto.SalonBookingSlugMasterItem;
import com.beautica.booking.dto.ServiceSummaryDto;
import com.beautica.booking.service.BookingCancellationService;
import com.beautica.booking.service.BookingSlugService;
import com.beautica.booking.service.GuestBookingService;
import com.beautica.booking.service.SalonBookingSlugService;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link PublicBookingController}.
 *
 * <p>The inner {@code @TestConfiguration} mirrors the production
 * {@code SecurityConfig} contract: {@code GET /api/v1/book/**} is
 * {@code permitAll()} and everything else is {@code authenticated()}. This also
 * guards against matcher over-broadening — a non-book GET must still be 401.
 */
@WebMvcTest(PublicBookingController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("PublicBookingController — @WebMvcTest slice")
class PublicBookingControllerTest {

    @TestConfiguration
    static class SecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/book/**").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/book/*/booking").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/book/cancel/*").permitAll()
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
    private BookingSlugService bookingSlugService;

    @MockBean
    private GuestBookingService guestBookingService;

    @MockBean
    private BookingCancellationService bookingCancellationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private SalonBookingSlugService salonBookingSlugService;

    @Test
    @DisplayName("GET /book/{slug}/info — 200 with master + services and NO Authorization header")
    void should_return200WithMasterAndServices_when_slugExists() throws Exception {
        var response = masterPage(
                "Марія Левченко",
                "https://cdn.example/a.jpg",
                "Манікюр та педикюр",
                List.of(new ServiceSummaryDto(UUID.randomUUID(), "Манікюр", 60, new BigDecimal("350.00"), "NAILS")));
        when(bookingSlugService.findBySlug("marija-l-cd34")).thenReturn(response);

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masterName").value("Марія Левченко"))
                .andExpect(jsonPath("$.avatarUrl").value("https://cdn.example/a.jpg"))
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].name").value("Манікюр"))
                .andExpect(jsonPath("$.services[0].durationMinutes").value(60))
                .andExpect(jsonPath("$.services[0].priceFrom").value(350.00));
    }

    @Test
    @DisplayName("GET /book/{slug}/info — public response carries no owner/master/user identifiers")
    void should_notLeakInternalIds_when_slugExists() throws Exception {
        var response = masterPage(
                "Ivan Petrenko", null, null,
                List.of(new ServiceSummaryDto(UUID.randomUUID(), "Стрижка", 45, new BigDecimal("200.00"), "HAIR")));
        when(bookingSlugService.findBySlug("ivan-petrenko-ab12")).thenReturn(response);

        mockMvc.perform(get("/api/v1/book/ivan-petrenko-ab12/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.services[0].ownerId").doesNotExist())
                .andExpect(jsonPath("$.services[0].ownerType").doesNotExist());
    }

    @Test
    @DisplayName("GET /book/{slug}/info — 404 when slug unknown")
    void should_return404_when_slugUnknown() throws Exception {
        when(bookingSlugService.findBySlug("unknown-slug"))
                .thenThrow(new NotFoundException("Booking page not found"));

        mockMvc.perform(get("/api/v1/book/unknown-slug/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /book/{slug}/info — 400 when slug violates the charset pattern (no DB hit)")
    void should_return400_when_slugMalformed() throws Exception {
        mockMvc.perform(get("/api/v1/book/Bad_Slug!/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingSlugService);
    }

    // ── Phase 13.3: availability ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /book/{slug}/availability — 200 with slots, NO auth required")
    void should_return200WithSlots_when_availabilityRequested() throws Exception {
        UUID svc = UUID.randomUUID();
        var slot = new AvailableSlotResponse(
                ZonedDateTime.parse("2026-06-10T12:00:00+03:00[Europe/Kyiv]"),
                ZonedDateTime.parse("2026-06-10T13:00:00+03:00[Europe/Kyiv]"));
        when(guestBookingService.availableSlots(eq("marija-l-cd34"), any(), eq(svc)))
                .thenReturn(List.of(slot));

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/availability")
                        .param("date", "2026-06-10")
                        .param("serviceId", svc.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("GET /book/{slug}/availability — 400 when date is in the past / over the window")
    void should_return400_when_availabilityDateOutOfWindow() throws Exception {
        UUID svc = UUID.randomUUID();
        // A single serviceId param routes to the single-UUID availableSlots overload; disambiguate the
        // mock from the multi-service List overload added in BE-7.
        when(guestBookingService.availableSlots(anyString(), any(), any(UUID.class)))
                .thenThrow(new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "date is in the past"));

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/availability")
                        .param("date", "2020-01-01")
                        .param("serviceId", svc.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── Phase 387: bookable-days (public calendar day-gate) ───────────────────

    @Test
    @DisplayName("GET /book/{slug}/bookable-days — 200 with [{date, working}] only, NO auth required")
    void should_return200WithDayFlags_when_bookableDaysRequested() throws Exception {
        UUID svc = UUID.randomUUID();
        when(guestBookingService.bookableDays("marija-l-cd34", java.time.LocalDate.parse("2026-06-10"),
                java.time.LocalDate.parse("2026-06-11"), List.of(svc)))
                .thenReturn(List.of(
                        new com.beautica.master.dto.MasterWorkingDayResponse(java.time.LocalDate.parse("2026-06-10"), true),
                        new com.beautica.master.dto.MasterWorkingDayResponse(java.time.LocalDate.parse("2026-06-11"), false)));

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/bookable-days")
                        .param("from", "2026-06-10")
                        .param("to", "2026-06-11")
                        .param("serviceId", svc.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].date").value("2026-06-10"))
                .andExpect(jsonPath("$[0].working").value(true))
                .andExpect(jsonPath("$[1].date").value("2026-06-11"))
                .andExpect(jsonPath("$[1].working").value(false))
                .andExpect(jsonPath("$[0].length()").value(2));
    }

    @Test
    @DisplayName("GET /book/{slug}/bookable-days — repeated serviceId binds to the ordered list (multi-service visit)")
    void should_passOrderedServiceIds_when_serviceIdRepeated() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(guestBookingService.bookableDays(anyString(), any(), any(), anyList())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/bookable-days")
                        .param("from", "2026-06-10")
                        .param("to", "2026-06-10")
                        .param("serviceId", a.toString(), b.toString()))
                .andExpect(status().isOk());

        verify(guestBookingService).bookableDays(eq("marija-l-cd34"), any(), any(), eq(List.of(a, b)));
    }

    @Test
    @DisplayName("GET /book/{slug}/bookable-days — 400 on missing/malformed from/to, missing or >10 serviceId, bad slug; no service call")
    void should_return400WithoutServiceCall_when_bookableDaysParamsInvalid() throws Exception {
        String svc = UUID.randomUUID().toString();
        String[] eleven = new String[11];
        for (int i = 0; i < eleven.length; i++) {
            eleven[i] = UUID.randomUUID().toString();
        }

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/bookable-days").param("to", "2026-06-10").param("serviceId", svc))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/marija-l-cd34/bookable-days").param("from", "2026-06-10").param("serviceId", svc))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/marija-l-cd34/bookable-days")
                        .param("from", "10.06.2026").param("to", "2026-06-10").param("serviceId", svc))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/marija-l-cd34/bookable-days").param("from", "2026-06-10").param("to", "2026-06-10"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/marija-l-cd34/bookable-days")
                        .param("from", "2026-06-10").param("to", "2026-06-10").param("serviceId", "not-a-uuid"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/marija-l-cd34/bookable-days")
                        .param("from", "2026-06-10").param("to", "2026-06-10").param("serviceId", eleven))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/Bad_Slug!/bookable-days")
                        .param("from", "2026-06-10").param("to", "2026-06-10").param("serviceId", svc))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(guestBookingService);
    }

    @Test
    @DisplayName("GET /book/{slug}/bookable-days — service 400 (range outside the guest window) and 404 (unknown slug/service) pass through")
    void should_return400Or404_when_serviceRejectsBookableDays() throws Exception {
        UUID svc = UUID.randomUUID();
        when(guestBookingService.bookableDays(eq("marija-l-cd34"), any(), any(), anyList()))
                .thenThrow(new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "date is in the past"));
        when(guestBookingService.bookableDays(eq("no-such-slug"), any(), any(), anyList()))
                .thenThrow(new NotFoundException("Booking page not found"));

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/bookable-days")
                        .param("from", "2020-01-01").param("to", "2020-01-02").param("serviceId", svc.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/no-such-slug/bookable-days")
                        .param("from", "2026-06-10").param("to", "2026-06-10").param("serviceId", svc.toString()))
                .andExpect(status().isNotFound());
    }

    // ── Phase 13.3: guest booking creation ────────────────────────────────────

    @Test
    @DisplayName("POST /book/{slug}/booking — 201 with confirmed booking")
    void should_return201_when_guestBookingCreated() throws Exception {
        UUID svc = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var req = new GuestBookingRequest(svc, null, OffsetDateTime.parse("2099-06-10T12:00:00+03:00"), "Олена", "Коваль", true, "2026-10-20");
        var resp = new GuestBookingResponse(bookingId, req.startsAt(), "Марія Левченко", "Манікюр", 60,
                "https://app.beautica.test/book/cancel/" + UUID.randomUUID());
        when(guestBookingService.createGuestBooking(eq("Bearer guest.token"), eq("marija-l-cd34"), any()))
                .thenReturn(resp);

        mockMvc.perform(post("/api/v1/book/marija-l-cd34/booking")
                        .header("Authorization", "Bearer guest.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.serviceName").value("Манікюр"))
                .andExpect(jsonPath("$.cancelUrl").value(resp.cancelUrl()));
    }

    @Test
    @DisplayName("POST /book/{slug}/booking — 401 when guest token is expired/invalid")
    void should_return401_when_tokenInvalid() throws Exception {
        UUID svc = UUID.randomUUID();
        var req = new GuestBookingRequest(svc, null, OffsetDateTime.parse("2099-06-10T12:00:00+03:00"), "A", "B", true, "2026-10-20");
        when(guestBookingService.createGuestBooking(anyString(), anyString(), any()))
                .thenThrow(new io.jsonwebtoken.ExpiredJwtException(null, null, "expired"));

        mockMvc.perform(post("/api/v1/book/marija-l-cd34/booking")
                        .header("Authorization", "Bearer expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /book/{slug}/booking — 400 when body fails Bean Validation (blank name)")
    void should_return400_when_nameBlank() throws Exception {
        UUID svc = UUID.randomUUID();
        String body = """
                {"serviceId":"%s","startsAt":"2099-06-10T12:00:00+03:00","name":"","surname":"Коваль","privacyAccepted":true,"privacyPolicyVersion":"2026-10-20"}
                """.formatted(svc);

        mockMvc.perform(post("/api/v1/book/marija-l-cd34/booking")
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(guestBookingService);
    }

    // ── Phase 13.4: guest cancel by link ──────────────────────────────────────

    @Test
    @DisplayName("GET /book/cancel/{token} — 200 with cancellable=true, NO auth required")
    void should_return200WithCancellableTrue_when_tokenValid() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        var info = new CancelTokenInfoResponse(
                "Марія Левченко", "Манікюр",
                OffsetDateTime.parse("2099-06-10T12:00:00+03:00"), true,
                OffsetDateTime.parse("2099-06-10T10:00:00+03:00"),
                com.beautica.booking.enums.BookingStatus.CONFIRMED, null, "Студія Лілія", "Хрещатик 10");
        when(bookingCancellationService.getInfo(token)).thenReturn(info);

        mockMvc.perform(get("/api/v1/book/cancel/" + token.value()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salonName").value("Студія Лілія"))
                .andExpect(jsonPath("$.address").value("Хрещатик 10"))
                .andExpect(jsonPath("$.guestPhone").doesNotExist())
                .andExpect(jsonPath("$.guestName").doesNotExist())
                .andExpect(jsonPath("$.masterName").value("Марія Левченко"))
                .andExpect(jsonPath("$.serviceName").value("Манікюр"))
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.windowClosesAt").exists());
    }

    @Test
    @DisplayName("GET /book/cancel/{token} — 200 with cancellable=false when inside the window")
    void should_return200WithCancellableFalse_when_insideWindow() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        var info = new CancelTokenInfoResponse(
                "Ivan", "Стрижка",
                OffsetDateTime.parse("2099-06-10T12:00:00+03:00"), false,
                OffsetDateTime.parse("2099-06-10T10:00:00+03:00"),
                com.beautica.booking.enums.BookingStatus.CONFIRMED, null, null, null);
        when(bookingCancellationService.getInfo(token)).thenReturn(info);

        mockMvc.perform(get("/api/v1/book/cancel/" + token.value()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.salonName").isEmpty())
                .andExpect(jsonPath("$.address").isEmpty());
    }

    @Test
    @DisplayName("phase 435: GET /book/cancel/{token} — 200 read-only view with status + rebookPath for a declined booking")
    void should_return200ReadOnlyViewWithStatus_when_bookingDeclined() throws Exception {
        // Arrange
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        var info = new CancelTokenInfoResponse(
                "Ivan", "Стрижка",
                OffsetDateTime.parse("2099-06-10T12:00:00+03:00"), false,
                OffsetDateTime.parse("2099-06-10T10:00:00+03:00"),
                com.beautica.booking.enums.BookingStatus.DECLINED, "/book/ivan-petrenko", null, "Сагайдачного 3А");
        when(bookingCancellationService.getInfo(token)).thenReturn(info);

        // Act + Assert
        mockMvc.perform(get("/api/v1/book/cancel/" + token.value()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.rebookPath").value("/book/ivan-petrenko"))
                .andExpect(jsonPath("$.salonName").isEmpty())
                .andExpect(jsonPath("$.address").value("Сагайдачного 3А"))
                .andExpect(jsonPath("$.masterName").value("Ivan"))
                .andExpect(jsonPath("$.startsAt").exists());
    }

    @Test
    @DisplayName("GET /book/cancel/{token} — 404 when token unknown / consumed (no hint)")
    void should_return404_when_cancelTokenUnknown() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        when(bookingCancellationService.getInfo(token))
                .thenThrow(new NotFoundException("Cancel token not found"));

        mockMvc.perform(get("/api/v1/book/cancel/" + token.value()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /book/cancel/{token} — 400 when the token is not a UUID (Spring converter, no DB hit)")
    void should_return400_when_cancelTokenMalformed() throws Exception {
        mockMvc.perform(get("/api/v1/book/cancel/not-a-uuid").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingCancellationService);
    }

    @Test
    @DisplayName("phase 436: POST /book/cancel/{token} without a body — 200 with the after-view, NO auth required")
    void should_return200WithView_when_cancelSucceeds() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        when(bookingCancellationService.cancel(org.mockito.ArgumentMatchers.eq(token),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(cancelledView());

        mockMvc.perform(post("/api/v1/book/cancel/" + token.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].cancellable").value(false))
                .andExpect(jsonPath("$.items[0].durationMinutes").value(60));

        verify(bookingCancellationService).cancel(org.mockito.ArgumentMatchers.eq(token),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("phase 436: POST /book/cancel/{token} with {} — cancels everything cancellable (backward compatible)")
    void should_passNoIds_when_bodyIsEmptyObject() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        when(bookingCancellationService.cancel(org.mockito.ArgumentMatchers.eq(token),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(cancelledView());

        mockMvc.perform(post("/api/v1/book/cancel/" + token.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("phase 436: POST /book/cancel/{token} with bookingIds — passes exactly those ids")
    void should_passIds_when_bodyNamesBookings() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(bookingCancellationService.cancel(token, List.of(a, b))).thenReturn(cancelledView());

        mockMvc.perform(post("/api/v1/book/cancel/" + token.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingIds\":[\"" + a + "\",\"" + b + "\"]}"))
                .andExpect(status().isOk());

        verify(bookingCancellationService).cancel(token, List.of(a, b));
    }

    @Test
    @DisplayName("phase 436: POST /book/cancel/{token} with \"bookingIds\": [] — 400 through the real handler, never cancel-all")
    void should_return400_when_bookingIdsEmpty() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        when(bookingCancellationService.cancel(token, List.of())).thenThrow(new BusinessException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "bookingIds must not be empty"));

        mockMvc.perform(post("/api/v1/book/cancel/" + token.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookingIds\":[]}"))
                .andExpect(status().isBadRequest());

        verify(bookingCancellationService).cancel(token, List.of());
    }

    @Test
    @DisplayName("phase 436 audit-fix: POST /book/cancel/{token} with a ~1 MB bookingIds array — 413 before parsing, service never called")
    void should_return413_when_cancelBodyOverCap() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        String id = "\"" + UUID.randomUUID() + "\"";
        String hugeBody = "{\"bookingIds\":[" + String.join(",", java.util.Collections.nCopies(26_000, id)) + "]}";

        mockMvc.perform(post("/api/v1/book/cancel/" + token.value())
                        .contentType(MediaType.APPLICATION_JSON).content(hugeBody))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Request body too large"));

        verifyNoInteractions(bookingCancellationService);
    }

    @Test
    @DisplayName("phase 436 audit-fix: a body of 10 ids stays well under the cap and reaches the service")
    void should_passBody_when_tenIdsUnderCap() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(10).toList();
        String body = "{\"bookingIds\":[" + ids.stream().map(u -> "\"" + u + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
        when(bookingCancellationService.cancel(token, ids)).thenReturn(cancelledView());

        mockMvc.perform(post("/api/v1/book/cancel/" + token.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("phase 436: POST /book/cancel/{token} with 11 bookingIds — 400, service never called")
    void should_return400_when_moreThanTenBookingIds() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        String ids = java.util.stream.Stream.generate(() -> "\"" + UUID.randomUUID() + "\"").limit(11)
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(post("/api/v1/book/cancel/" + token.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookingIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingCancellationService);
    }

    @Test
    @DisplayName("phase 436: POST /book/cancel/{token} with a malformed bookingId — generic 400, service never called")
    void should_return400_when_bookingIdMalformed() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/book/cancel/" + token.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookingIds\":[\"nope\"]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingCancellationService);
    }

    @Test
    @DisplayName("POST /book/cancel/{token} — 422 when inside the cancellation window")
    void should_return422_when_postInsideWindow() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        when(bookingCancellationService.cancel(org.mockito.ArgumentMatchers.eq(token),
                org.mockito.ArgumentMatchers.isNull())).thenThrow(new BusinessException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "Скасування недоступне — менше ніж 2 год до запису"));

        mockMvc.perform(post("/api/v1/book/cancel/" + token.value()))
                .andExpect(status().isUnprocessableEntity())
                // End-to-end through the real GlobalExceptionHandler: the cancel-window 422 copy
                // must reach the client verbatim (Ukrainian, with the window-hours interpolated) —
                // not redacted like CONFLICT/BAD_REQUEST. Pins the cancel path specifically, not
                // just the generic handler unit test.
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Скасування недоступне — менше ніж 2 год до запису"));
    }

    @Test
    @DisplayName("POST /book/cancel/{token} — 404 when token already consumed (idempotent replay)")
    void should_return404_when_postTokenConsumed() throws Exception {
        GuestCancelToken token = GuestCancelToken.of(UUID.randomUUID());
        when(bookingCancellationService.cancel(org.mockito.ArgumentMatchers.eq(token),
                org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new NotFoundException("Cancel token not found"));

        mockMvc.perform(post("/api/v1/book/cancel/" + token.value()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /book/cancel/{token} — 400 when the token is not a UUID (no DB hit, no hint)")
    void should_return400_when_postCancelTokenMalformed() throws Exception {
        mockMvc.perform(post("/api/v1/book/cancel/not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingCancellationService);
    }

    /** Phase 436 — a one-line after-cancel view. */
    private static CancelTokenInfoResponse cancelledView() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2099-06-10T12:00:00+03:00");
        return new CancelTokenInfoResponse(
                "Ivan", "Стрижка", startsAt, false, startsAt.minusHours(2),
                com.beautica.booking.enums.BookingStatus.CANCELLED, null, null, null, null,
                List.of(new com.beautica.booking.dto.CancelTokenItemResponse(
                        UUID.randomUUID(), "Стрижка", startsAt, 60,
                        com.beautica.booking.enums.BookingStatus.CANCELLED, false)));
    }

    /** Phase 380: a fully populated enriched master payload around the legacy four fields. */
    private static BookingSlugInfoResponse masterPage(
            String name, String avatarUrl, String bio, List<ServiceSummaryDto> services) {
        return new BookingSlugInfoResponse(
                name, avatarUrl, bio, services, UUID.randomUUID(),
                com.beautica.master.entity.MasterType.INDEPENDENT_MASTER, null, null, null, null, null, 0,
                List.of(), List.of(), List.of(), true, java.time.Instant.parse("2026-10-18T09:12:44Z"),
                List.of(), null, null, null);
    }

    // ── Phase 385: guest booking comment ──────────────────────────────────────

    @Test
    @DisplayName("POST /book/{slug}/booking — a clientComment over 1000 chars or with control characters is 400, never reaches the service")
    void should_return400_when_clientCommentTooLongOrHasControlCharacters() throws Exception {
        UUID svc = UUID.randomUUID();
        for (String bad : new String[] {"а".repeat(1001), "до\\u0000бре"}) {
            String body = "{\"serviceId\":\"" + svc + "\",\"startsAt\":\"2099-06-10T12:00:00+03:00\","
                    + "\"name\":\"Олена\",\"surname\":\"Коваль\",\"privacyAccepted\":true,"
                    + "\"privacyPolicyVersion\":\"2026-10-20\",\"clientComment\":\"" + bad + "\"}";
            mockMvc.perform(post("/api/v1/book/marija-l-cd34/booking")
                            .header("Authorization", "Bearer guest.jwt.token")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(guestBookingService, org.mockito.Mockito.never()).createGuestBooking(any(), any(), any());
    }

    // ── Phase 383: GET /book/{slug}/reviews ────────────────────────────────────

    @Test
    @DisplayName("GET /book/{slug}/reviews — 200 PageResponse with the info Cache-Control, sort and size passed through")
    void should_return200WithPage_when_reviewsRequested() throws Exception {
        var review = new com.beautica.review.dto.ReviewResponse(UUID.randomUUID(), UUID.randomUUID(), "Олена К.",
                "Манікюр", 5, "Дуже акуратно", java.time.OffsetDateTime.parse("2026-10-12T15:20:00Z"), false,
                com.beautica.review.entity.ReviewSource.APP);
        when(bookingSlugService.reviewsForSlug(eq("marija-l-cd34"),
                eq(com.beautica.review.dto.SalonReviewSort.HIGHEST), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(review),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/reviews?sort=HIGHEST&page=0&size=20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=0, public, s-maxage=30"))
                .andExpect(jsonPath("$.data[0].clientDisplayName").value("Олена К."))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /book/{slug}/reviews — unknown sort is 400, malformed slug is 400, neither reaches the service")
    void should_return400_when_reviewsSortOrSlugInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/book/marija-l-cd34/reviews?sort=BOGUS").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/BAD_SLUG/reviews").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingSlugService);
    }

    @Test
    @DisplayName("GET /book/{slug}/reviews — unknown slug is 404")
    void should_return404_when_reviewsSlugUnknown() throws Exception {
        when(bookingSlugService.reviewsForSlug(eq("nope-zz99"), any(), any()))
                .thenThrow(new NotFoundException("Booking page not found"));

        mockMvc.perform(get("/api/v1/book/nope-zz99/reviews").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /book/s/{slug}/reviews — 200 PageResponse<SalonReviewResponse> with the info Cache-Control")
    void should_return200WithSalonReviewPage_when_salonReviewsRequested() throws Exception {
        var review = new com.beautica.review.dto.SalonReviewResponse(UUID.randomUUID(), UUID.randomUUID(), "Anna",
                "Bila", "Олена К.", "Манікюр", 5, "Дуже акуратно",
                java.time.OffsetDateTime.parse("2026-10-12T15:20:00Z"), false,
                com.beautica.review.entity.ReviewSource.APP);
        when(bookingSlugService.salonReviewsForSlug(eq("studiia-krasa-ab12"),
                eq(com.beautica.review.dto.SalonReviewSort.OLDEST), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(review),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/book/s/studiia-krasa-ab12/reviews?sort=OLDEST").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=0, public, s-maxage=30"))
                .andExpect(jsonPath("$.data[0].masterFirstName").value("Anna"))
                .andExpect(jsonPath("$.data[0].clientDisplayName").value("Олена К."));
    }

    @Test
    @DisplayName("GET /book/s/{slug}/reviews — bad sort / malformed slug are 400 without a service call; unknown slug is 404")
    void should_return400Or404_when_salonReviewsInputInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/book/s/studiia-krasa-ab12/reviews?sort=NOPE")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/s/BAD_SLUG/reviews")).andExpect(status().isBadRequest());
        verifyNoInteractions(bookingSlugService);
        when(bookingSlugService.salonReviewsForSlug(eq("nope-zz99"), any(), any()))
                .thenThrow(new NotFoundException("Booking page not found"));

        mockMvc.perform(get("/api/v1/book/s/nope-zz99/reviews")).andExpect(status().isNotFound());
    }

    // ── Phase 377: public salon booking page ──────────────────────────────────

    private static final UUID SALON_PAGE_MASTER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SALON_PAGE_SERVICE_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");
    private static final UUID SALON_PAGE_MSA_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static SalonBookingSlugInfoResponse salonPage(UUID salonId) {
        return new SalonBookingSlugInfoResponse(
                salonId, "Студія Краса", "Вінниця", "https://cdn.example/logo.jpg", null,
                new BigDecimal("4.80"), 12, true,
                List.of(new SalonBookingSlugMasterItem("anna-bila-aa11", "Anna Bila", null, "Стиліст", new BigDecimal("4.90"), 7,
                        SALON_PAGE_MASTER_ID)),
                "Вінницька область", "вул. Соборна", "12", java.time.Instant.parse("2026-10-15T08:00:00Z"),
                null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(new com.beautica.booking.dto.SalonServiceCoverage(SALON_PAGE_SERVICE_ID, List.of(
                        new com.beautica.booking.dto.SalonServiceMasterRef(SALON_PAGE_MASTER_ID, SALON_PAGE_MSA_ID)))));
    }

    @Test
    @DisplayName("GET /book/s/{slug}/info — 200 with salon + bookable masters and NO Authorization header")
    void should_return200WithSalonAndMasters_when_salonSlugExists() throws Exception {
        UUID salonId = UUID.randomUUID();
        when(salonBookingSlugService.findBySlug("studiia-krasa-ab12")).thenReturn(salonPage(salonId));

        mockMvc.perform(get("/api/v1/book/s/studiia-krasa-ab12/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salonId").value(salonId.toString()))
                .andExpect(jsonPath("$.name").value("Студія Краса"))
                .andExpect(jsonPath("$.city").value("Вінниця"))
                .andExpect(jsonPath("$.avgRating").value(4.80))
                .andExpect(jsonPath("$.reviewCount").value(12))
                .andExpect(jsonPath("$.bookable").value(true))
                .andExpect(jsonPath("$.masters.length()").value(1))
                .andExpect(jsonPath("$.masters[0].masterSlug").value("anna-bila-aa11"))
                .andExpect(jsonPath("$.masters[0].professionalTitle").value("Стиліст"))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                // Phase 391: the master id is now part of the public contract (salon guest routes key on it).
                .andExpect(jsonPath("$.masters[0].masterId").value(SALON_PAGE_MASTER_ID.toString()))
                .andExpect(jsonPath("$.serviceCoverage[0].serviceId").value(SALON_PAGE_SERVICE_ID.toString()))
                .andExpect(jsonPath("$.serviceCoverage[0].masters[0].masterId").value(SALON_PAGE_MASTER_ID.toString()))
                .andExpect(jsonPath("$.serviceCoverage[0].masters[0].masterServiceId").value(SALON_PAGE_MSA_ID.toString()))
                .andExpect(jsonPath("$.masters[0].userId").doesNotExist());
        verifyNoInteractions(bookingSlugService);
    }

    @Test
    @DisplayName("GET /book/s/{slug}/info — 404 when the salon slug is unknown / inactive")
    void should_return404_when_salonSlugUnknown() throws Exception {
        when(salonBookingSlugService.findBySlug("no-such-salon"))
                .thenThrow(new NotFoundException("Booking page not found"));

        mockMvc.perform(get("/api/v1/book/s/no-such-salon/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /book/s/{slug}/info — 400 when the slug violates the charset pattern (no lookup)")
    void should_return400_when_salonSlugMalformed() throws Exception {
        mockMvc.perform(get("/api/v1/book/s/Bad_Slug!/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(salonBookingSlugService);
    }

    @Test
    @DisplayName("GET /book/s/info — routes to the MASTER handler with slug 's' (1 char) → 400, salon handler untouched")
    void should_notRouteToSalonHandler_when_pathIsBookSInfo() throws Exception {
        mockMvc.perform(get("/api/v1/book/s/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(salonBookingSlugService);
        verifyNoInteractions(bookingSlugService);
    }

    @Test
    @DisplayName("GET /book/{slug}/info for a master slug never reaches the salon handler")
    void should_routeMasterSlugToMasterHandler_when_notUnderS() throws Exception {
        when(bookingSlugService.findBySlug("ivan-petrenko-ab12"))
                .thenReturn(masterPage("Ivan Petrenko", null, null, List.of()));

        mockMvc.perform(get("/api/v1/book/ivan-petrenko-ab12/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verifyNoInteractions(salonBookingSlugService);
    }
}
