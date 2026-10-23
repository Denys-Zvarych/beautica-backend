package com.beautica.booking.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.dto.BookingLinkResponse;
import com.beautica.booking.dto.BookingLinksResponse;
import com.beautica.booking.dto.BookingLinkStatus;
import com.beautica.booking.service.BookingLinkRetirementService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private BookingLinkRetirementService bookingLinkRetirementService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(bookingLinkService, bookingLinkRetirementService);
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
    @EnumSource(value = Role.class, names = {"INDEPENDENT_MASTER"})
    @DisplayName("GET /booking-links/mine as INDEPENDENT_MASTER (the only MASTER-link role since 2026-10-20) → 200 with full MASTER link shape")
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
    @EnumSource(value = Role.class, names = {"SALON_OWNER", "SALON_ADMIN"})
    @DisplayName("GET /booking-links/mine as SALON_OWNER / SALON_ADMIN → 200 with the SALON link shape only (owner: no MASTER link, 2026-10-20)")
    void should_return200WithSalonLinkOnly_when_salonRole(Role role) throws Exception {
        UUID salonId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        when(bookingLinkService.getMyLinks(CALLER_ID, role)).thenReturn(new BookingLinksResponse(true, List.of(
                new BookingLinkResponse(BookingLinkKind.SALON, "alfa-aa11",
                        "https://beautica.app/book/s/alfa-aa11", "Alfa", salonId, true))));

        mockMvc.perform(get(URL).with(as(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareEnabled").value(true))
                .andExpect(jsonPath("$.data.links.length()").value(1))
                .andExpect(jsonPath("$.data.links[0].kind").value("SALON"))
                .andExpect(jsonPath("$.data.links[0].slug").value("alfa-aa11"))
                .andExpect(jsonPath("$.data.links[0].url").value("https://beautica.app/book/s/alfa-aa11"))
                .andExpect(jsonPath("$.data.links[0].displayName").value("Alfa"))
                .andExpect(jsonPath("$.data.links[0].salonId").value(salonId.toString()))
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

    // ── phase 378 — regenerate / revoke ──────────────────────────────────────────────────────

    private static final UUID SALON_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String BODY = "{\"currentSlug\":\"olena-koval-x7k2\"}";
    private static final String MASTER_REGENERATE = "/api/v1/booking-links/master/regenerate";
    private static final String MASTER_REVOKE = "/api/v1/booking-links/master";
    private static final String SALON_REGENERATE = "/api/v1/booking-links/salons/" + SALON_ID + "/regenerate";
    private static final String SALON_REVOKE = "/api/v1/booking-links/salons/" + SALON_ID;

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    @DisplayName("378: POST /master/regenerate as INDEPENDENT_MASTER → 200 with the new ACTIVE link; caller id + currentSlug passed")
    void should_return200WithNewLink_when_independentMasterRegenerates() throws Exception {
        when(bookingLinkRetirementService.regenerateMaster(CALLER_ID, "olena-koval-x7k2")).thenReturn(
                new BookingLinkResponse(BookingLinkKind.MASTER, "olena-koval-p3qd",
                        "https://beautica.app/book/olena-koval-p3qd", "Олена Коваль", null, true));

        mockMvc.perform(json(post(MASTER_REGENERATE), BODY).with(as(Role.INDEPENDENT_MASTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("MASTER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.slug").value("olena-koval-p3qd"))
                .andExpect(jsonPath("$.data.url").value("https://beautica.app/book/olena-koval-p3qd"))
                .andExpect(jsonPath("$.data.bookable").value(true));
    }

    @Test
    @DisplayName("378: DELETE /master as INDEPENDENT_MASTER with currentSlug null → 200 REVOKED, slug/url null")
    void should_return200Revoked_when_independentMasterRevokes() throws Exception {
        when(bookingLinkRetirementService.revokeMaster(CALLER_ID, null))
                .thenReturn(BookingLinkResponse.revoked(BookingLinkKind.MASTER, "Олена Коваль", null));

        mockMvc.perform(json(delete(MASTER_REVOKE), "{\"currentSlug\":null}").with(as(Role.INDEPENDENT_MASTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(BookingLinkStatus.REVOKED.name()))
                .andExpect(jsonPath("$.data.slug").isEmpty())
                .andExpect(jsonPath("$.data.url").isEmpty())
                .andExpect(jsonPath("$.data.bookable").value(false));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_OWNER", "SALON_ADMIN"})
    @DisplayName("378: salon regenerate + revoke as SALON_OWNER / SALON_ADMIN → 200; role + salon id passed to the service")
    void should_return200_when_salonRoleMutatesSalonLink(Role role) throws Exception {
        when(bookingLinkRetirementService.regenerateSalon(CALLER_ID, role, SALON_ID, "olena-koval-x7k2")).thenReturn(
                new BookingLinkResponse(BookingLinkKind.SALON, "alfa-p3qd", "https://beautica.app/book/s/alfa-p3qd",
                        "Alfa", SALON_ID, false));
        when(bookingLinkRetirementService.revokeSalon(CALLER_ID, role, SALON_ID, "olena-koval-x7k2"))
                .thenReturn(BookingLinkResponse.revoked(BookingLinkKind.SALON, "Alfa", SALON_ID));

        mockMvc.perform(json(post(SALON_REGENERATE), BODY).with(as(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.salonId").value(SALON_ID.toString()))
                .andExpect(jsonPath("$.data.slug").value("alfa-p3qd"));
        mockMvc.perform(json(delete(SALON_REVOKE), BODY).with(as(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_OWNER", "SALON_ADMIN", "SALON_MASTER", "CLIENT"})
    @DisplayName("378: MASTER routes for every non-INDEPENDENT_MASTER role (owner has no personal link) → 403, service never called")
    void should_return403_when_nonIndependentMasterHitsMasterRoutes(Role role) throws Exception {
        mockMvc.perform(json(post(MASTER_REGENERATE), BODY).with(as(role))).andExpect(status().isForbidden());
        mockMvc.perform(json(delete(MASTER_REVOKE), BODY).with(as(role))).andExpect(status().isForbidden());

        Mockito.verifyNoInteractions(bookingLinkRetirementService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"INDEPENDENT_MASTER", "SALON_MASTER", "CLIENT"})
    @DisplayName("378: SALON routes for INDEPENDENT_MASTER / SALON_MASTER / CLIENT → 403, service never called")
    void should_return403_when_nonSalonManagerHitsSalonRoutes(Role role) throws Exception {
        mockMvc.perform(json(post(SALON_REGENERATE), BODY).with(as(role))).andExpect(status().isForbidden());
        mockMvc.perform(json(delete(SALON_REVOKE), BODY).with(as(role))).andExpect(status().isForbidden());

        Mockito.verifyNoInteractions(bookingLinkRetirementService);
    }

    @Test
    @DisplayName("378: all four routes anonymous → 401")
    void should_return401_when_anonymousMutation() throws Exception {
        mockMvc.perform(json(post(MASTER_REGENERATE), BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(delete(MASTER_REVOKE), BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post(SALON_REGENERATE), BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(delete(SALON_REVOKE), BODY)).andExpect(status().isUnauthorized());

        Mockito.verifyNoInteractions(bookingLinkRetirementService);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"currentSlug\":\" olena-koval-x7k2\"}", "{\"currentSlug\":\"Olena-Koval\"}",
            "{\"currentSlug\":\"../etc\"}", "{\"currentSlug\":\"-a\"}",
            "{\"currentSlug\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}"})
    @DisplayName("378: malformed currentSlug (space, uppercase, path chars, leading hyphen, 61 chars) → 400, service never called")
    void should_return400_when_currentSlugMalformed(String body) throws Exception {
        mockMvc.perform(json(post(MASTER_REGENERATE), body).with(as(Role.INDEPENDENT_MASTER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(delete(SALON_REVOKE), body).with(as(Role.SALON_OWNER)))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(bookingLinkRetirementService);
    }

    @Test
    @DisplayName("QA 378: currentSlug of exactly 60 chars (the column length, @Size max) → accepted and passed to the service")
    void should_acceptCurrentSlug_when_exactlySixtyChars() throws Exception {
        String sixty = "a".repeat(60);
        when(bookingLinkRetirementService.revokeMaster(CALLER_ID, sixty))
                .thenReturn(BookingLinkResponse.revoked(BookingLinkKind.MASTER, "Олена Коваль", null));

        mockMvc.perform(json(delete(MASTER_REVOKE), "{\"currentSlug\":\"" + sixty + "\"}").with(as(Role.INDEPENDENT_MASTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        Mockito.verify(bookingLinkRetirementService).revokeMaster(CALLER_ID, sixty);
    }

    @Test
    @DisplayName("378: missing body → 400")
    void should_return400_when_bodyMissing() throws Exception {
        mockMvc.perform(post(MASTER_REGENERATE).with(as(Role.INDEPENDENT_MASTER))).andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(bookingLinkRetirementService);
    }

    @Test
    @DisplayName("378: non-UUID salon id → 400, service never called")
    void should_return400_when_salonIdNotUuid() throws Exception {
        mockMvc.perform(json(post("/api/v1/booking-links/salons/not-a-uuid/regenerate"), BODY).with(as(Role.SALON_OWNER)))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(bookingLinkRetirementService);
    }
}
