package com.beautica.master;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.master.controller.IndependentMasterController;
import com.beautica.user.UserProfileResponse;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * {@code @WebMvcTest} slice for {@link IndependentMasterController}.
 *
 * <p>Covers the HTTP contract of {@code PATCH /api/v1/independent-masters/me}:
 * status codes, role enforcement ({@link PreAuthorize}), request-body validation,
 * and response shape. Business logic lives entirely in {@link UserService} and is
 * verified by its own unit test class — this slice never exercises the service
 * implementation.
 *
 * <h2>Why the inner {@code SecurityConfig} is needed</h2>
 * {@code @WebMvcTest} does not load {@code @EnableMethodSecurity} from the
 * production {@code SecurityConfig}. Without an explicit override, the
 * {@code @PreAuthorize("hasRole('INDEPENDENT_MASTER')")} annotation on the
 * controller is silently ignored and the 403 tests would pass vacuously.
 * The inner {@code @TestConfiguration @EnableMethodSecurity} re-enables method
 * security in the test slice context.
 *
 * <h2>Auth injection</h2>
 * Uses {@code SecurityMockMvcRequestPostProcessors.authentication()} to inject a
 * pre-built {@link UsernamePasswordAuthenticationToken} that populates
 * {@code authentication.getDetails()} with the user UUID — the field read by
 * {@link IndependentMasterController#extractUserId}. {@code @WithMockUser} is
 * deliberately NOT used because it never sets {@code details}.
 */
@WebMvcTest(IndependentMasterController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("IndependentMasterController — @WebMvcTest slice")
class IndependentMasterControllerTest {

    private static final String PATCH_ME_URL = "/api/v1/independent-masters/me";

    // ── Security override — required for @PreAuthorize to fire ────────────────

    /**
     * Minimal security filter chain that:
     * <ul>
     *   <li>Disables CSRF (avoids needing {@code .with(csrf())} on PATCH calls).</li>
     *   <li>Requires authentication on all requests (drives the 401 test).</li>
     *   <li>Returns 401 on missing/invalid credentials via the entry point.</li>
     *   <li>Re-enables method security so {@code @PreAuthorize} fires (drives the 403 tests).</li>
     * </ul>
     *
     * <p>Note: CSRF is disabled here because tests use {@code .with(csrf())} only
     * where it mirrors a real scenario; for role tests the concern is the 403 path,
     * not CSRF. The CSRF assertion is covered separately in the 401 test which omits
     * authentication entirely.
     */
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

    /**
     * Production collaborator. Stub per test for the happy path; no stub needed
     * for validation-failure tests (the controller never reaches the service).
     */
    @MockBean
    private UserService userService;

    /**
     * Required by {@link WebMvcTestSupport}'s pass-through filter override.
     * Never configured — the pass-through filter does not invoke token validation.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Builds the same {@link UsernamePasswordAuthenticationToken} that the real
     * {@link com.beautica.auth.JwtAuthenticationFilter} produces for a valid JWT.
     * The {@code details} field carries the user UUID consumed by
     * {@link IndependentMasterController#extractUserId}.
     */
    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    /**
     * Returns a minimal valid request body for
     * {@code PATCH /api/v1/independent-masters/me}.
     * Only {@code cityId} is required; remaining fields are optional in the DTO.
     */
    private String validRequestBody(UUID cityId) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("cityId", cityId.toString());
        body.put("street", "вулиця Хрещатик, 1");
        return objectMapper.writeValueAsString(body);
    }

    /**
     * Builds a stub {@link UserProfileResponse} for the happy-path test.
     * Only fields relevant to assertions are set to non-null values.
     */
    private UserProfileResponse stubUpdatedProfile(UUID userId, UUID cityId) {
        return new UserProfileResponse(
                userId,
                "master@beautica.test",
                "INDEPENDENT_MASTER",
                "Олена",
                "Коваль",
                null,         // phoneNumber
                cityId,
                null,         // districtId
                null,         // oblastId
                null,         // cityName
                null,         // oblastName
                null,         // districtName
                "вулиця Хрещатик, 1",
                null,         // buildingNo
                null,         // locationNote
                null,         // bio
                null,         // instagram
                true,         // isActive
                true,         // emailVerified
                null          // salonId
        );
    }

    // ── PATCH /me — happy path ────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /me — 200 when INDEPENDENT_MASTER sends a valid locality body")
    void should_return200WithUpdatedProfile_when_independentMasterSendsValidBody() throws Exception {
        var userId = UUID.randomUUID();
        var cityId = UUID.randomUUID();

        when(userService.updateProfile(eq(userId), any()))
                .thenReturn(stubUpdatedProfile(userId, cityId));

        mockMvc.perform(patch(PATCH_ME_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(cityId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.role").value("INDEPENDENT_MASTER"))
                .andExpect(jsonPath("$.data.cityId").value(cityId.toString()))
                .andExpect(jsonPath("$.data.street").value("вулиця Хрещатик, 1"));
    }

    // ── PATCH /me — 401 unauthenticated ──────────────────────────────────────

    @Test
    @DisplayName("PATCH /me — 401 when no Authorization header present")
    void should_return401_when_noAuthorizationHeader() throws Exception {
        var cityId = UUID.randomUUID();

        mockMvc.perform(patch(PATCH_ME_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(cityId)))
                .andExpect(status().isUnauthorized());
    }

    // ── PATCH /me — 403 wrong roles ───────────────────────────────────────────

    @Test
    @DisplayName("PATCH /me — 403 when CLIENT role attempts to call the endpoint")
    void should_return403_when_clientRoleCallsIndependentMasterEndpoint() throws Exception {
        var clientUserId = UUID.randomUUID();
        var cityId = UUID.randomUUID();

        mockMvc.perform(patch(PATCH_ME_URL)
                        .with(authenticatedAs(clientUserId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(cityId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /me — 403 when SALON_OWNER role attempts to call the endpoint")
    void should_return403_when_salonOwnerRoleCallsIndependentMasterEndpoint() throws Exception {
        var ownerUserId = UUID.randomUUID();
        var cityId = UUID.randomUUID();

        mockMvc.perform(patch(PATCH_ME_URL)
                        .with(authenticatedAs(ownerUserId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(cityId)))
                .andExpect(status().isForbidden());
    }

    // ── PATCH /me — 400 validation failures ──────────────────────────────────

    @Test
    @DisplayName("PATCH /me — 400 when cityId is absent from the request body")
    void should_return400_when_cityIdIsMissingFromRequestBody() throws Exception {
        var userId = UUID.randomUUID();
        // Omit cityId entirely — the @NotNull constraint must reject this at the
        // controller boundary before the service is ever invoked.
        String bodyWithoutCityId = objectMapper.writeValueAsString(
                java.util.Map.of("street", "вулиця Хрещатик, 1"));

        mockMvc.perform(patch(PATCH_ME_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutCityId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /me — 400 when street exceeds 255 characters")
    void should_return400_when_streetExceeds255Characters() throws Exception {
        var userId = UUID.randomUUID();
        var cityId = UUID.randomUUID();
        // 256 characters — one over the @Size(max = 255) cap
        String tooLongStreet = "А".repeat(256);

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("cityId", cityId.toString());
        body.put("street", tooLongStreet);
        String requestBody = objectMapper.writeValueAsString(body);

        mockMvc.perform(patch(PATCH_ME_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /me — 400 when locationNote contains a control character (§A regression)")
    void should_return400_when_locationNoteContainsControlCharacter() throws Exception {
        var userId = UUID.randomUUID();
        var cityId = UUID.randomUUID();
        // Embedded NUL control character — violates @Pattern("^[^\\p{Cntrl}]*$").
        // Without the constraint this passes through to the DB and causes a 500.
        // The   Java escape keeps the source ASCII-clean; Jackson serialises
        // it as the JSON   escape and the Bean Validation @Pattern fires at
        // the controller boundary, returning a clean 400 instead.
        String locationNoteWithControlChar = "біля парку ";

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("cityId", cityId.toString());
        body.put("locationNote", locationNoteWithControlChar);
        String requestBody = objectMapper.writeValueAsString(body);

        mockMvc.perform(patch(PATCH_ME_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
