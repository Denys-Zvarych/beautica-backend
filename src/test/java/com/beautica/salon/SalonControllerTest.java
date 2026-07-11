package com.beautica.salon;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.salon.controller.SalonController;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.service.SalonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link SalonController}.
 *
 * <p>Migrated from {@code @SpringBootTest(RANDOM_PORT)} + Testcontainers to avoid the
 * full application-context startup cost (8–15 s per class). Authorization decisions that
 * previously required real DB data are now stubbed via {@code @MockBean AuthorizationService}.
 *
 * <p>Follow the pattern established in {@link com.beautica.user.UserControllerTest}:
 * pass-through filter overrides, {@code authentication()} post-processor for auth,
 * and {@code csrf()} on mutating requests.
 */
@WebMvcTest(SalonController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("SalonController — @WebMvcTest slice")
class SalonControllerTest {

    private static final Logger log = LoggerFactory.getLogger(SalonControllerTest.class);
    private static final String SALONS_URL = "/api/v1/salons";

    // ── Security configuration ────────────────────────────────────────────────

    /**
     * Minimal {@link SecurityFilterChain} for the {@code @WebMvcTest} slice.
     *
     * <p>Mirrors the permit-all rules from production {@code SecurityConfig} for the
     * endpoints under test, while keeping CSRF disabled and session stateless so that
     * {@code SecurityMockMvcRequestPostProcessors.authentication()} injects auth tokens
     * correctly.
     *
     * <p>The pass-through {@link JwtAuthenticationFilter} is registered via
     * {@code addFilterBefore} so the filter chain position matches production, but the
     * filter itself is a no-op — authentication is injected directly by the test via
     * {@code authentication()}.
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}/masters").permitAll()
                            // Phase 23.x: mirrors the production SecurityConfig public GET matcher for
                            // the booking-selection endpoint.
                            .requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}/services/{serviceDefId}/masters").permitAll()
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
    private SalonService salonService;

    @MockBean(name = "authz")
    private AuthorizationService authorizationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // Phase 23.x: SalonController now constructor-injects BookingMasterService for the
    // GET /{salonId}/services/{serviceDefId}/masters booking-selection endpoint. Without this
    // @MockBean the @WebMvcTest context fails to load (no bean of type BookingMasterService),
    // which previously took down all 33 tests in this class.
    @MockBean
    private com.beautica.booking.service.BookingMasterService bookingMasterService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private SalonResponse stubSalonResponse(UUID salonId, String name) {
        // Phase 12.1: isPrimary added as the 17th component; false is the safe default for stubs.
        return new SalonResponse(salonId, null, name, null, null, null, null,
                null, null, null, null, null, null, null, null, true, false, null);
    }

    // ── POST /api/v1/salons ───────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/salons — 201 when SALON_OWNER sends valid body")
    void should_return201_when_validSalonCreation() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var request = new CreateSalonRequest("My Salon", null, "Kyiv", null, null, null, null, null, null, null, null, null);
        var stubResponse = stubSalonResponse(salonId, "My Salon");

        when(salonService.createSalon(eq(userId), any(CreateSalonRequest.class)))
                .thenReturn(stubResponse);

        log.debug("Act: POST {} as SALON_OWNER with name='My Salon'", SALONS_URL);
        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("My Salon"));
    }

    @Test
    @DisplayName("POST /api/v1/salons — 403 when CLIENT token is used")
    void should_return403_when_clientTokenUsedToCreateSalon() throws Exception {
        var userId = UUID.randomUUID();
        var request = new CreateSalonRequest("Forbidden Salon", null, null, null, null, null, null, null, null, null, null, null);

        log.debug("Act: POST {} as CLIENT — must be rejected with 403", SALONS_URL);
        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/salons — 401 when no Authorization header")
    void should_return401_when_noTokenOnCreateSalon() throws Exception {
        var request = new CreateSalonRequest("Anon Salon", null, null, null, null, null, null, null, null, null, null, null);

        log.debug("Act: POST {} without credentials — unauthenticated request must be rejected", SALONS_URL);
        mockMvc.perform(post(SALONS_URL)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/salons — 400 when name is blank (fails @NotBlank validation)")
    void should_return400_when_nameIsBlank_onCreateSalon() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: POST {} with blank name — must fail @NotBlank and return 400", SALONS_URL);
        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"city\":\"Kyiv\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/v1/salons/{id} ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/salons/{id} — 200 without authentication (public endpoint)")
    void should_return200_when_publicGetSalon() throws Exception {
        var salonId = UUID.randomUUID();
        var salon = buildSalonEntity(salonId, "Public Salon");
        when(salonService.getSalonEntity(salonId)).thenReturn(salon);

        log.debug("Act: GET {}/{} without credentials — public endpoint", SALONS_URL, salonId);
        mockMvc.perform(get(SALONS_URL + "/" + salonId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Public Salon"));
    }

    @Test
    @DisplayName("GET /api/v1/salons/{id} — 404 when salon does not exist")
    void should_return404_when_salonNotFound() throws Exception {
        var unknownId = UUID.randomUUID();
        when(salonService.getSalonEntity(unknownId))
                .thenThrow(new com.beautica.common.exception.NotFoundException("Salon not found"));

        log.debug("Act: GET {}/{} for a salon that does not exist", SALONS_URL, unknownId);
        mockMvc.perform(get(SALONS_URL + "/" + unknownId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/v1/salons/{salonId}/services/{serviceDefId}/masters ──────────
    // Phase 23.x booking-selection endpoint. The @WebMvcTest slice proves only the HTTP
    // contract (public access, JSON shape, 404 passthrough); the schedule-usability filter
    // itself lives in BookingMasterService (a @MockBean here) and is covered end-to-end by
    // BookingMasterServiceIT against a real Postgres.

    @Test
    @DisplayName("GET /{salonId}/services/{serviceDefId}/masters — 200 with bookable master list, no authentication (public)")
    void should_return200WithBookableMasters_when_publicRequest() throws Exception {
        var salonId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        var masterId = UUID.randomUUID();
        var masterServiceId = UUID.randomUUID();
        var bookable = new com.beautica.booking.dto.BookableMasterResponse(
                masterId, masterServiceId, "Олена", "Коваль", "Майстер манікюру",
                "https://cdn.example/a.jpg", new java.math.BigDecimal("4.80"), 12);
        when(bookingMasterService.getBookableMasters(salonId, serviceDefId))
                .thenReturn(List.of(bookable));

        log.debug("Act: GET {}/{}/services/{}/masters without credentials — public booking-selection endpoint",
                SALONS_URL, salonId, serviceDefId);
        mockMvc.perform(get(SALONS_URL + "/" + salonId + "/services/" + serviceDefId + "/masters")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].masterId").value(masterId.toString()))
                .andExpect(jsonPath("$.data[0].masterServiceId").value(masterServiceId.toString()))
                .andExpect(jsonPath("$.data[0].firstName").value("Олена"))
                .andExpect(jsonPath("$.data[0].lastName").value("Коваль"))
                .andExpect(jsonPath("$.data[0].avgRating").value(4.80))
                .andExpect(jsonPath("$.data[0].reviewCount").value(12));
    }

    @Test
    @DisplayName("GET /{salonId}/services/{serviceDefId}/masters — 200 with [] when no candidate is schedule-usable")
    void should_return200EmptyList_when_noBookableMasters() throws Exception {
        var salonId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        when(bookingMasterService.getBookableMasters(salonId, serviceDefId)).thenReturn(List.of());

        log.debug("Act: GET {}/{}/services/{}/masters — all candidates scheduleless, expect 200 []",
                SALONS_URL, salonId, serviceDefId);
        mockMvc.perform(get(SALONS_URL + "/" + salonId + "/services/" + serviceDefId + "/masters")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("GET /{salonId}/services/{serviceDefId}/masters — 404 when salon/service does not resolve (service NotFoundException)")
    void should_return404_when_serviceDoesNotResolve() throws Exception {
        var salonId = UUID.randomUUID();
        var serviceDefId = UUID.randomUUID();
        when(bookingMasterService.getBookableMasters(salonId, serviceDefId))
                .thenThrow(new com.beautica.common.exception.NotFoundException("Service not found: " + serviceDefId));

        log.debug("Act: GET {}/{}/services/{}/masters for a service that does not resolve to this salon",
                SALONS_URL, salonId, serviceDefId);
        mockMvc.perform(get(SALONS_URL + "/" + salonId + "/services/" + serviceDefId + "/masters")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── PATCH /api/v1/salons/{id} ─────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /api/v1/salons/{id} — 403 when a different owner patches the salon")
    void should_return403_when_differentOwnerPatchesSalon() throws Exception {
        var attackerUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        // authz returns false for the attacker (different owner)
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(false);

        var request = new UpdateSalonRequest("Hijacked", null, null, null, null,
                null, null, null, null, null, null, null);

        log.debug("Act: PATCH {}/{} with attacker's token — different owner must be denied", SALONS_URL, salonId);
        mockMvc.perform(patch(SALONS_URL + "/" + salonId)
                        .with(authenticatedAs(attackerUserId, "attacker@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // ── POST /api/v1/salons/{id}/invite ───────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/salons/{id}/invite — 201 when owner sends invite to valid email")
    void should_return201_when_ownerSendsInvite() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        when(salonService.inviteMaster(eq(userId), eq(salonId), any(), any()))
                .thenReturn(new InviteResponse("master@beautica.test", java.time.Instant.now().plusSeconds(86400)));

        String body = "{\"email\":\"master@beautica.test\"}";

        log.debug("Act: POST {}/{}/invite as SALON_OWNER with valid master email", SALONS_URL, salonId);
        mockMvc.perform(post(SALONS_URL + "/" + salonId + "/invite")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /api/v1/salons/{id}/invite — 400 when invite email is invalid")
    void should_return400_when_inviteEmailInvalid() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        String body = "{\"email\":\"not-an-email\"}";

        log.debug("Act: POST {}/{}/invite with malformed email='not-an-email'", SALONS_URL, salonId);
        mockMvc.perform(post(SALONS_URL + "/" + salonId + "/invite")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── PATCH with SALON_ADMIN ────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /api/v1/salons/{id} — 200 when SALON_ADMIN patches the salon they belong to")
    void should_return200_when_salonAdminPatchesSalon() throws Exception {
        var adminUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        var updated = stubSalonResponse(salonId, "Updated By Admin");
        when(salonService.updateSalon(eq(adminUserId), eq(salonId), any(UpdateSalonRequest.class)))
                .thenReturn(updated);

        var request = new UpdateSalonRequest("Updated By Admin", null, null, null, null,
                null, null, null, null, null, null, null);

        log.debug("Act: PATCH {}/{} as SALON_ADMIN belonging to that salon", SALONS_URL, salonId);
        mockMvc.perform(patch(SALONS_URL + "/" + salonId)
                        .with(authenticatedAs(adminUserId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Updated By Admin"));
    }

    // ── DELETE /api/v1/salons/{id} ────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/salons/{id} — 403 when SALON_ADMIN attempts to delete the salon")
    void should_return403_when_salonAdminDeletesSalon() throws Exception {
        var adminUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        log.debug("Act: DELETE {}/{} as SALON_ADMIN — admin must not be allowed to delete", SALONS_URL, salonId);
        mockMvc.perform(delete(SALONS_URL + "/" + salonId)
                        .with(authenticatedAs(adminUserId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/v1/salons/{id} — 204 when SALON_OWNER deactivates their own salon")
    void should_return204_when_salonOwnerDeletesOwnSalon() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        log.debug("Act: DELETE {}/{} as SALON_OWNER — must deactivate and return 204", SALONS_URL, salonId);
        mockMvc.perform(delete(SALONS_URL + "/" + salonId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ── GET /api/v1/salons/mine — deactivated salon excluded ─────────────────

    @Test
    @DisplayName("GET /api/v1/salons/mine — deactivated salon does not appear in listing")
    void should_notReturnDeactivatedSalon_when_listingSalons() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var activeSalon = stubSalonResponse(salonId, "Active Salon");

        // First call: salon exists and is active.
        // Second call (after deactivation): service filters by isActive=true and returns nothing.
        when(salonService.getOwnerSalons(userId))
                .thenReturn(List.of(activeSalon))
                .thenReturn(List.of());

        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);

        // Verify the salon appears in the list before deactivation.
        log.debug("Arrange: GET {}/mine — salon {} must be present before deactivation", SALONS_URL, salonId);
        mockMvc.perform(get(SALONS_URL + "/mine")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(salonId.toString()));

        // Act: deactivate the salon via DELETE.
        log.debug("Act: DELETE {}/{} — deactivates the salon", SALONS_URL, salonId);
        mockMvc.perform(delete(SALONS_URL + "/" + salonId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Assert: the listing no longer contains the deactivated salon.
        log.debug("Assert: GET {}/mine — deactivated salon {} must be absent", SALONS_URL, salonId);
        mockMvc.perform(get(SALONS_URL + "/mine")
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ── POST salons — second salon ────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/salons — 201 on second salon creation when SALON_OWNER already owns one salon")
    void should_return201_when_salonOwnerCreatesSecondSalon() throws Exception {
        var userId = UUID.randomUUID();
        var secondSalonId = UUID.randomUUID();
        var request = new CreateSalonRequest("Second Salon", null, "Lviv", null, null, null, null, null, null, null, null, null);
        var stubResponse = stubSalonResponse(secondSalonId, "Second Salon");

        when(salonService.createSalon(eq(userId), any(CreateSalonRequest.class)))
                .thenReturn(stubResponse);

        log.debug("Act: POST {} as SALON_OWNER who already owns one salon — multi-salon guard must be absent", SALONS_URL);
        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Second Salon"));
    }

    // ── URL validation tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/salons — 400 when instagramUrl uses javascript: scheme")
    void should_return400_when_instagramUrlUsesJavascriptScheme() throws Exception {
        var userId = UUID.randomUUID();
        var request = new CreateSalonRequest("Insta Salon JS", null, "Kyiv", null, null, null, "javascript:alert(1)", null, null, null, null, null);

        log.debug("Act: POST {} with instagramUrl='javascript:alert(1)' — must be rejected with 400", SALONS_URL);
        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/salons — 400 when instagramUrl uses http: instead of https:")
    void should_return400_when_instagramUrlUsesHttpScheme() throws Exception {
        var userId = UUID.randomUUID();
        var request = new CreateSalonRequest("Insta Salon HTTP", null, "Kyiv", null, null, null, "http://instagram.com/testuser", null, null, null, null, null);

        log.debug("Act: POST {} with instagramUrl='http://instagram.com/testuser' — must be rejected with 400", SALONS_URL);
        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ── Invite role tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/salons/{id}/invite — 403 when SALON_MASTER calls invite endpoint")
    void should_return403_when_salonMasterCallsInviteEndpoint() throws Exception {
        var masterUserId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        // canManageSalon returns false for SALON_MASTER (role guard fires before the method)
        // The @PreAuthorize "hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(...)"
        // short-circuits on the role check before calling authz.
        when(authorizationService.canManageSalon(any(), any())).thenReturn(false);

        String body = "{\"email\":\"victim@test.com\"}";

        log.debug("Act: POST {}/{}/invite as SALON_MASTER — must be denied with 403", SALONS_URL, salonId);
        mockMvc.perform(post(SALONS_URL + "/" + salonId + "/invite")
                        .with(authenticatedAs(masterUserId, "master@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/salons/{id}/invite — 403 when SALON_ADMIN invites into a different salon")
    void should_return403_when_salonAdminFromDifferentSalonInvites() throws Exception {
        var adminUserId = UUID.randomUUID();
        var salonBId = UUID.randomUUID();
        // Admin belongs to salon A, not salon B — authz denies
        when(authorizationService.canManageSalon(any(), eq(salonBId))).thenReturn(false);

        String body = "{\"email\":\"victim@test.com\"}";

        log.debug("Act: POST {}/{}/invite as SALON_ADMIN of a different salon — cross-salon invite must be denied", SALONS_URL, salonBId);
        mockMvc.perform(post(SALONS_URL + "/" + salonBId + "/invite")
                        .with(authenticatedAs(adminUserId, "admin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ── QA-MEDIUM: control-char @Pattern guard — POST create ─────────────────

    @Test
    @DisplayName("POST /api/v1/salons — 400 when name contains a control character")
    void should_return400_when_salonNameContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Salon\t\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/salons — 400 when description contains a control character")
    void should_return400_when_salonDescriptionContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Valid Name\",\"description\":\"Good desc bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/salons — 400 when city contains a control character")
    void should_return400_when_legacyCityContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Valid Name\",\"city\":\"Kyivwest\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/salons — 400 when region contains a control character")
    void should_return400_when_legacyRegionContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Valid Name\",\"region\":\"Kyivregion\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/salons — 400 when address contains a control character")
    void should_return400_when_legacyAddressContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Valid Name\",\"address\":\"Street 1floor 2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── QA-MEDIUM: control-char @Pattern guard — PATCH update ────────────────

    @Test
    @DisplayName("PATCH /api/v1/salons/{id} — 400 when name contains a control character")
    void should_return400_when_update_salonNameContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        mockMvc.perform(patch(SALONS_URL + "/" + salonId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\tName\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PATCH /api/v1/salons/{id} — 400 when description contains a control character")
    void should_return400_when_update_salonDescriptionContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        mockMvc.perform(patch(SALONS_URL + "/" + salonId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Bad desc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PATCH /api/v1/salons/{id} — 400 when city contains a control character")
    void should_return400_when_update_legacyCityContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        mockMvc.perform(patch(SALONS_URL + "/" + salonId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Kyivbad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PATCH /api/v1/salons/{id} — 400 when region contains a control character")
    void should_return400_when_update_legacyRegionContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        mockMvc.perform(patch(SALONS_URL + "/" + salonId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"Kyivoblast\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PATCH /api/v1/salons/{id} — 400 when address contains a control character")
    void should_return400_when_update_legacyAddressContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();

        mockMvc.perform(patch(SALONS_URL + "/" + salonId)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"Streetbad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── QA-MEDIUM: Phase 10.3 structured-address fields — control-char guard ────

    @Test
    @DisplayName("POST /api/v1/salons — 400 when street contains a control character")
    void should_return400_when_salonStreetContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();
        // Newline in street — @Pattern(^[^\p{Cntrl}]*$) must reject before service is reached
        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Valid Salon\",\"street\":\"Khreshchatyk 1\\nbad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/salons — 400 when buildingNo contains a control character")
    void should_return400_when_salonBuildingNoContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();
        // NUL byte in buildingNo — @Pattern(^[^\p{Cntrl}]*$) must reject
        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Valid Salon\",\"buildingNo\":\"12\\u0000A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/salons — 400 when locationNote contains a control character")
    void should_return400_when_salonLocationNoteContainsControlCharacters() throws Exception {
        var userId = UUID.randomUUID();
        // Tab character in locationNote — @Pattern(^[^\p{Cntrl}]*$) must reject
        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Valid Salon\",\"locationNote\":\"near the park\\tbad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── Phase 20.x: instagramUrl widened validation ──────────────────────────
    // Before these two tests, no test anywhere in this class ever set instagramUrl to a
    // non-null value: the @Pattern widen (URL-only → handle-or-URL) had zero HTTP-boundary
    // coverage. SalonService is a @MockBean here, so normalisation itself is proven by
    // SalonServiceTest — this class proves @Valid actually lets a bare handle reach the
    // controller/service (201, not 400) and still rejects a value matching neither pattern.

    @Test
    @DisplayName("POST /api/v1/salons — 201 when instagramUrl is a bare @-prefixed handle (widened pattern)")
    void should_return201_when_salonInstagramIsBareAtHandle() throws Exception {
        var userId = UUID.randomUUID();
        var salonId = UUID.randomUUID();
        var request = new CreateSalonRequest("My Salon", null, null, null, null, null, "@some.handle",
                null, null, null, null, null);
        var stubResponse = stubSalonResponse(salonId, "My Salon");

        when(salonService.createSalon(eq(userId), any(CreateSalonRequest.class))).thenReturn(stubResponse);

        log.debug("Act: POST {} with instagramUrl='@some.handle' — must pass @Valid now that the pattern is widened", SALONS_URL);
        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<CreateSalonRequest> captor = ArgumentCaptor.forClass(CreateSalonRequest.class);
        verify(salonService).createSalon(eq(userId), captor.capture());
        assertThat(captor.getValue().instagramUrl())
                .as("the raw (pre-normalisation) handle must reach the service unchanged — normalisation happens in SalonService")
                .isEqualTo("@some.handle");
    }

    @Test
    @DisplayName("POST /api/v1/salons — 400 when instagramUrl matches neither the handle nor the URL pattern")
    void should_return400_when_salonInstagramMatchesNeitherPattern() throws Exception {
        var userId = UUID.randomUUID();

        log.debug("Act: POST {} with an instagramUrl matching neither pattern — must still be rejected after the widen", SALONS_URL);
        mockMvc.perform(post(SALONS_URL)
                        .with(authenticatedAs(userId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Valid Name\",\"instagramUrl\":\"not a real value!!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds a minimal {@link com.beautica.salon.entity.Salon} entity stub for tests that call
     * {@link SalonService#getSalonEntity} — the controller maps this to
     * {@link com.beautica.salon.dto.PublicSalonResponse} via its static factory method.
     */
    private com.beautica.salon.entity.Salon buildSalonEntity(UUID salonId, String name) {
        return com.beautica.salon.entity.Salon.builder()
                .id(salonId)
                .name(name)
                .build();
    }
}
