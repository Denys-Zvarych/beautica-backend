package com.beautica.review.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.review.dto.ClientReviewResponse;
import com.beautica.review.service.ClientReviewService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B547 (REV-11, REV-D06) — {@code POST /client-reviews} role gate: the SALON_MASTER is now in the role list, and the
 * per-booking decision is {@code @authz.canReviewClient} (performing master only). A CLIENT stays out by role.
 */
@WebMvcTest(ClientReviewController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("ClientReviewController — @WebMvcTest slice (B547 salon master)")
class ClientReviewControllerTest {

    private static final String URL = "/api/v1/client-reviews";

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

    @Autowired private MockMvc mockMvc;
    @MockBean private ClientReviewService clientReviewService;
    @MockBean(name = "authz") private AuthorizationService authorizationService;
    @MockBean private JwtTokenProvider jwtTokenProvider;

    private static RequestPostProcessor as(UUID userId, Role role) {
        var token = new UsernamePasswordAuthenticationToken("u@beautica.test", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(userId);
        return authentication(token);
    }

    private static String body(UUID bookingId) {
        return "{\"bookingId\":\"" + bookingId + "\",\"rating\":5,\"comment\":\"Пунктуальна\"}";
    }

    @Test
    @DisplayName("201 when the SALON_MASTER who performed the booking rates its client")
    void should_return201_when_salonMasterRatesClientOfOwnBooking() throws Exception {
        UUID masterUserId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        when(authorizationService.canReviewClient(any(), eq(bookingId))).thenReturn(true);
        when(clientReviewService.create(eq(masterUserId), any())).thenReturn(new ClientReviewResponse(
                UUID.randomUUID(), bookingId, UUID.randomUUID(), UUID.randomUUID(), 5, "Пунктуальна",
                OffsetDateTime.now(ZoneOffset.UTC)));

        mockMvc.perform(post(URL).with(as(masterUserId, Role.SALON_MASTER))
                        .contentType(MediaType.APPLICATION_JSON).content(body(bookingId)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("403 when a SALON_MASTER rates the client of a colleague's booking; the service is never reached")
    void should_return403_when_salonMasterRatesClientOfColleaguesBooking() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(authorizationService.canReviewClient(any(), eq(bookingId))).thenReturn(false);

        mockMvc.perform(post(URL).with(as(UUID.randomUUID(), Role.SALON_MASTER))
                        .contentType(MediaType.APPLICATION_JSON).content(body(bookingId)))
                .andExpect(status().isForbidden());

        verify(clientReviewService, never()).create(any(), any());
    }

    @Test
    @DisplayName("403 for a CLIENT by role alone — the ownership predicate is never consulted")
    void should_return403_when_clientRatesClient() throws Exception {
        mockMvc.perform(post(URL).with(as(UUID.randomUUID(), Role.CLIENT))
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(authorizationService, clientReviewService);
    }

    // ── B570: DELETE /client-reviews/{reviewId} (REV-45) ──

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_OWNER", "SALON_ADMIN", "INDEPENDENT_MASTER", "SALON_MASTER"})
    @DisplayName("B570 — 204 for every provider role; the service gets the caller id and role, no SpEL authz lookup")
    void should_return204_when_providerDeletesOwnRating(Role role) throws Exception {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();

        mockMvc.perform(delete(URL + "/" + reviewId).with(as(userId, role)))
                .andExpect(status().isNoContent());

        verify(clientReviewService).deleteOwn(reviewId, userId, role);
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("B570 — 403 for a CLIENT by role alone; the service is never reached")
    void should_return403_when_clientDeletesRating() throws Exception {
        mockMvc.perform(delete(URL + "/" + UUID.randomUUID()).with(as(UUID.randomUUID(), Role.CLIENT)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clientReviewService);
    }

    @Test
    @DisplayName("B570 — the service's not-own / gone 404 reaches the wire as the one generic 404 body (no echo)")
    void should_return404_when_serviceReportsNotFound() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        doThrow(new NotFoundException("Client review not found"))
                .when(clientReviewService).deleteOwn(reviewId, userId, Role.SALON_OWNER);

        mockMvc.perform(delete(URL + "/" + reviewId).with(as(userId, Role.SALON_OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    @DisplayName("B570 — 400 for a malformed review id; the service is never reached")
    void should_return400_when_reviewIdMalformed() throws Exception {
        mockMvc.perform(delete(URL + "/not-a-uuid").with(as(UUID.randomUUID(), Role.SALON_OWNER)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(clientReviewService);
    }
}
