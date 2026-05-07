package com.beautica.user;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.auth.filter.AuthRateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
@DisplayName("UserController — @WebMvcTest slice")
class UserControllerTest {

    // ── Pass-through filter overrides ─────────────────────────────────────────

    /**
     * Replaces both {@code @Component} filters with stateless pass-through beans so
     * the filter chain reaches the DispatcherServlet. Without this, Mockito mocks
     * these filters as no-ops and requests never reach any handler.
     */
    @TestConfiguration
    static class PassThroughFilters {

        @Bean
        @Primary
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
            // Pass-through: never parses a real JWT; the test injects auth directly
            // via SecurityMockMvcRequestPostProcessors.authentication().
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
            // Pass-through: rate limiting is not under test in a controller slice.
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
    }

    // ── Slice infrastructure ──────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Only production collaborator. Stub its responses per test. */
    @MockBean
    private UserService userService;

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
                "Jane", "Doe", "+380671234567", true, null
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
                "Oksana", "Kovalenko", null, true, null
        );
        when(userService.updateProfile(eq(userId), any(UpdateProfileRequest.class)))
                .thenReturn(updated);

        var body = new UpdateProfileRequest("Oksana", "Kovalenko", null);

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
        var body = new UpdateProfileRequest(tooLong, "Doe", null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(authenticatedAs(userId, "jane@example.com", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /me with no JWT → 401")
    void should_return401_when_patchProfileWithoutJwt() throws Exception {
        var body = new UpdateProfileRequest("Jane", "Doe", null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }
}
