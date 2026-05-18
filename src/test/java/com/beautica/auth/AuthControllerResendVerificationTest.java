package com.beautica.auth;

import com.beautica.auth.dto.RegistrationResponse;
import com.beautica.auth.dto.ResendVerificationRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.config.WebMvcTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for the {@code POST /api/v1/auth/resend-verification} endpoint.
 *
 * <p>Covers HTTP-layer concerns only: request validation, successful dispatch to
 * {@link AuthService}, and exception translation by
 * {@link com.beautica.common.exception.GlobalExceptionHandler}. No DB or real filter
 * chain is required — pass-through filters are provided by {@link WebMvcTestSupport}.
 *
 * <p>The inner {@link SecurityConfig} uses {@code anyRequest().permitAll()} because
 * {@code /api/v1/auth/resend-verification} is a public endpoint.
 */
@WebMvcTest(AuthController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("POST /api/v1/auth/resend-verification — controller slice")
class AuthControllerResendVerificationTest {

    private static final Logger log = LoggerFactory.getLogger(AuthControllerResendVerificationTest.class);
    private static final String RESEND_URL = "/api/v1/auth/resend-verification";

    // ── Security configuration ────────────────────────────────────────────────

    /**
     * Permit-all filter chain for the controller slice.
     *
     * <p>{@code /api/v1/auth/resend-verification} is a public endpoint; no authentication
     * token is required. CSRF is disabled to keep {@code MockMvc} POST requests simple,
     * and the session is stateless to match production behaviour.
     */
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
    @DisplayName("200 with RegistrationResponse when valid email is supplied")
    void should_return200_when_validEmailSent() throws Exception {
        var stubResponse = RegistrationResponse.of("user@example.com");
        log.debug("Arrange: stub authService.resendVerification() to return RegistrationResponse");

        when(authService.resendVerification(any(ResendVerificationRequest.class))).thenReturn(stubResponse);

        log.debug("Act: POST {} with valid email", RESEND_URL);
        mvc.perform(post(RESEND_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("user@example.com"));
    }

    @Test
    @DisplayName("400 when email field is blank")
    void should_return400_when_emailBlank() throws Exception {
        log.debug("Arrange: no mock needed — blank email fails @NotBlank before service is reached");

        log.debug("Act: POST {} with blank email", RESEND_URL);
        mvc.perform(post(RESEND_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("400 when email field has invalid format")
    void should_return400_when_emailInvalid() throws Exception {
        log.debug("Arrange: no mock needed — invalid email format fails @Email before service is reached");

        log.debug("Act: POST {} with non-email string", RESEND_URL);
        mvc.perform(post(RESEND_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("429 with Retry-After header when service throws ResendThrottledException")
    void should_return429WithRetryAfter_when_throttled() throws Exception {
        log.debug("Arrange: stub authService.resendVerification() to throw ResendThrottledException(30)");

        when(authService.resendVerification(any(ResendVerificationRequest.class)))
                .thenThrow(new ResendThrottledException(30L));

        log.debug("Act: POST {} with valid email that triggers throttle", RESEND_URL);
        mvc.perform(post(RESEND_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Please wait before requesting another code"));
    }
}
