package com.beautica.service.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.auth.filter.AuthRateLimitFilter;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.service.ServiceCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link ServiceController}.
 *
 * <p>Migrated from {@code @SpringBootTest(RANDOM_PORT)} + Testcontainers. All
 * collaborators ({@link ServiceCatalogService}, {@link AuthorizationService}) are
 * {@code @MockBean}s; no real database is required.
 */
@WebMvcTest(ServiceController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@DisplayName("ServiceController — @WebMvcTest slice")
class ServiceControllerTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceControllerTest.class);

    // ── Pass-through filter overrides ─────────────────────────────────────────

    @TestConfiguration
    @EnableMethodSecurity
    static class PassThroughFilters {

        @Bean
        @Primary
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
            return new JwtAuthenticationFilter(jwtTokenProvider) {
                @Override
                protected void doFilterInternal(HttpServletRequest req,
                                                HttpServletResponse res,
                                                FilterChain chain)
                        throws ServletException, IOException {
                    chain.doFilter(req, res);
                }
            };
        }

        @Bean
        @Primary
        AuthRateLimitFilter authRateLimitFilter() {
            return new AuthRateLimitFilter(null, null) {
                @Override
                protected void doFilterInternal(HttpServletRequest req,
                                                HttpServletResponse res,
                                                FilterChain chain)
                        throws ServletException, IOException {
                    chain.doFilter(req, res);
                }

                @Override
                public boolean shouldNotFilter(HttpServletRequest request) {
                    return true;
                }
            };
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/masters/{masterId}/services").permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    // ── Slice infrastructure ──────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ServiceCatalogService serviceCatalogService;

    @MockBean(name = "authz")
    private AuthorizationService authorizationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private ServiceDefinitionResponse stubServiceDefResponse(UUID id, String name) {
        return new ServiceDefinitionResponse(id, name, null, null, 60, new BigDecimal("350.00"), 10, true, null, null);
    }

    private MasterServiceResponse stubMasterServiceResponse(UUID id, UUID masterId, String name) {
        return new MasterServiceResponse(id, masterId, stubServiceDefResponse(UUID.randomUUID(), name),
                null, null, new BigDecimal("350.00"), 60, true);
    }

    // ── POST /api/v1/salons/{salonId}/services ─────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — 201 when SALON_OWNER adds a service to their salon")
    void should_return201_when_ownerAddsServiceToSalon() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var serviceId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Classic Manicure", "Basic nail care", null, 60, new BigDecimal("350.00"), 10, null);
        var stub = stubServiceDefResponse(serviceId, "Classic Manicure");

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(serviceCatalogService.addServiceToSalon(eq(salonId), any(CreateServiceDefinitionRequest.class)))
                .thenReturn(stub);

        log.debug("Act: POST /api/v1/salons/{}/services as SALON_OWNER", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(serviceId.toString()))
                .andExpect(jsonPath("$.data.name").value("Classic Manicure"));
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 403 when a different owner adds a service")
    void should_return403_when_nonOwnerAddsServiceToSalon() throws Exception {
        var ownerBUserId = UUID.randomUUID();
        var salonAId = UUID.randomUUID();
        // authz denies — different owner
        when(authorizationService.canManageSalon(any(), eq(salonAId))).thenReturn(false);

        var request = new CreateServiceDefinitionRequest(
                "Hijack Service", null, null, 30, new BigDecimal("100.00"), 0, null);

        log.debug("Act: POST /api/v1/salons/{}/services with Owner B token — cross-owner must be denied", salonAId);
        mockMvc.perform(post("/api/v1/salons/" + salonAId + "/services")
                        .with(authenticatedAs(ownerBUserId, "ownerb@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when baseDurationMinutes is zero")
    void should_return400_when_durationZeroOrNegative() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        // baseDurationMinutes = 0 violates @Positive constraint
        String invalidBody = "{\"name\":\"Bad Service\",\"baseDurationMinutes\":0,\"bufferMinutesAfter\":0}";

        log.debug("Act: POST /api/v1/salons/{}/services with baseDurationMinutes=0 — must be rejected with 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/v1/salons/{salonId}/masters/{masterId}/services ──────────────

    @Test
    @DisplayName("POST /salons/{salonId}/masters/{masterId}/services — 201 when owner assigns service to master")
    void should_return201_when_ownerAssignsServiceToMaster() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        var assignmentId = UUID.randomUUID();
        var request = new AssignServiceToMasterRequest(serviceDefId, null, null);
        var stub = stubMasterServiceResponse(assignmentId, masterId, "Pedicure");

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(authorizationService.masterBelongsToSalon(masterId, salonId)).thenReturn(true);
        when(serviceCatalogService.assignServiceToMaster(eq(salonId), eq(masterId), any(AssignServiceToMasterRequest.class)))
                .thenReturn(stub);

        log.debug("Act: POST /api/v1/salons/{}/masters/{}/services — assign service to master", salonId, masterId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/masters/" + masterId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.masterId").value(masterId.toString()));
    }

    @Test
    @DisplayName("POST /salons/{salonId}/masters/{masterId}/services — 409 when same service assigned twice")
    void should_return409_when_duplicateAssignment() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        var request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(authorizationService.masterBelongsToSalon(masterId, salonId)).thenReturn(true);
        when(serviceCatalogService.assignServiceToMaster(eq(salonId), eq(masterId), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "Already assigned"));

        log.debug("Act: POST same assignment twice — second call must return 409");
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/masters/" + masterId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // ── GET /api/v1/masters/{masterId}/services — public ──────────────────────

    @Test
    @DisplayName("GET /masters/{id}/services — 200 without authentication (public endpoint)")
    void should_return200_when_publicGetMasterServices() throws Exception {
        var masterId = UUID.randomUUID();
        var stub = List.of(stubMasterServiceResponse(UUID.randomUUID(), masterId, "Gel Nails"));
        when(serviceCatalogService.getMasterServices(masterId)).thenReturn(stub);

        log.debug("Act: GET /api/v1/masters/{}/services without credentials — public endpoint", masterId);
        mockMvc.perform(get("/api/v1/masters/" + masterId + "/services")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ── POST /api/v1/independent-masters/me/services ───────────────────────────

    @Test
    @DisplayName("POST /independent-masters/me/services — 201 when INDEPENDENT_MASTER adds a service")
    void should_return201_when_independentMasterAddsOwnService() throws Exception {
        var userId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Lash Extensions", "Volume set", null, 120, new BigDecimal("900.00"), 15, null);
        var stub = stubMasterServiceResponse(UUID.randomUUID(), masterId, "Lash Extensions");

        when(serviceCatalogService.addIndependentMasterService(eq(userId), any(CreateServiceDefinitionRequest.class)))
                .thenReturn(stub);

        log.debug("Act: POST /api/v1/independent-masters/me/services as INDEPENDENT_MASTER");
        mockMvc.perform(post("/api/v1/independent-masters/me/services")
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.serviceDefinition.name").value("Lash Extensions"));
    }

    @Test
    @DisplayName("POST /independent-masters/me/services — 403 when CLIENT attempts the call")
    void should_return403_when_clientAddsIndependentMasterService() throws Exception {
        var userId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Sneaky Service", null, null, 30, new BigDecimal("100.00"), 0, null);

        log.debug("Act: POST /api/v1/independent-masters/me/services as CLIENT — must be denied");
        mockMvc.perform(post("/api/v1/independent-masters/me/services")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /api/v1/services/{serviceDefId} ─────────────────────────────────

    @Test
    @DisplayName("DELETE /services/{id} — 204 when owner deactivates their service definition")
    void should_return204_when_ownerDeactivatesService() throws Exception {
        var userId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        when(authorizationService.canManageServiceDefinition(any(), eq(serviceDefId))).thenReturn(true);

        log.debug("Act: DELETE /api/v1/services/{} as SALON_OWNER — must return 204", serviceDefId);
        mockMvc.perform(delete("/api/v1/services/" + serviceDefId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ── Validation boundary tests ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when baseDurationMinutes exceeds 480")
    void should_return400_when_baseDurationMinutesExceedsMax() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        String invalidBody = "{\"name\":\"Too Long\",\"baseDurationMinutes\":481,\"bufferMinutesAfter\":0}";

        log.debug("Act: POST /api/v1/salons/{}/services with baseDurationMinutes=481 — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when bufferMinutesAfter exceeds 120")
    void should_return400_when_bufferMinutesAfterExceedsMax() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        String invalidBody = "{\"name\":\"Buffer Service\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":121}";

        log.debug("Act: POST /api/v1/salons/{}/services with bufferMinutesAfter=121 — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when basePrice exceeds DecimalMax(99999999.99)")
    void should_return400_when_basePriceExceedsDecimalMax() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        String invalidBody = "{\"name\":\"Expensive Service\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0,\"basePrice\":100000000.00}";

        log.debug("Act: POST /api/v1/salons/{}/services with basePrice=100000000.00 — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when basePrice has 3 decimal places (violates @Digits(fraction=2))")
    void should_return400_when_basePriceHasThreeDecimalPlaces() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        String invalidBody = "{\"name\":\"Fraction Service\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0,\"basePrice\":123.456}";

        log.debug("Act: POST /api/v1/salons/{}/services with basePrice=123.456 — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when name exceeds 255 characters")
    void should_return400_when_nameExceeds255Chars() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        String longName = "a".repeat(256);
        String invalidBody = "{\"name\":\"" + longName + "\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0}";

        log.debug("Act: POST /api/v1/salons/{}/services with name.length=256 — must return 400", salonId);
        mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    // ── 401 for unauthenticated POST requests ─────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — 401 when no Authorization header is present")
    void should_return401_when_postSalonServiceWithoutAuth() throws Exception {
        var anyId = UUID.randomUUID();

        log.debug("Act: POST /api/v1/salons/{}/services without Authorization header — must return 401", anyId);
        mockMvc.perform(post("/api/v1/salons/" + anyId + "/services")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /independent-masters/me/services — 401 when no Authorization header is present")
    void should_return401_when_postIndependentMasterServiceWithoutAuth() throws Exception {
        log.debug("Act: POST /api/v1/independent-masters/me/services without Authorization header — must return 401");
        mockMvc.perform(post("/api/v1/independent-masters/me/services")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /services/{id} — 401 when no Authorization header is present")
    void should_return401_when_deleteServiceWithoutAuth() throws Exception {
        var anyId = UUID.randomUUID();

        log.debug("Act: DELETE /api/v1/services/{} without Authorization header — must return 401", anyId);
        mockMvc.perform(delete("/api/v1/services/" + anyId)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /services/{id} — 403 when authenticated owner targets a non-existent service definition UUID")
    void should_return403_when_ownerDeletesNonExistentServiceDefinition() throws Exception {
        // canManageServiceDefinition returns false when service definition does not exist
        // (findOwnerUserId returns empty → orElse(false)).
        // Spring Security short-circuits with 403, the service method is never invoked.
        var userId = UUID.randomUUID();
        var nonExistentId = UUID.randomUUID();
        when(authorizationService.canManageServiceDefinition(any(), eq(nonExistentId))).thenReturn(false);

        log.debug("Act: DELETE /api/v1/services/{} with valid owner token but missing service def — must return 403", nonExistentId);
        mockMvc.perform(delete("/api/v1/services/" + nonExistentId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
