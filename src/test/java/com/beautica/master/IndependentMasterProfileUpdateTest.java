package com.beautica.master;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.master.controller.IndependentMasterController;
import com.beautica.master.dto.MasterProfileUpdateRequest;
import com.beautica.master.dto.MasterPublicProfileResponse;
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
 * {@code @WebMvcTest} slice for {@link IndependentMasterController}
 * covering the {@code PATCH /api/v1/independent-masters/me/profile} endpoint.
 *
 * <p>Validates the HTTP contract of the profile-text update path: status codes,
 * role enforcement ({@link PreAuthorize}), Bean Validation on every DTO field, and
 * the shape of the success response. Business logic is tested separately in
 * {@code UserServiceTest} — this slice only exercises the controller.
 *
 * <h2>Why the inner {@code MethodSecurityConfig} is needed</h2>
 * {@code @WebMvcTest} does not load {@code @EnableMethodSecurity} from the
 * production config. Without an explicit override, {@code @PreAuthorize} is
 * silently ignored and the 403 tests pass vacuously.
 */
@WebMvcTest(IndependentMasterController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("IndependentMasterController — PATCH /me/profile slice")
class IndependentMasterProfileUpdateTest {

    private static final String PATCH_PROFILE_URL = "/api/v1/independent-masters/me/profile";

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
     * {@code IndependentMasterController#extractUserId}.
     */
    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    /** Returns a fully-valid request body for the profile update endpoint. */
    private String validRequestBody(String phone, String bio, String instagram) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("phoneNumber", phone);
        if (bio != null) body.put("bio", bio);
        if (instagram != null) body.put("instagram", instagram);
        return objectMapper.writeValueAsString(body);
    }

    /**
     * Builds a stub response that mirrors what the service would return.
     *
     * <p>Returns {@link MasterPublicProfileResponse} — the slim 3-field DTO — rather
     * than the full {@link com.beautica.user.UserProfileResponse}. The controller
     * wraps whatever the service returns in {@code ApiResponse.ok(...)}, so the
     * JSON fields under {@code $.data} must match the slim DTO shape.
     */
    private MasterPublicProfileResponse stubProfile(String phone, String bio, String instagram) {
        return new MasterPublicProfileResponse(null, null, phone, bio, instagram);
    }

    /**
     * Overload that also populates firstName and lastName — used by tests that assert
     * name fields are echoed in the response body.
     */
    private MasterPublicProfileResponse stubProfile(String firstName, String lastName,
            String phone, String bio, String instagram) {
        return new MasterPublicProfileResponse(firstName, lastName, phone, bio, instagram);
    }

    // ── PATCH /me/profile — happy path ────────────────────────────────────────

    @Test
    @DisplayName("PATCH /me/profile — 200 when INDEPENDENT_MASTER sends valid phone, bio, and instagram")
    void should_return200WithUpdatedProfile_when_validRequestBodySent() throws Exception {
        var userId = UUID.randomUUID();
        var phone = "+380671234567";
        var bio = "Professional hairdresser with 5 years experience.";
        var instagram = "@my_master_handle";

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(stubProfile(phone, bio, instagram));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(phone, bio, instagram)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.phoneNumber").value(phone))
                .andExpect(jsonPath("$.data.bio").value(bio))
                .andExpect(jsonPath("$.data.instagram").value(instagram));
    }

    @Test
    @DisplayName("PATCH /me/profile — 200 when bio and instagram are omitted (optional fields)")
    void should_return200_when_onlyPhoneNumberProvided() throws Exception {
        var userId = UUID.randomUUID();
        var phone = "+380501112233";

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(stubProfile(phone, null, null));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(phone, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.phoneNumber").value(phone));
    }

    // ── PATCH /me/profile — 401 unauthenticated ───────────────────────────────

    @Test
    @DisplayName("PATCH /me/profile — 401 when no Authorization header present")
    void should_return401_when_noAuthorizationHeader() throws Exception {
        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, null)))
                .andExpect(status().isUnauthorized());
    }

    // ── PATCH /me/profile — 403 wrong roles ──────────────────────────────────

    @Test
    @DisplayName("PATCH /me/profile — 403 when CLIENT role calls the endpoint")
    void should_return403_when_clientRoleCallsProfileEndpoint() throws Exception {
        var clientId = UUID.randomUUID();

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /me/profile — 403 when SALON_OWNER role calls the endpoint")
    void should_return403_when_salonOwnerRoleCallsProfileEndpoint() throws Exception {
        var ownerId = UUID.randomUUID();

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /me/profile — 403 when SALON_MASTER role calls the endpoint")
    void should_return403_when_salonMasterRoleCallsProfileEndpoint() throws Exception {
        var masterId = UUID.randomUUID();

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(masterId, "salonmaster@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /me/profile — 403 when SALON_ADMIN role calls the endpoint")
    void should_return403_when_salonAdminRoleCallsProfileEndpoint() throws Exception {
        var adminId = UUID.randomUUID();

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(adminId, "salonadmin@beautica.test", Role.SALON_ADMIN))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, null)))
                .andExpect(status().isForbidden());
    }

    // ── PATCH /me/profile — 400 validation failures ───────────────────────────

    @Test
    @DisplayName("PATCH /me/profile — 400 when phoneNumber is blank")
    void should_return400_when_phoneNumberIsBlank() throws Exception {
        var userId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(java.util.Map.of("phoneNumber", "  "));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /me/profile — 200 when phoneNumber is absent from the request body (phoneNumber is now optional)")
    void should_return200_when_phoneNumberIsAbsent() throws Exception {
        var userId = UUID.randomUUID();
        // phoneNumber is optional — omitting it must not produce a validation error.
        // The service leaves the stored phone unchanged when the field is null.
        var body = objectMapper.writeValueAsString(java.util.Map.of("bio", "Some bio text"));

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(new MasterPublicProfileResponse(null, null, "+380670000000", "Some bio text", null));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PATCH /me/profile — 400 when bio exceeds 2000 characters (§A size cap)")
    void should_return400_when_bioExceeds2000Characters() throws Exception {
        var userId = UUID.randomUUID();
        // 2001 characters — one over the @Size(max = 2000) cap; must not reach the DB.
        String tooLongBio = "А".repeat(2001);

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", tooLongBio, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /me/profile — 400 when instagram exceeds 100 characters (§A size cap)")
    void should_return400_when_instagramExceeds100Characters() throws Exception {
        var userId = UUID.randomUUID();
        // 101 characters — one over the @Size(max = 100) cap on instagram.
        String tooLongInstagram = "@" + "a".repeat(100);

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, tooLongInstagram)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /me/profile — 400 when instagram contains a control character (§A regression, MEDIUM-1)")
    void should_return400_when_instagramContainsControlCharacter() throws Exception {
        var userId = UUID.randomUUID();
        // NUL byte injected into instagram handle — the strict allowlist pattern rejects any
        // character outside [A-Za-z0-9._] and the empty / https:// branches, so   must
        // produce a 400 rather than reaching the DB and causing a 500.
        String instagramWithControlChar = "@handle injected";

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, instagramWithControlChar)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ── PATCH /me/profile — instagram scheme-injection (MEDIUM-2) ────────────

    @Test
    @DisplayName("PATCH /me/profile — 400 when instagram contains javascript: scheme (MEDIUM-2 stored-XSS guard)")
    void should_return400_when_instagramContainsJavascriptScheme() throws Exception {
        var userId = UUID.randomUUID();
        // javascript: URI scheme must be rejected by the strict allowlist — not just control chars.
        String maliciousInstagram = "javascript:alert(1)";

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, maliciousInstagram)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /me/profile — 400 when instagram contains data: URI scheme (MEDIUM-2 stored-XSS guard)")
    void should_return400_when_instagramContainsDataUri() throws Exception {
        var userId = UUID.randomUUID();
        String maliciousInstagram = "data:text/html,<script>alert(1)</script>";

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, maliciousInstagram)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /me/profile — 200 when instagram is a valid handle with @ prefix (MEDIUM-2 allowlist)")
    void should_return200_when_instagramIsValidHandle() throws Exception {
        var userId = UUID.randomUUID();
        var instagram = "@myhandle";

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(stubProfile("+380671234567", null, instagram));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, instagram)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.instagram").value(instagram));
    }

    @Test
    @DisplayName("PATCH /me/profile — 200 when instagram is a valid full instagram.com URL (MEDIUM-2 allowlist)")
    void should_return200_when_instagramIsValidFullUrl() throws Exception {
        var userId = UUID.randomUUID();
        var instagram = "https://instagram.com/myhandle";

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(stubProfile("+380671234567", null, instagram));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, instagram)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.instagram").value(instagram));
    }

    // ── PATCH /me/profile — phone all-whitespace (MEDIUM-3) ──────────────────

    @Test
    @DisplayName("PATCH /me/profile — 400 when phoneNumber is all spaces (MEDIUM-3: @Pattern must reject whitespace-only)")
    void should_return400_when_phoneNumberIsAllSpaces() throws Exception {
        var userId = UUID.randomUUID();
        // 7 spaces — @NotBlank alone fails because Jackson trims nothing; the new @Pattern
        // requires the first char (after optional +) to be a digit, so "       " must produce 400.
        var body = objectMapper.writeValueAsString(java.util.Map.of("phoneNumber", "       "));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /me/profile — 400 when bio contains a control character (§A regression)")
    void should_return400_when_bioContainsControlCharacter() throws Exception {
        var userId = UUID.randomUUID();
        // NUL control character embedded in bio — violates @Pattern("^[^\\p{Cntrl}]*$").
        // Without the constraint this reaches the DB and causes a 500.
        String bioWithControlChar = "Professional hairdresser with 5 years experience.";

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", bioWithControlChar, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ── PATCH /me/profile — MEDIUM boundary cases ────────────────────────────

    @Test
    @DisplayName("PATCH /me/profile — 200 when instagram is a handle without @ prefix (MEDIUM)")
    void should_return200_when_instagramIsHandleWithoutAtPrefix() throws Exception {
        var userId = UUID.randomUUID();
        // "myhandle" (no @) must pass the allowlist pattern ^@?[A-Za-z0-9._]{1,30}$ — the @ is optional.
        var instagram = "myhandle";

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(stubProfile("+380671234567", null, instagram));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", null, instagram)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.instagram").value(instagram));
    }

    @Test
    @DisplayName("PATCH /me/profile — 400 when phoneNumber is too short (MEDIUM: 6 chars fails @Pattern min-digit rule)")
    void should_return400_when_phoneNumberIsTooShort() throws Exception {
        var userId = UUID.randomUUID();
        // "+38067" — 6 characters total; the @Pattern requires at least 7 digits after the optional +
        // so this must produce a 400, not reach the service layer.
        var body = objectMapper.writeValueAsString(java.util.Map.of("phoneNumber", "+38067"));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ── CRITICAL regression — instagram empty string is the "clear" signal ─────

    @Test
    @DisplayName("PATCH /me/profile — 200 when instagram is empty string (clear-field contract)")
    void should_return200_when_instagramIsEmptyString() throws Exception {
        var userId = UUID.randomUUID();
        // CRITICAL regression: the mobile edit screen sends instagram="" to CLEAR the stored
        // handle (master_update.dart: "an empty instagram CLEARS the value server-side"). The
        // @Pattern carries an explicit ^$ alternation so "" is valid; UserService treats the
        // non-null "" as an overwrite. Previously the pattern rejected "" with a 400, and
        // because a single @Valid failure rejects the WHOLE body, firstName/lastName never
        // persisted — the exact bug this guards against.
        var body = objectMapper.writeValueAsString(java.util.Map.of(
                "phoneNumber", "+380671234567",
                "instagram", ""));

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(stubProfile("+380671234567", null, ""));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PATCH /me/profile — 200 for the exact failing payload {firstName, lastName, bio:\"\", instagram:\"\"}")
    void should_return200_when_clearingPayloadWithNamesSent() throws Exception {
        var userId = UUID.randomUUID();
        // The exact payload proven to fail live: names set, bio + instagram cleared via "".
        // It must be accepted so firstName/lastName persist.
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("firstName", "Olena");
        body.put("lastName", "Koval");
        body.put("bio", "");
        body.put("instagram", "");

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(stubProfile("Olena", "Koval", null, "", ""));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.firstName").value("Olena"))
                .andExpect(jsonPath("$.data.lastName").value("Koval"));
    }

    @Test
    @DisplayName("PATCH /me/profile — 400 with errors.instagram present when instagram is malformed")
    void should_return400WithErrorsMap_when_instagramMalformed() throws Exception {
        var userId = UUID.randomUUID();
        // A genuine format violation still fails, AND the response now carries the top-level
        // `errors` map (field → message) the mobile ErrorMapperInterceptor reads.
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("firstName", "Olena");
        body.put("lastName", "Koval");
        body.put("instagram", "bad handle!!");

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.errors.instagram").isNotEmpty());
    }

    // ── Fix 5 — QA MEDIUM: phone max-boundary ────────────────────────────────

    @Test
    @DisplayName("PATCH /me/profile — 200 when phoneNumber is exactly 20 characters (max boundary)")
    void should_return200_when_phoneNumberIsExactly20Characters() throws Exception {
        var userId = UUID.randomUUID();
        // "+" + 19 digits = 20 chars total — exactly at the @Size(max = 20) and @Pattern ceiling.
        var phone = "+" + "1".repeat(19);

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(stubProfile(phone, null, null));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(phone, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.phoneNumber").value(phone));
    }

    @Test
    @DisplayName("PATCH /me/profile — 400 when phoneNumber exceeds 20 characters (max boundary + 1)")
    void should_return400_when_phoneNumberExceeds20Characters() throws Exception {
        var userId = UUID.randomUUID();
        // "+" + 20 digits = 21 chars total — one over the @Size(max = 20) cap.
        var phone = "+" + "1".repeat(20);
        var body = objectMapper.writeValueAsString(java.util.Map.of("phoneNumber", phone));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ── Fix 5 — QA MEDIUM: firstName and lastName echoed in response ─────────

    @Test
    @DisplayName("PATCH /me/profile — 200 with firstName and lastName echoed when update includes name fields")
    void should_return200WithFirstNameAndLastName_when_updateIncludesNameFields() throws Exception {
        var userId = UUID.randomUUID();
        var firstName = "Нова";
        var lastName = "Назва";
        var phone = "+380671234567";

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("phoneNumber", phone);

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(stubProfile(firstName, lastName, phone, null, null));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.firstName").value(firstName))
                .andExpect(jsonPath("$.data.lastName").value(lastName));
    }

    // ── QA: firstName/lastName @NoDigits — number guard ──────────────────────

    @Test
    @DisplayName("PATCH /me/profile — 400 with errors.firstName when firstName contains a digit (@NoDigits)")
    void should_return400_when_firstNameContainsDigit() throws Exception {
        var userId = UUID.randomUUID();
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("firstName", "Olena5");
        body.put("lastName", "Koval");

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.firstName").value("First name must not contain a number"));
    }

    @Test
    @DisplayName("PATCH /me/profile — 400 with errors.lastName when lastName contains a digit (@NoDigits)")
    void should_return400_when_lastNameContainsDigit() throws Exception {
        var userId = UUID.randomUUID();
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("firstName", "Olena");
        body.put("lastName", "Koval6");

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.lastName").value("Last name must not contain a number"));
    }

    @Test
    @DisplayName("PATCH /me/profile — 200 when firstName/lastName carry hyphen + apostrophe but no digit (@NoDigits)")
    void should_return200_when_namesUseHyphenApostropheWithoutDigit() throws Exception {
        var userId = UUID.randomUUID();
        var firstName = "Анна-Марія";
        var lastName = "О’Коннор";

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(stubProfile(firstName, lastName, "+380671234567", null, null));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.firstName").value(firstName))
                .andExpect(jsonPath("$.data.lastName").value(lastName));
    }

    @Test
    @DisplayName("PATCH /me/profile — 200 when first/last name are omitted (optional — @NoDigits skips null)")
    void should_return200_when_namesOmitted() throws Exception {
        var userId = UUID.randomUUID();
        // Only bio supplied; firstName/lastName null. @NoDigits skips null so the body validates,
        // proving the constraint does not turn the optional name fields into required ones.
        var body = objectMapper.writeValueAsString(java.util.Map.of("bio", "Just a bio update"));

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(new MasterPublicProfileResponse(null, null, "+380670000000", "Just a bio update", null));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── Fix 6 — QA MEDIUM: bio exact-boundary ────────────────────────────────

    @Test
    @DisplayName("PATCH /me/profile — 200 when bio is exactly 2000 characters (max boundary)")
    void should_return200_when_bioIsExactly2000Characters() throws Exception {
        var userId = UUID.randomUUID();
        // 2000 Cyrillic chars — exactly at the @Size(max = 2000) ceiling. The @Pattern
        // ^[^\p{Cntrl}]*$ permits Cyrillic letters; this must pass and reach the service.
        String bio = "А".repeat(2000);

        when(userService.updateMasterProfile(eq(userId), any(MasterProfileUpdateRequest.class)))
                .thenReturn(stubProfile("+380671234567", bio, null));

        mockMvc.perform(patch(PATCH_PROFILE_URL)
                        .with(authenticatedAs(userId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody("+380671234567", bio, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.bio").value(bio));
    }
}
