package com.beautica.booking.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.GuestBookingRequest;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.booking.service.GuestBookingService;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.GoneException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.master.dto.MasterWorkingDayResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link PublicSalonBookingController} (phases 392/393): routing, parameter binding,
 * boundary validation (400 with no service call), anonymous access, and the service's 404 / 410 / 401 / 409
 * passing through unchanged. Business behaviour is in {@code GuestBookingServiceTest} and the ITs.
 */
@WebMvcTest(PublicSalonBookingController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("PublicSalonBookingController — @WebMvcTest slice (phases 392/393)")
class PublicSalonBookingControllerTest {

    private static final String SLUG = "studiia-krasa-ab12";
    private static final UUID MASTER_ID = UUID.fromString("2f7a1c3e-1b2d-4c5e-8f90-123456789abc");
    private static final String BASE = "/api/v1/book/s/" + SLUG + "/masters/" + MASTER_ID;

    @TestConfiguration
    static class SecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
                throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/book/**").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/book/s/*/masters/*/booking").permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private GuestBookingService guestBookingService;
    @MockBean private JwtTokenProvider jwtTokenProvider;

    // ── availability ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET …/availability — 200, no auth; repeated serviceId binds to the ordered list with slug + masterId")
    void should_return200AndBindOrderedServiceIds_when_salonAvailabilityRequested() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        LocalDate date = LocalDate.of(2099, 6, 10);
        ZonedDateTime start = ZonedDateTime.parse("2099-06-10T10:00:00+03:00");
        when(guestBookingService.salonAvailableSlots(SLUG, MASTER_ID, date, List.of(a, b)))
                .thenReturn(List.of(new AvailableSlotResponse(start, start.plusMinutes(120))));

        mockMvc.perform(get(BASE + "/availability").param("date", "2099-06-10")
                        .param("serviceId", a.toString()).param("serviceId", b.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        verify(guestBookingService).salonAvailableSlots(SLUG, MASTER_ID, date, List.of(a, b));
    }

    @Test
    @DisplayName("GET …/availability — 400 with no service call: missing / 11 serviceId, malformed masterId, bad slug, "
            + "missing date")
    void should_return400WithoutServiceCall_when_availabilityParamsInvalid() throws Exception {
        String svc = UUID.randomUUID().toString();
        String[] eleven = new String[11];
        for (int i = 0; i < eleven.length; i++) {
            eleven[i] = UUID.randomUUID().toString();
        }

        mockMvc.perform(get(BASE + "/availability").param("date", "2099-06-10")).andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/availability").param("date", "2099-06-10").param("serviceId", eleven))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/s/" + SLUG + "/masters/not-a-uuid/availability")
                .param("date", "2099-06-10").param("serviceId", svc)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/s/BAD_SLUG/masters/" + MASTER_ID + "/availability")
                .param("date", "2099-06-10").param("serviceId", svc)).andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/availability").param("serviceId", svc)).andExpect(status().isBadRequest());

        verifyNoInteractions(guestBookingService);
    }

    @Test
    @DisplayName("GET …/availability — service 404 / 410 / 400 pass through")
    void should_passThroughServiceErrors_when_availabilityFails() throws Exception {
        String svc = UUID.randomUUID().toString();
        when(guestBookingService.salonAvailableSlots(eq(SLUG), eq(MASTER_ID), eq(LocalDate.of(2099, 6, 10)), anyList()))
                .thenThrow(new NotFoundException("Booking page not found"));
        when(guestBookingService.salonAvailableSlots(eq(SLUG), eq(MASTER_ID), eq(LocalDate.of(2099, 6, 11)), anyList()))
                .thenThrow(GoneException.bookingLinkRetired());
        when(guestBookingService.salonAvailableSlots(eq(SLUG), eq(MASTER_ID), eq(LocalDate.of(2099, 6, 12)), anyList()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST, "date too far ahead"));

        mockMvc.perform(get(BASE + "/availability").param("date", "2099-06-10").param("serviceId", svc))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/availability").param("date", "2099-06-11").param("serviceId", svc))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.data.code").value("BOOKING_LINK_RETIRED"));
        mockMvc.perform(get(BASE + "/availability").param("date", "2099-06-12").param("serviceId", svc))
                .andExpect(status().isBadRequest());
    }

    // ── bookable-days ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET …/bookable-days — 200 shape {date, working} only, args bound")
    void should_return200WithDateWorkingOnly_when_salonBookableDaysRequested() throws Exception {
        UUID svc = UUID.randomUUID();
        LocalDate from = LocalDate.of(2099, 6, 10);
        when(guestBookingService.salonBookableDays(SLUG, MASTER_ID, from, from.plusDays(1), List.of(svc)))
                .thenReturn(List.of(new MasterWorkingDayResponse(from, true),
                        new MasterWorkingDayResponse(from.plusDays(1), false)));

        mockMvc.perform(get(BASE + "/bookable-days").param("from", "2099-06-10").param("to", "2099-06-11")
                        .param("serviceId", svc.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].date").value("2099-06-10"))
                .andExpect(jsonPath("$[0].working").value(true))
                .andExpect(jsonPath("$[1].working").value(false));
    }

    @Test
    @DisplayName("GET …/bookable-days — 400 with no service call for missing / malformed from or to")
    void should_return400WithoutServiceCall_when_bookableDaysRangeInvalid() throws Exception {
        String svc = UUID.randomUUID().toString();

        mockMvc.perform(get(BASE + "/bookable-days").param("to", "2099-06-11").param("serviceId", svc))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/bookable-days").param("from", "10.06.2099").param("to", "2099-06-11")
                .param("serviceId", svc)).andExpect(status().isBadRequest());

        verifyNoInteractions(guestBookingService);
    }

    // ── booking ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST …/booking — 201 with no Spring auth; Authorization header + slug + masterId passed through")
    void should_return201_when_salonGuestBookingCreated() throws Exception {
        UUID svc = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        var req = new GuestBookingRequest(null, List.of(svc), OffsetDateTime.parse("2099-06-10T12:00:00+03:00"),
                "Олена", "Коваль", true, "2026-10-20");
        var resp = new GuestBookingResponse(bookingId, appointmentId, req.startsAt(), "Анна Біла", "Манікюр", 60,
                "https://beautica.app/book/cancel/" + UUID.randomUUID());
        when(guestBookingService.createSalonGuestBooking(eq("Bearer guest.token"), eq(SLUG), eq(MASTER_ID), any()))
                .thenReturn(resp);

        mockMvc.perform(post(BASE + "/booking").header("Authorization", "Bearer guest.token")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()))
                .andExpect(jsonPath("$.cancelUrl").value(resp.cancelUrl()));
    }

    @Test
    @DisplayName("POST …/booking — 400 with no service call: privacyAccepted false, blank name, 11 services")
    void should_return400WithoutServiceCall_when_bookingBodyInvalid() throws Exception {
        String noConsent = """
                {"masterServiceIds":["%s"],"startsAt":"2099-06-10T12:00:00+03:00","name":"Олена","surname":"Коваль","privacyAccepted":false,"privacyPolicyVersion":"2026-10-20"}
                """.formatted(UUID.randomUUID());
        String blankName = noConsent.replace("\"name\":\"Олена\"", "\"name\":\"\"").replace("false", "true");
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            ids.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
        }
        String elevenServices = """
                {"masterServiceIds":[%s],"startsAt":"2099-06-10T12:00:00+03:00","name":"Олена","surname":"Коваль","privacyAccepted":true,"privacyPolicyVersion":"2026-10-20"}
                """.formatted(ids);

        for (String body : List.of(noConsent, blankName, elevenServices)) {
            mockMvc.perform(post(BASE + "/booking").header("Authorization", "Bearer t")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(guestBookingService);
    }

    @Test
    @DisplayName("POST …/booking — service 401 (bad guest token) / 404 / 409 pass through")
    void should_passThroughServiceErrors_when_bookingFails() throws Exception {
        var req = new GuestBookingRequest(null, List.of(UUID.randomUUID()),
                OffsetDateTime.parse("2099-06-10T12:00:00+03:00"), "Олена", "Коваль", true, "2026-10-20");
        String body = objectMapper.writeValueAsString(req);
        when(guestBookingService.createSalonGuestBooking(eq("Bearer expired"), anyString(), any(), any()))
                .thenThrow(new io.jsonwebtoken.ExpiredJwtException(null, null, "expired"));
        when(guestBookingService.createSalonGuestBooking(eq("Bearer foreign"), anyString(), any(), any()))
                .thenThrow(new NotFoundException("Booking page not found"));
        when(guestBookingService.createSalonGuestBooking(eq("Bearer taken"), anyString(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "Slot not available"));

        mockMvc.perform(post(BASE + "/booking").header("Authorization", "Bearer expired")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/booking").header("Authorization", "Bearer foreign")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mockMvc.perform(post(BASE + "/booking").header("Authorization", "Bearer taken")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }
}
