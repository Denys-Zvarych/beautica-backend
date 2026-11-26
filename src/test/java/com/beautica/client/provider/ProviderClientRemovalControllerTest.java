package com.beautica.client.provider;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.client.provider.controller.ProviderClientController;
import com.beautica.client.provider.service.ClientRef;
import com.beautica.client.provider.service.ClientScope;
import com.beautica.client.provider.service.ClientScope.MasterScope;
import com.beautica.client.provider.service.ClientScope.SalonScope;
import com.beautica.client.provider.service.ProviderClientScopeResolver;
import com.beautica.client.provider.service.ProviderClientService;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B564 — {@code @WebMvcTest} slice of the four list-removal endpoints on {@link ProviderClientController}: role gates
 * (§D negative test per denied role), the exactly-one-id body rule (400) and the scope / ref handed to the service.
 */
@WebMvcTest(ProviderClientController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("ProviderClientController — B564 removals @WebMvcTest slice")
class ProviderClientRemovalControllerTest {

    private static final String SALON_REMOVE = "/api/v1/salons/%s/clients/removals";
    private static final String SALON_RESTORE = "/api/v1/salons/%s/clients/removals/restore";
    private static final String ME_REMOVE = "/api/v1/masters/me/clients/removals";
    private static final String ME_RESTORE = "/api/v1/masters/me/clients/removals/restore";

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

    @MockBean private ProviderClientService providerClientService;
    @MockBean private ProviderClientScopeResolver scopeResolver;
    @MockBean(name = "authz") private AuthorizationService authorizationService;
    @MockBean private JwtTokenProvider jwtTokenProvider;

    private final UUID salonId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();

    private static RequestPostProcessor as(UUID userId, Role role) {
        var token = new UsernamePasswordAuthenticationToken("u@beautica.test", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(userId);
        return authentication(token);
    }

    private static String body(UUID clientId, UUID guestBookingId) {
        return "{\"clientId\":" + (clientId == null ? "null" : "\"" + clientId + "\"")
                + ",\"guestBookingId\":" + (guestBookingId == null ? "null" : "\"" + guestBookingId + "\"") + "}";
    }

    @Test
    @DisplayName("owner removes a registered client from the salon list: 204, salon scope, actor = caller")
    void should_return204AndRemoveFromSalonList_when_ownerRemovesRegistered() throws Exception {
        ClientScope scope = new SalonScope(salonId, null);
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(scopeResolver.forSalon(salonId, null)).thenReturn(scope);

        mockMvc.perform(post(SALON_REMOVE.formatted(salonId)).with(as(userId, Role.SALON_OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(body(clientId, null)))
                .andExpect(status().isNoContent());

        verify(providerClientService).removeFromList(scope, new ClientRef.Registered(clientId), userId);
    }

    @Test
    @DisplayName("admin restores a guest on the salon list: 204, guest anchor")
    void should_return204AndRestore_when_adminRestoresGuest() throws Exception {
        UUID anchor = UUID.randomUUID();
        ClientScope scope = new SalonScope(salonId, null);
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(scopeResolver.forSalon(salonId, null)).thenReturn(scope);

        mockMvc.perform(post(SALON_RESTORE.formatted(salonId)).with(as(userId, Role.SALON_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, anchor)))
                .andExpect(status().isNoContent());

        verify(providerClientService).restoreToList(scope, new ClientRef.GuestAnchor(anchor));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALON_MASTER", "INDEPENDENT_MASTER"})
    @DisplayName("a master removes from / restores to their own list: 204, master scope")
    void should_return204_when_masterUsesOwnList(String role) throws Exception {
        ClientScope scope = new MasterScope(UUID.randomUUID(), null);
        when(scopeResolver.forMasterMe(userId, Role.valueOf(role))).thenReturn(scope);

        mockMvc.perform(post(ME_REMOVE).with(as(userId, Role.valueOf(role)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(clientId, null)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(ME_RESTORE).with(as(userId, Role.valueOf(role)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(clientId, null)))
                .andExpect(status().isNoContent());

        verify(providerClientService).removeFromList(scope, new ClientRef.Registered(clientId), userId);
        verify(providerClientService).restoreToList(scope, new ClientRef.Registered(clientId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENT", "SALON_MASTER", "INDEPENDENT_MASTER"})
    @DisplayName("14: CLIENT / SALON_MASTER / INDEPENDENT_MASTER on the salon path → 403, nothing written")
    void should_return403_when_nonSalonRoleUsesSalonPath(String role) throws Exception {
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        mockMvc.perform(post(SALON_REMOVE.formatted(salonId)).with(as(userId, Role.valueOf(role)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(clientId, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(SALON_RESTORE.formatted(salonId)).with(as(userId, Role.valueOf(role)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(clientId, null)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(providerClientService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENT", "SALON_OWNER", "SALON_ADMIN"})
    @DisplayName("14: CLIENT / owner / admin on /masters/me → 403 (the owner's master mode is the salon path)")
    void should_return403_when_nonMasterRoleUsesMePath(String role) throws Exception {
        mockMvc.perform(post(ME_REMOVE).with(as(userId, Role.valueOf(role)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(clientId, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(ME_RESTORE).with(as(userId, Role.valueOf(role)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(clientId, null)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(providerClientService);
    }

    @Test
    @DisplayName("14: an admin of another salon (canManageSalon false) → 403")
    void should_return403_when_callerCannotManageSalon() throws Exception {
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(false);

        mockMvc.perform(post(SALON_REMOVE.formatted(salonId)).with(as(userId, Role.SALON_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body(clientId, null)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(providerClientService);
    }

    @Test
    @DisplayName("14: both ids, neither id, or no body → 400, nothing written")
    void should_return400_when_bodyDoesNotCarryExactlyOneId() throws Exception {
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        String url = SALON_REMOVE.formatted(salonId);

        mockMvc.perform(post(url).with(as(userId, Role.SALON_OWNER)).contentType(MediaType.APPLICATION_JSON)
                .content(body(clientId, UUID.randomUUID()))).andExpect(status().isBadRequest());
        mockMvc.perform(post(url).with(as(userId, Role.SALON_OWNER)).contentType(MediaType.APPLICATION_JSON)
                .content(body(null, null))).andExpect(status().isBadRequest());
        mockMvc.perform(post(ME_RESTORE).with(as(userId, Role.INDEPENDENT_MASTER))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(post(url).with(as(userId, Role.SALON_OWNER)).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(providerClientService);
    }

    @Test
    @DisplayName("an unknown client is the service's 404, passed through unchanged")
    void should_return404_when_serviceReportsClientNotFound() throws Exception {
        ClientScope scope = new SalonScope(salonId, null);
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(scopeResolver.forSalon(salonId, null)).thenReturn(scope);
        doThrow(new NotFoundException("CLIENT_NOT_FOUND")).when(providerClientService)
                .removeFromList(scope, new ClientRef.Registered(clientId), userId);

        mockMvc.perform(post(SALON_REMOVE.formatted(salonId)).with(as(userId, Role.SALON_OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(body(clientId, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
