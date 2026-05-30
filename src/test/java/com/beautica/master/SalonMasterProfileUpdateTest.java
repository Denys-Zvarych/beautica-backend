package com.beautica.master;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.master.controller.MasterController;
import com.beautica.master.dto.MasterProfileUpdateRequest;
import com.beautica.master.dto.MasterPublicProfileResponse;
import com.beautica.master.service.MasterService;
import com.beautica.user.UserService;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link MasterController}
 * covering the {@code PATCH /api/v1/masters/me/profile} endpoint (Fix 4).
 *
 * <p>Validates role enforcement, happy-path response shape, and the four
 * negative cases required by the audit finding:
 * <ul>
 *   <li>SALON_MASTER (happy path — should succeed)</li>
 *   <li>INDEPENDENT_MASTER calling the SALON_MASTER endpoint (should fail)</li>
 *   <li>CLIENT calling the endpoint (should fail)</li>
 *   <li>Unauthenticated request (should fail)</li>
 * </ul>
 */
@WebMvcTest(MasterController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("MasterController — PATCH /me/profile slice (SALON_MASTER)")
class SalonMasterProfileUpdateTest {

    private static final String PATCH_PROFILE_URL = "/api/v1/masters/me/profile";

    // ── Security override — required for @PreAuthorize to fire ────────────────

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {

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

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MasterService masterService;

    @MockBean
    private SlotCalculationService slotCalculationService;

    @MockBean(name = "authz")
    private AuthorizationService authorizationService;

    @MockBean
    private UserService userService;

    /**
     * Required by {@link WebMvcTestSupport}'s pass-through filter override.
     * Never configured — the pass-through filter does not invoke token validation.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the same {@link UsernamePasswordAuthenticationToken} the real
     * {@link com.beautica.auth.JwtAuthenticationFilter} produces for a valid JWT.
     * The {@code details} field carries the user UUID consumed by
     * {@code MasterController#extractUserId}.
     */
    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    /** Returns a fully-valid request body JSON string for the profile update endpoint. */
    private String validRequestBody(String phone, String bio, String instagram) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("phoneNumber", phone);
        if (bio != null) body.put("bio", bio);
        if (instagram != null) body.put("instagram", instagram);
        return objectMapper.writeValueAsString(body);
    }

    // ── PATCH /me/profile — happy path ────────────────────────────────────────

    @Test
    @DisplayName("PATCH /me/profile — 200 when SALON_MASTER sends valid phone, bio, and instagram")
    void should_return200WithUpdatedProfile_when_salonMasterUpdatesProfile() throws Exception {
        var userId = UUID.randomUUID();
        var phone = "+380671234567";
        var bio = "Certified nail technician.";
        var instagram = "@nail_master";

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(new MasterPublicProfileResponse(null, null, phone, bio, instagram));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "smaster@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(phone, bio, instagram)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.phoneNumber").value(phone))
                .andExpect(jsonPath("$.data.bio").value(bio))
                .andExpect(jsonPath("$.data.instagram").value(instagram));
    }

    // ── PATCH /me/profile — 401 unauthenticated ───────────────────────────────

    @Test
    @DisplayName("PATCH /me/profile — 401 when no Authorization header present")
    void should_return401_when_unauthenticatedCallsMastersMeProfile() throws Exception {
        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, null)))
                .andExpect(status().isUnauthorized());
    }

    // ── PATCH /me/profile — 403 wrong roles ──────────────────────────────────

    @Test
    @DisplayName("PATCH /me/profile — 403 when INDEPENDENT_MASTER calls the SALON_MASTER endpoint")
    void should_return403_when_independentMasterCallsMastersMeProfile() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "imaster@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /me/profile — 403 when CLIENT calls the endpoint")
    void should_return403_when_clientCallsMastersMeProfile() throws Exception {
        var clientId = UUID.randomUUID();

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /me/profile — 403 when SALON_OWNER calls the endpoint")
    void should_return403_when_salonOwnerCallsMastersMeProfile() throws Exception {
        var ownerId = UUID.randomUUID();

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /me/profile — 403 when SALON_ADMIN calls the endpoint")
    void should_return403_when_salonAdminCallsMastersMeProfile() throws Exception {
        var adminId = UUID.randomUUID();

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(adminId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, null)))
                .andExpect(status().isForbidden());
    }
}
