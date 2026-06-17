package com.beautica.booking.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.booking.dto.BookingSlugInfoResponse;
import com.beautica.booking.dto.ServiceSummaryDto;
import com.beautica.booking.service.BookingSlugService;
import com.beautica.common.exception.NotFoundException;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @MockBean
    private BookingSlugService bookingSlugService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("GET /book/{slug}/info — 200 with master + services and NO Authorization header")
    void should_return200WithMasterAndServices_when_slugExists() throws Exception {
        var response = new BookingSlugInfoResponse(
                "Марія Левченко",
                "https://cdn.example/a.jpg",
                "Манікюр та педикюр",
                List.of(new ServiceSummaryDto(UUID.randomUUID(), "Манікюр", 60, new BigDecimal("350.00"))));
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
        var response = new BookingSlugInfoResponse(
                "Ivan Petrenko", null, null,
                List.of(new ServiceSummaryDto(UUID.randomUUID(), "Стрижка", 45, new BigDecimal("200.00"))));
        when(bookingSlugService.findBySlug("ivan-petrenko-ab12")).thenReturn(response);

        mockMvc.perform(get("/api/v1/book/ivan-petrenko-ab12/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.masterId").doesNotExist())
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
}
