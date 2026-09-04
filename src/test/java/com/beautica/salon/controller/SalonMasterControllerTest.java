package com.beautica.salon.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.service.MasterService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link SalonMasterController} — Phase 12.4, extended by Phase 297
 * for the {@code DELETE /{salonId}/masters/{masterId}} single-master-removal endpoint.
 *
 * <p>{@link AuthorizationService} is mocked to control {@code @PreAuthorize} outcomes
 * without a real DB. {@link MasterService} (and, since Phase 297, {@code SalonService}) is mocked
 * for all business-logic interactions.
 *
 * <p>Authentication is injected directly via
 * {@code SecurityMockMvcRequestPostProcessors.authentication()} so the
 * {@code details} UUID field is populated correctly — never via {@code @WithMockUser}.
 */
@WebMvcTest(SalonMasterController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("SalonMasterController — @WebMvcTest slice (Phase 12.4)")
class SalonMasterControllerTest {

    private static final Logger log = LoggerFactory.getLogger(SalonMasterControllerTest.class);
    private static final String BASE_URL = "/api/v1/salons";

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

    // ── Slice infrastructure ──────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MasterService masterService;

    // Phase 297 — the controller now also depends on SalonService for removeMaster.
    @MockBean
    private com.beautica.salon.service.SalonService salonService;

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

    private MasterDetailResponse stubMasterDetail(UUID masterId) {
        return new MasterDetailResponse(
                masterId, "Iryna", "Petrenko", null, null, null, null, null,
                null, null, null, null, BigDecimal.ZERO, 0, MasterType.SALON_OWNER, null, List.of(),
                null, null, null);
    }

    private Master stubMasterEntity(UUID masterId) {
        Master master = Master.builder()
                .masterType(MasterType.SALON_OWNER)
                .isActive(true)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .build();
        ReflectionTestUtils.setField(master, "id", masterId);
        return master;
    }

    // ── POST /{salonId}/master — enable ────────────────────────────────────────

    /**
     * Owner who owns the salon calls POST → service creates/reactivates the master row.
     * Response must be 200 with the MasterDetailResponse body.
     */
    @Test
    @DisplayName("POST /{salonId}/master — 200 when owner enables master profile")
    void should_return200_andCreateMaster_when_ownerEnablesMaster() throws Exception {
        var ownerUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(masterService.createMasterForOwner(eq(ownerUserId), eq(salonId)))
                .thenReturn(stubMasterEntity(masterId));
        // Controller uses entity overload (MEDIUM-2 fix) — stub Master arg, not UUID arg.
        when(masterService.getMasterDetail(any(Master.class)))
                .thenReturn(stubMasterDetail(masterId));

        log.debug("Act: POST {}/{}/master as SALON_OWNER — must return 200", BASE_URL, salonId);

        mockMvc.perform(post(BASE_URL + "/" + salonId + "/master")
                        .with(authenticatedAs(ownerUserId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.masterId").value(masterId.toString()))
                .andExpect(jsonPath("$.data.masterType").value("SALON_OWNER"));
    }

    /**
     * Calling POST a second time when the master row is already active must still
     * return 200 — the operation is idempotent at the service layer.
     */
    @Test
    @DisplayName("POST /{salonId}/master — 200 idempotent on second enable call")
    void should_return200_andBeIdempotent_when_ownerEnablesTwice() throws Exception {
        var ownerUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        // Same master returned on both calls — service is idempotent
        when(masterService.createMasterForOwner(eq(ownerUserId), eq(salonId)))
                .thenReturn(stubMasterEntity(masterId));
        // Controller uses entity overload (MEDIUM-2 fix) — stub Master arg, not UUID arg.
        when(masterService.getMasterDetail(any(Master.class)))
                .thenReturn(stubMasterDetail(masterId));

        log.debug("Act: POST {}/{}/master twice as SALON_OWNER — both calls must return 200", BASE_URL, salonId);

        // First call
        mockMvc.perform(post(BASE_URL + "/" + salonId + "/master")
                        .with(authenticatedAs(ownerUserId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.masterId").value(masterId.toString()));

        // Second call — same mock state; service still returns the existing active row
        mockMvc.perform(post(BASE_URL + "/" + salonId + "/master")
                        .with(authenticatedAs(ownerUserId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.masterId").value(masterId.toString()));
    }

    /**
     * SALON_ADMIN must be denied — the guard requires {@code hasRole('SALON_OWNER')}.
     * {@code canManageSalon} returns true for admins in general, but the role check
     * fails first, so the method is never reached.
     */
    @Test
    @DisplayName("POST /{salonId}/master — 403 when SALON_ADMIN tries to enable")
    void should_return403_when_salonAdminTriesToEnable() throws Exception {
        var adminUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        // canManageSalon would return true for an admin — but SALON_OWNER role check fires first.
        // Returning true here ensures the 403 is role-driven, not authz-bean-driven.
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        log.debug("Act: POST {}/{}/master as SALON_ADMIN — must be denied with 403", BASE_URL, salonId);

        mockMvc.perform(post(BASE_URL + "/" + salonId + "/master")
                        .with(authenticatedAs(adminUserId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * An owner of salon B targeting salon A — {@code canManageSalon} returns false,
     * so the entire SpEL guard short-circuits to denied.
     */
    @Test
    @DisplayName("POST /{salonId}/master — 403 when owner targets a salon they do not own")
    void should_return403_when_ownerEnablesMasterInUnownedSalon() throws Exception {
        var foreignOwnerId = UUID.randomUUID();
        var salonAId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonAId))).thenReturn(false);

        log.debug("Act: POST {}/{}/master as SALON_OWNER with no ownership — must be 403",
                BASE_URL, salonAId);

        mockMvc.perform(post(BASE_URL + "/" + salonAId + "/master")
                        .with(authenticatedAs(foreignOwnerId, "other@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * No Authorization header on POST — filter chain must return 401.
     */
    @Test
    @DisplayName("POST /{salonId}/master — 401 when no Authorization header")
    void should_return401_when_noToken_onEnable() throws Exception {
        var salonId = UUID.randomUUID();

        log.debug("Act: POST {}/{}/master without credentials — must be 401", BASE_URL, salonId);

        mockMvc.perform(post(BASE_URL + "/" + salonId + "/master")
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /{salonId}/master — disable ────────────────────────────────────

    /**
     * Owner with a pre-existing SALON_OWNER master row calls DELETE → 204, row
     * soft-deleted (is_active = false). Never hard-deleted.
     */
    @Test
    @DisplayName("DELETE /{salonId}/master — 204 when owner disables master profile")
    void should_return204_andSoftDelete_when_ownerDisablesMaster() throws Exception {
        var ownerUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        doNothing().when(masterService).deactivateOwnerMaster(eq(ownerUserId), eq(salonId));

        log.debug("Act: DELETE {}/{}/master as SALON_OWNER — must return 204", BASE_URL, salonId);

        mockMvc.perform(delete(BASE_URL + "/" + salonId + "/master")
                        .with(authenticatedAs(ownerUserId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    /**
     * Owner calls DELETE before ever calling POST (no master row) → service throws
     * {@link NotFoundException} → must surface as 404.
     */
    @Test
    @DisplayName("DELETE /{salonId}/master — 404 when no owner master profile exists")
    void should_return404_when_disablingNonexistentOwnerMaster() throws Exception {
        var ownerUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        doThrow(new NotFoundException("Owner master profile not found"))
                .when(masterService).deactivateOwnerMaster(eq(ownerUserId), eq(salonId));

        log.debug("Act: DELETE {}/{}/master when no row exists — must return 404", BASE_URL, salonId);

        mockMvc.perform(delete(BASE_URL + "/" + salonId + "/master")
                        .with(authenticatedAs(ownerUserId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    /**
     * No Authorization header on DELETE — filter chain must return 401.
     */
    @Test
    @DisplayName("DELETE /{salonId}/master — 401 when no Authorization header")
    void should_return401_when_noToken_onDisable() throws Exception {
        var salonId = UUID.randomUUID();

        log.debug("Act: DELETE {}/{}/master without credentials — must be 401", BASE_URL, salonId);

        mockMvc.perform(delete(BASE_URL + "/" + salonId + "/master")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * SALON_ADMIN must be denied on DELETE — same role guard as POST.
     */
    @Test
    @DisplayName("DELETE /{salonId}/master — 403 when SALON_ADMIN tries to disable")
    void should_return403_when_salonAdminTriesToDisable() throws Exception {
        var adminUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        log.debug("Act: DELETE {}/{}/master as SALON_ADMIN — must be 403", BASE_URL, salonId);

        mockMvc.perform(delete(BASE_URL + "/" + salonId + "/master")
                        .with(authenticatedAs(adminUserId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * Owner of salon B targeting salon A on DELETE — must be 403.
     */
    @Test
    @DisplayName("DELETE /{salonId}/master — 403 when owner targets a salon they do not own")
    void should_return403_when_ownerDisablesMasterInUnownedSalon() throws Exception {
        var foreignOwnerId = UUID.randomUUID();
        var salonAId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonAId))).thenReturn(false);

        log.debug("Act: DELETE {}/{}/master as SALON_OWNER with no ownership — must be 403",
                BASE_URL, salonAId);

        mockMvc.perform(delete(BASE_URL + "/" + salonAId + "/master")
                        .with(authenticatedAs(foreignOwnerId, "other@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /{salonId}/masters/{masterId} — Phase 297 single-master removal ────────────────

    /**
     * Owner who owns the salon and targets a master belonging to it → service disposes of the
     * master, controller returns 204.
     */
    @Test
    @DisplayName("DELETE /{salonId}/masters/{masterId} — 204 when owner removes a master of their salon")
    void should_return204_when_ownerRemovesMasterOfOwnedSalon() throws Exception {
        var ownerUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(authorizationService.masterBelongsToSalon(eq(masterId), eq(salonId))).thenReturn(true);
        doNothing().when(salonService).removeMaster(eq(ownerUserId), eq(salonId), eq(masterId));

        log.debug("Act: DELETE {}/{}/masters/{} as SALON_OWNER — must return 204",
                BASE_URL, salonId, masterId);

        mockMvc.perform(delete(BASE_URL + "/" + salonId + "/masters/" + masterId)
                        .with(authenticatedAs(ownerUserId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    /**
     * The service refuses with a domain conflict (e.g. D3's future-booking guard, or D6's
     * owner-row / already-detached guards) → must surface as 409.
     */
    @Test
    @DisplayName("DELETE /{salonId}/masters/{masterId} — 409 when the service refuses the removal")
    void should_return409_when_serviceRefusesRemoval() throws Exception {
        var ownerUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(authorizationService.masterBelongsToSalon(eq(masterId), eq(salonId))).thenReturn(true);
        doThrow(new com.beautica.common.exception.BusinessException(
                org.springframework.http.HttpStatus.CONFLICT,
                "Master has 1 future confirmed booking(s) — cancel or reschedule them first"))
                .when(salonService).removeMaster(eq(ownerUserId), eq(salonId), eq(masterId));

        log.debug("Act: DELETE {}/{}/masters/{} when service refuses — must return 409",
                BASE_URL, salonId, masterId);

        mockMvc.perform(delete(BASE_URL + "/" + salonId + "/masters/" + masterId)
                        .with(authenticatedAs(ownerUserId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    /**
     * masterId does not resolve, or resolves to a master of a different salon —
     * {@code masterBelongsToSalon} returns false, so the SpEL guard denies before the service is
     * ever reached (D5's IDOR closure).
     */
    @Test
    @DisplayName("DELETE /{salonId}/masters/{masterId} — 403 when masterBelongsToSalon is false")
    void should_return403_when_masterDoesNotBelongToSalon() throws Exception {
        var ownerUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(authorizationService.masterBelongsToSalon(eq(masterId), eq(salonId))).thenReturn(false);

        log.debug("Act: DELETE {}/{}/masters/{} with masterBelongsToSalon=false — must be 403",
                BASE_URL, salonId, masterId);

        mockMvc.perform(delete(BASE_URL + "/" + salonId + "/masters/" + masterId)
                        .with(authenticatedAs(ownerUserId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * SALON_ADMIN must be denied — D5's deliberate divergence from {@code removeAdmin}:
     * {@code hasRole('SALON_OWNER')} only, never {@code hasAnyRole('SALON_OWNER', 'SALON_ADMIN')}.
     */
    @Test
    @DisplayName("DELETE /{salonId}/masters/{masterId} — 403 when SALON_ADMIN tries to remove a master")
    void should_return403_when_salonAdminTriesToRemoveMaster() throws Exception {
        var adminUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        // canManageSalon/masterBelongsToSalon would both return true for an admin acting on
        // their own salon — but the SALON_OWNER role check fires first (D5), so the 403 here
        // must be role-driven, not authz-bean-driven.
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(authorizationService.masterBelongsToSalon(eq(masterId), eq(salonId))).thenReturn(true);

        log.debug("Act: DELETE {}/{}/masters/{} as SALON_ADMIN — must be denied with 403",
                BASE_URL, salonId, masterId);

        mockMvc.perform(delete(BASE_URL + "/" + salonId + "/masters/" + masterId)
                        .with(authenticatedAs(adminUserId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * Owner of salon B targeting salon A — {@code canManageSalon} returns false, so the whole
     * SpEL guard short-circuits to denied before {@code masterBelongsToSalon} is even relevant.
     */
    @Test
    @DisplayName("DELETE /{salonId}/masters/{masterId} — 403 when owner targets a salon they do not own")
    void should_return403_when_ownerRemovesMasterInUnownedSalon() throws Exception {
        var foreignOwnerId = UUID.randomUUID();
        var salonAId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        when(authorizationService.canManageSalon(any(), eq(salonAId))).thenReturn(false);

        log.debug("Act: DELETE {}/{}/masters/{} as SALON_OWNER with no ownership — must be 403",
                BASE_URL, salonAId, masterId);

        mockMvc.perform(delete(BASE_URL + "/" + salonAId + "/masters/" + masterId)
                        .with(authenticatedAs(foreignOwnerId, "other@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * No Authorization header — filter chain must return 401.
     */
    @Test
    @DisplayName("DELETE /{salonId}/masters/{masterId} — 401 when no Authorization header")
    void should_return401_when_noToken_onRemoveMaster() throws Exception {
        var salonId = UUID.randomUUID();
        var masterId = UUID.randomUUID();

        log.debug("Act: DELETE {}/{}/masters/{} without credentials — must be 401",
                BASE_URL, salonId, masterId);

        mockMvc.perform(delete(BASE_URL + "/" + salonId + "/masters/" + masterId)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
