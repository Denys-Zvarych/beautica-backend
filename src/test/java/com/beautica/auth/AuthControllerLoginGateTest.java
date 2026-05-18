package com.beautica.auth;

import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.exception.EmailNotVerifiedException;
import com.beautica.config.WebMvcTestSupport;
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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for the login-gate behaviour introduced in Phase 1.7.
 *
 * <p>Covers HTTP-layer concerns: correct HTTP status (403), structured response shape
 * ({@code success=false}, {@code data.code="EMAIL_NOT_VERIFIED"}, {@code data.email}),
 * and basic request validation (blank email → 400). No real Spring Security filter
 * chain or DB is required — pass-through filters are provided by
 * {@link WebMvcTestSupport} and the endpoint is public.
 */
@WebMvcTest(AuthController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("POST /api/v1/auth/login — login gate controller slice (Phase 1.7)")
class AuthControllerLoginGateTest {

    private static final String LOGIN_URL = "/api/v1/auth/login";

    // ── Security configuration ────────────────────────────────────────────────

    @TestConfiguration
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    // ── Slice infrastructure ──────────────────────────────────────────────────

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private InviteService inviteService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return 403 with EMAIL_NOT_VERIFIED code when service throws EmailNotVerifiedException")
    void should_return403WithEmailNotVerified_when_serviceThrowsEmailNotVerifiedException() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new EmailNotVerifiedException("user@example.com"));

        mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"anypassword"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.data.email").value("user@example.com"));
    }

    @Test
    @DisplayName("should return 400 when email is blank")
    void should_return400_when_emailBlank() throws Exception {
        mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"","password":"anypassword"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
