package com.beautica.booking.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.dto.BookingLinkResponse;
import com.beautica.booking.dto.BookingLinksResponse;
import com.beautica.booking.service.BookingLinkService;
import com.beautica.config.WebMvcTestSupport;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
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
 * Phase 375 — {@code GET /api/v1/booking-links/mine}, sliced (§M-1): role gate (D2), principal
 * sourcing and the JSON envelope. Link resolution is {@code BookingLinkServiceTest}; persistence and
 * the lazy slug are {@code BookingLinkIT}.
 */
@WebMvcTest(BookingLinkController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("BookingLinkController — @WebMvcTest slice (Phase 375)")
class BookingLinkControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
                throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, exc) ->
                            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    private static final String URL = "/api/v1/booking-links/mine";
    private static final UUID CALLER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BookingLinkService bookingLinkService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(bookingLinkService);
    }

    private static RequestPostProcessor as(Role role) {
        var token = new UsernamePasswordAuthenticationToken(
                "caller@beautica.test", null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(CALLER_ID);
        return authentication(token);
    }

    @Test
    @DisplayName("GET /booking-links/mine anonymous → 401, service never called")
    void should_return401_when_anonymous() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());

        verify(bookingLinkService, never()).getMyLinks(any(), any());
    }

    @Test
    @DisplayName("GET /booking-links/mine as CLIENT → 403, service never called")
    void should_return403_when_client() throws Exception {
        mockMvc.perform(get(URL).with(as(Role.CLIENT))).andExpect(status().isForbidden());

        verify(bookingLinkService, never()).getMyLinks(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"INDEPENDENT_MASTER", "SALON_OWNER"})
    @DisplayName("GET /booking-links/mine as master-owning role → 200 with full MASTER link shape")
    void should_return200WithMasterLink_when_masterRole(Role role) throws Exception {
        when(bookingLinkService.getMyLinks(CALLER_ID, role)).thenReturn(new BookingLinksResponse(true, List.of(
                new BookingLinkResponse(BookingLinkKind.MASTER, "olena-koval-x7k2",
                        "https://beautica.app/book/olena-koval-x7k2", "Олена Коваль", null, true))));

        mockMvc.perform(get(URL).with(as(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shareEnabled").value(true))
                .andExpect(jsonPath("$.data.links.length()").value(1))
                .andExpect(jsonPath("$.data.links[0].kind").value("MASTER"))
                .andExpect(jsonPath("$.data.links[0].slug").value("olena-koval-x7k2"))
                .andExpect(jsonPath("$.data.links[0].url").value("https://beautica.app/book/olena-koval-x7k2"))
                .andExpect(jsonPath("$.data.links[0].displayName").value("Олена Коваль"))
                .andExpect(jsonPath("$.data.links[0].salonId").isEmpty())
                .andExpect(jsonPath("$.data.links[0].bookable").value(true));

        verify(bookingLinkService).getMyLinks(eq(CALLER_ID), eq(role));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_ADMIN", "SALON_MASTER"})
    @DisplayName("GET /booking-links/mine as SALON_ADMIN / SALON_MASTER → 200, shareEnabled=true, links empty")
    void should_return200WithEmptyLinks_when_noMasterLinkRole(Role role) throws Exception {
        when(bookingLinkService.getMyLinks(CALLER_ID, role)).thenReturn(new BookingLinksResponse(true, List.of()));

        mockMvc.perform(get(URL).with(as(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareEnabled").value(true))
                .andExpect(jsonPath("$.data.links").isEmpty());
    }

    @Test
    @DisplayName("GET /booking-links/mine with kill-switch off → 200, shareEnabled=false, links empty")
    void should_return200Disabled_when_shareSwitchedOff() throws Exception {
        when(bookingLinkService.getMyLinks(CALLER_ID, Role.INDEPENDENT_MASTER))
                .thenReturn(BookingLinksResponse.disabled());

        mockMvc.perform(get(URL).with(as(Role.INDEPENDENT_MASTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareEnabled").value(false))
                .andExpect(jsonPath("$.data.links").isEmpty());
    }
}
