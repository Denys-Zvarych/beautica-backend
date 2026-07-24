package com.beautica.user;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.PasswordResetService;
import com.beautica.auth.Role;
import com.beautica.config.WebMvcTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link UserController} — reference pattern for all future
 * controller slices in Beautica.
 *
 * <h2>Design notes for future developers</h2>
 * <ul>
 *   <li>{@code SecurityConfig} requires {@code JwtAuthenticationFilter},
 *       {@code AuthRateLimitFilter}, {@code JwtTokenProvider}, and the
 *       {@code app.frontend.base-url} property. Supply the property via
 *       {@code @TestPropertySource}. Replace the two filters with pass-through
 *       {@code @TestConfiguration} overrides so the filter chain stays intact —
 *       if those beans are {@code @MockBean}s they produce no-op mocks that swallow
 *       the request and return an empty 200, breaking all assertions.
 *       {@code JwtTokenProvider} IS safe to {@code @MockBean} because
 *       {@code JwtAuthenticationFilter} is replaced entirely.</li>
 *   <li>Authenticated requests inject a {@link UsernamePasswordAuthenticationToken}
 *       directly via {@code SecurityMockMvcRequestPostProcessors.authentication()}.
 *       {@code @WithMockUser} is NOT used here because the real controller reads
 *       {@code userId} from {@code authentication.getDetails()} — a field that
 *       {@code @WithMockUser} never populates.</li>
 *   <li>All state-changing requests ({@code PATCH}, {@code POST}, {@code PUT},
 *       {@code DELETE}) need {@code .with(csrf())} even though production config
 *       disables CSRF. The {@code @WebMvcTest} slice boots a fresh Spring Security
 *       context that re-enables CSRF by default before the custom
 *       {@code SecurityConfig} bean is applied; without the token MockMvc returns
 *       403 instead of the expected status.</li>
 * </ul>
 */
@WebMvcTest(UserController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("UserController — @WebMvcTest slice")
class UserControllerTest {

    // ── Slice infrastructure ──────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Only production collaborator. Stub its responses per test. */
    @MockBean
    private UserService userService;

    /** Required by {@link UserController}'s constructor for the change-password-otp endpoint. */
    @MockBean
    private PasswordResetService passwordResetService;

    /**
     * Required by {@code SecurityConfig} constructor (via {@code JwtAuthenticationFilter}).
     * The pass-through filter override above does not call {@code jwtTokenProvider} at all,
     * so this mock never needs to be configured.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Builds the same {@link UsernamePasswordAuthenticationToken} that the real
     * {@link JwtAuthenticationFilter} produces for a valid JWT:
     * <ul>
     *   <li>{@code principal} — email string</li>
     *   <li>{@code authorities} — {@code ROLE_<ROLE_NAME>}</li>
     *   <li>{@code details} — {@link UUID} user ID (read by
     *       {@link UserController#extractUserId})</li>
     * </ul>
     */
    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    // ── GET /api/v1/users/me ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /me with valid JWT → 200 and correct profile fields returned")
    void should_return200WithProfile_when_validJwt() throws Exception {
        var userId = UUID.randomUUID();
        var profile = new UserProfileResponse(
                userId, "jane@example.com", "CLIENT",
                "Jane", "Doe", "+380671234567",
                null, null,                   // cityId, districtId
                null,                         // oblastId
                null, null, null,             // cityName, oblastName, districtName
                null, null, null,             // street, buildingNo, locationNote
                null, null, null,                   // bio, instagram
                true, false, null
        );
        when(userService.getProfile(userId)).thenReturn(profile);

        mockMvc.perform(get("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.email").value("jane@example.com"))
                .andExpect(jsonPath("$.data.firstName").value("Jane"))
                .andExpect(jsonPath("$.data.lastName").value("Doe"))
                .andExpect(jsonPath("$.data.role").value("CLIENT"));
    }

    @Test
    @DisplayName("GET /me with no JWT → 401")
    void should_return401_when_getProfileWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── PATCH /api/v1/users/me ────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /me with valid body → 200 and updated profile returned")
    void should_return200_when_patchProfileWithValidBody() throws Exception {
        var userId = UUID.randomUUID();
        var updated = new UserProfileResponse(
                userId, "jane@example.com", "CLIENT",
                "Oksana", "Kovalenko", null,
                null, null,                   // cityId, districtId
                null,                         // oblastId
                null, null, null,             // cityName, oblastName, districtName
                null, null, null,             // street, buildingNo, locationNote
                null, null, null,                   // bio, instagram
                true, false, null
        );
        when(userService.updateProfile(eq(userId), any(UpdateProfileRequest.class)))
                .thenReturn(updated);

        var body = new UpdateProfileRequest("Oksana", "Kovalenko", null,
                null, null, null, null, null, null, null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.firstName").value("Oksana"))
                .andExpect(jsonPath("$.data.lastName").value("Kovalenko"));
    }

    @Test
    @DisplayName("PATCH /me with firstName exceeding 100 chars → 400 with validation error")
    void should_return400_when_firstNameExceeds100Chars() throws Exception {
        var userId = UUID.randomUUID();
        // 101 characters — violates @Size(max = 100)
        var tooLong = "A".repeat(101);
        var body = new UpdateProfileRequest(tooLong, "Doe", null,
                null, null, null, null, null, null, null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /me with a control-char locationNote → clean 400 (not 500) (§A)")
    void should_return400_when_locationNoteHasControlChar() throws Exception {
        var userId = UUID.randomUUID();
        // Embedded NUL control char — violates @Pattern("^[^\\p{Cntrl}]*$").
        // Without the pattern this reaches the DB and surfaces as a 500, not a
        // clean 400. The   Java escape keeps the source file ASCII-clean;
        // Jackson serialises it as the JSON   escape and deserialises it
        // back to the literal control char, so the Bean Validation @Pattern
        // fires at the controller boundary.
        var locationNoteWithControlChar = "near the park  ";
        var body = new UpdateProfileRequest("Jane", "Doe", null,
                null, null, null, null, locationNoteWithControlChar, null, null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /me — 400 when firstName contains a control character (NUL byte)")
    void should_return400_when_firstNameContainsControlCharacter() throws Exception {
        var userId = UUID.randomUUID();
        // Embedded NUL in firstName — @Pattern(^[^\p{Cntrl}]*$) on UpdateProfileRequest
        // must reject at the controller boundary before UserService is reached.
        var body = "{\"firstName\":\"Oksana\\u0000\",\"lastName\":\"Kovalenko\"}";

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .updateProfile(any(UUID.class), any(UpdateProfileRequest.class));
    }

    // ── UpdateProfileRequest readable @Size / @Pattern message contract (regression) ─
    // The address fields gained explicit message= strings. @RequestBody violations
    // surface via MethodArgumentNotValidException → GlobalExceptionHandler.handleValidation,
    // which keys the readable message by the DTO field name in the top-level errors map.
    // These lock the exact per-field strings the mobile client renders inline.

    @Test
    @DisplayName("PATCH /me — errors.street carries the readable @Size message when street exceeds 255 chars (regression)")
    void should_returnReadableSizeMessage_when_streetExceeds255Chars() throws Exception {
        var userId = UUID.randomUUID();
        // street is index 5: (firstName, lastName, phoneNumber, cityId, districtId, street, buildingNo, locationNote)
        var tooLongStreet = "A".repeat(256);
        var body = new UpdateProfileRequest("Jane", "Doe", null,
                null, null, tooLongStreet, null, null, null, null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.street").value("Street must be at most 255 characters"));
    }

    @Test
    @DisplayName("PATCH /me — errors.street carries the readable @Pattern message when street contains a control character (regression)")
    void should_returnReadablePatternMessage_when_streetHasControlCharacter() throws Exception {
        var userId = UUID.randomUUID();
        // Embedded NUL in street — @Pattern(^[^\p{Cntrl}]*$) must reject at the controller boundary.
        // Raw JSON keeps the source file ASCII-clean; Jackson decodes   to the literal control char.
        var body = "{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"street\":\"Main St\\u0000\"}";

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.street").value("Street must not contain control characters"));

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .updateProfile(any(UUID.class), any(UpdateProfileRequest.class));
    }

    // ── Client locality stays OPTIONAL (Phase 10.6-reversal client-path guard) ────
    // The Phase 10.6 reversal made street + buildingNo @NotBlank on the PROVIDER DTOs
    // (IndependentMasterUpdateRequest / Create/UpdateSalonRequest) ONLY. The CLIENT
    // self-service path (PATCH /users/me → UpdateProfileRequest) must NOT inherit that
    // constraint: a client may save a city-only (or city+district) locality with no
    // structured address. This test pins that invariant explicitly with a populated
    // cityId/districtId and null street/buildingNo — it would fail the instant an
    // accidental @NotBlank leaked onto UpdateProfileRequest.street/buildingNo.

    @Test
    @DisplayName("PATCH /me — 200 when CLIENT saves city+district only, no street/buildingNo (client locality stays optional)")
    void should_return200_when_clientSavesCityOnlyWithoutStreetOrBuilding() throws Exception {
        var userId = UUID.randomUUID();
        var cityId = UUID.randomUUID();
        var districtId = UUID.randomUUID();
        var updated = new UserProfileResponse(
                userId, "jane@example.com", "CLIENT",
                "Jane", "Doe", null,
                cityId, districtId,           // cityId, districtId — persisted for a client
                null,                         // oblastId
                "Київ", null, "Печерський",   // cityName, oblastName, districtName
                null, null, null,             // street, buildingNo, locationNote — all stay null
                null, null, null,             // bio, instagram, professionalTitle
                true, false, null
        );
        when(userService.updateProfile(eq(userId), any(UpdateProfileRequest.class)))
                .thenReturn(updated);

        // City + district set, but NO street / buildingNo — the realistic client
        // "set my discovery city" save. @NotBlank must not exist on this DTO, so the
        // request must reach the service and echo the persisted city.
        var body = new UpdateProfileRequest(null, null, null,
                cityId, districtId, null, null, null, null, null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cityId").value(cityId.toString()))
                .andExpect(jsonPath("$.data.districtId").value(districtId.toString()))
                .andExpect(jsonPath("$.data.street").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.buildingNo").value(org.hamcrest.Matchers.nullValue()));

        org.mockito.Mockito.verify(userService)
                .updateProfile(eq(userId), any(UpdateProfileRequest.class));
    }

    // ── UpdateProfileRequest @NoDigits regression (MEDIUM fix) ────────────────────
    // PATCH /users/me previously bypassed the no-digit person-name rule. @NoDigits is
    // now wired onto firstName/lastName; these lock the controller-boundary behaviour:
    // a digit-bearing name is rejected with the readable per-field error string, a
    // name with the allowed punctuation (hyphen/apostrophe/space) is accepted, and a
    // null name pair still passes because both fields are optional on this edit DTO.

    @Test
    @DisplayName("PATCH /me — 400 with errors.firstName @NoDigits message when firstName contains a digit (regression)")
    void should_return400WithFirstNameError_when_firstNameContainsDigit() throws Exception {
        var userId = UUID.randomUUID();
        var body = new UpdateProfileRequest("Oksana2", "Kovalenko", null,
                null, null, null, null, null, null, null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.firstName").value("First name must not contain a number"));

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .updateProfile(any(UUID.class), any(UpdateProfileRequest.class));
    }

    @Test
    @DisplayName("PATCH /me — 400 with errors.lastName @NoDigits message when lastName contains a digit (regression)")
    void should_return400WithLastNameError_when_lastNameContainsDigit() throws Exception {
        var userId = UUID.randomUUID();
        var body = new UpdateProfileRequest("Oksana", "Kovalenko9", null,
                null, null, null, null, null, null, null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.lastName").value("Last name must not contain a number"));

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .updateProfile(any(UUID.class), any(UpdateProfileRequest.class));
    }

    @Test
    @DisplayName("PATCH /me — 200 when names use allowed punctuation (hyphen/apostrophe/space), no @NoDigits trip")
    void should_return200_when_nameHasHyphenApostropheSpaceButNoDigit() throws Exception {
        var userId = UUID.randomUUID();
        var updated = new UserProfileResponse(
                userId, "jane@example.com", "CLIENT",
                "Анна-Марія", "О’Коннор", null,
                null, null,                   // cityId, districtId
                null,                         // oblastId
                null, null, null,             // cityName, oblastName, districtName
                null, null, null,             // street, buildingNo, locationNote
                null, null, null,                   // bio, instagram
                true, false, null
        );
        when(userService.updateProfile(eq(userId), any(UpdateProfileRequest.class)))
                .thenReturn(updated);

        var body = new UpdateProfileRequest("Анна-Марія", "О’Коннор", null,
                null, null, null, null, null, null, null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.firstName").value("Анна-Марія"))
                .andExpect(jsonPath("$.data.lastName").value("О’Коннор"));
    }

    @Test
    @DisplayName("PATCH /me — 200 when both first and last name are null (optional fields, no @NoDigits trip)")
    void should_return200_when_bothNamesNull() throws Exception {
        var userId = UUID.randomUUID();
        var updated = new UserProfileResponse(
                userId, "jane@example.com", "CLIENT",
                "Jane", "Doe", "+380671234567",
                null, null,                   // cityId, districtId
                null,                         // oblastId
                null, null, null,             // cityName, oblastName, districtName
                null, null, null,             // street, buildingNo, locationNote
                null, null, null,                   // bio, instagram
                true, false, null
        );
        when(userService.updateProfile(eq(userId), any(UpdateProfileRequest.class)))
                .thenReturn(updated);

        // A null first/last name pair is a valid no-op for these fields — @NoDigits
        // (like @Size) must not fire on null, so the request reaches the service.
        var body = new UpdateProfileRequest(null, null, "+380671234567",
                null, null, null, null, null, null, null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(userService)
                .updateProfile(eq(userId), any(UpdateProfileRequest.class));
    }

    // ── Phase 19.6 — instagram field on UpdateProfileRequest ──────────────────
    // The @Size(max=100) + @Pattern handle/URL contract is reused verbatim from the
    // master contacts-edit side. These lock the controller boundary: a valid handle
    // reaches the service and the (service-normalised) value is echoed; an illegal
    // handle is rejected with a clean 400 before the service is invoked.

    @Test
    @DisplayName("PATCH /me — 200 with a valid instagram handle; body reflects the normalized value")
    void should_return200_when_instagramHandleIsValid() throws Exception {
        var userId = UUID.randomUUID();
        var updated = new UserProfileResponse(
                userId, "jane@example.com", "CLIENT",
                "Jane", "Doe", null,
                null, null,                   // cityId, districtId
                null,                         // oblastId
                null, null, null,             // cityName, oblastName, districtName
                null, null, null,             // street, buildingNo, locationNote
                null, "beauty_studio", null,        // bio, instagram (normalized — @ stripped by the service)
                true, false, null
        );
        when(userService.updateProfile(eq(userId), any(UpdateProfileRequest.class)))
                .thenReturn(updated);

        // Client sends the @-prefixed form; the service strips the @ and the
        // response carries the canonical handle the slice asserts on.
        var body = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, "@beauty_studio", null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.instagram").value("beauty_studio"));
    }

    @Test
    @DisplayName("PATCH /me — 200 with an empty-string instagram (the CLEAR signal); @Pattern ^$ arm accepts it")
    void should_return200_when_instagramIsEmptyStringClearSignal() throws Exception {
        var userId = UUID.randomUUID();
        var updated = new UserProfileResponse(
                userId, "jane@example.com", "CLIENT",
                "Jane", "Doe", null,
                null, null,                   // cityId, districtId
                null,                         // oblastId
                null, null, null,             // cityName, oblastName, districtName
                null, null, null,             // street, buildingNo, locationNote
                null, null, null,                   // instagram cleared by the service (blank → null)
                true, false, null
        );
        when(userService.updateProfile(eq(userId), any(UpdateProfileRequest.class)))
                .thenReturn(updated);

        var body = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, "", null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(userService)
                .updateProfile(eq(userId), any(UpdateProfileRequest.class));
    }

    @Test
    @DisplayName("PATCH /me — 400 when instagram contains an illegal character (space); service never reached")
    void should_return400_when_instagramHasIllegalSpace() throws Exception {
        var userId = UUID.randomUUID();
        // A space is not in [A-Za-z0-9._]; the @Pattern handle arm rejects it and the
        // URL arm does not match either → clean 400 at the controller boundary.
        var body = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, "@beauty studio", null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.instagram").isNotEmpty());

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .updateProfile(any(UUID.class), any(UpdateProfileRequest.class));
    }

    @Test
    @DisplayName("PATCH /me — 400 when instagram violates the @Pattern (illegal angle-bracket char); service never reached")
    void should_return400_when_instagramViolatesPattern() throws Exception {
        var userId = UUID.randomUUID();
        // '<' is outside the handle char class [A-Za-z0-9._] and does not begin a valid
        // URL form either; the @Pattern rejects it at the controller boundary → clean 400.
        var body = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, "beauty<studio", null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.instagram").isNotEmpty());

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .updateProfile(any(UUID.class), any(UpdateProfileRequest.class));
    }

    @Test
    @DisplayName("PATCH /me — 400 when instagram exceeds 100 characters (over the @Size/@Column bound)")
    void should_return400_when_instagramExceeds100Chars() throws Exception {
        var userId = UUID.randomUUID();
        // 101 chars of legal handle characters — passes the handle arm's char class
        // intent but violates @Size(max=100); must be a clean 400, not a 500 (§A).
        var tooLong = "a".repeat(101);
        var body = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, tooLong, null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.instagram").isNotEmpty());

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .updateProfile(any(UUID.class), any(UpdateProfileRequest.class));
    }

    @Test
    @DisplayName("PATCH /me — 400 when instagram contains a control character (NUL byte)")
    void should_return400_when_instagramHasControlChar() throws Exception {
        var userId = UUID.randomUUID();
        // Raw JSON keeps this source file ASCII-clean; Jackson decodes   to the
        // literal control char, which neither @Pattern arm accepts → clean 400.
        var body = "{\"instagram\":\"beauty\\u0000\"}";

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .updateProfile(any(UUID.class), any(UpdateProfileRequest.class));
    }

    @Test
    @DisplayName("PATCH /me with no JWT → 401")
    void should_return401_when_patchProfileWithoutJwt() throws Exception {
        var body = new UpdateProfileRequest("Jane", "Doe", null,
                null, null, null, null, null, null, null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/users/me/change-password/request-otp ────────────────────
    // Authenticated "change password from settings" entry point (Phase A7). Rate-limit
    // (429) behaviour for changePasswordOtpBuckets is covered separately by
    // RateLimitConfigTest and AuthRateLimitFilterTest — this slice only asserts the
    // controller's own auth-boundary and delegation behaviour.

    @Test
    @DisplayName("POST /me/change-password/request-otp with valid JWT → 200, delegates to requestResetForUserId(userId)")
    void should_return200_when_requestChangePasswordOtpWithValidJwt() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/users/me/change-password/request-otp")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(passwordResetService).requestResetForUserId(userId);
    }

    @Test
    @DisplayName("POST /me/change-password/request-otp with no JWT → 401, service never invoked")
    void should_return401_when_requestChangePasswordOtpWithoutJwt() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/change-password/request-otp")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        org.mockito.Mockito.verify(passwordResetService, org.mockito.Mockito.never())
                .requestResetForUserId(any(UUID.class));
    }
}
