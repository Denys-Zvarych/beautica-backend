package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.VerifyEmailRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.VerificationException;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for the {@code POST /api/v1/auth/verify-email} endpoint.
 *
 * <p>Covers HTTP-layer concerns only: request validation, successful dispatch to
 * {@link AuthService}, and service-exception translation by
 * {@link com.beautica.common.exception.GlobalExceptionHandler}. No real Spring
 * Security filter chain or DB is required — pass-through filters are provided by
 * {@link WebMvcTestSupport} and authentication is not needed for this unauthenticated
 * endpoint.
 *
 * <p>The inner {@link SecurityConfig} uses {@code anyRequest().permitAll()} because
 * {@code /api/v1/auth/verify-email} is a public endpoint; adding role guards here
 * would mask missing-auth regressions in the production filter chain.
 */
@WebMvcTest(AuthController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("POST /api/v1/auth/verify-email — controller slice")
class AuthControllerVerifyEmailTest {

    private static final Logger log = LoggerFactory.getLogger(AuthControllerVerifyEmailTest.class);
    private static final String VERIFY_EMAIL_URL = "/api/v1/auth/verify-email";

    // ── Security configuration ────────────────────────────────────────────────

    /**
     * Permit-all filter chain for the controller slice.
     *
     * <p>{@code /api/v1/auth/verify-email} is a public endpoint; no authentication
     * token is required. CSRF is disabled to keep {@code MockMvc} POST requests
     * simple, and the session is stateless to match production behaviour.
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
    @DisplayName("200 with AuthResponse when valid email and 6-digit code are supplied")
    void should_return200_when_verifyEmailWithValidBody() throws Exception {
        var stubUserId = UUID.randomUUID();
        var stubResponse = AuthResponse.of(
                "stub-access-token", "stub-refresh-token",
                stubUserId, "user@example.com", Role.CLIENT);
        log.debug("Arrange: stub authService.verifyEmail() to return AuthResponse for user@example.com");

        when(authService.verifyEmail(any(VerifyEmailRequest.class))).thenReturn(stubResponse);

        log.debug("Act: POST {} with valid email and 6-digit code", VERIFY_EMAIL_URL);
        mvc.perform(post(VERIFY_EMAIL_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","code":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    @Test
    @DisplayName("400 when email field is blank")
    void should_return400_when_verifyEmailWithBlankEmail() throws Exception {
        log.debug("Arrange: no mock needed — blank email fails Bean Validation before service is reached");

        log.debug("Act: POST {} with blank email", VERIFY_EMAIL_URL);
        mvc.perform(post(VERIFY_EMAIL_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"","code":"123456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("400 when email field has invalid format")
    void should_return400_when_verifyEmailWithInvalidEmailFormat() throws Exception {
        log.debug("Arrange: no mock needed — invalid email format fails @Email validation before service is reached");

        log.debug("Act: POST {} with non-email value in email field", VERIFY_EMAIL_URL);
        mvc.perform(post(VERIFY_EMAIL_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","code":"123456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("400 when code is only 5 digits (below the 6-digit minimum)")
    void should_return400_when_verifyEmailWithFiveDigitCode() throws Exception {
        log.debug("Arrange: no mock needed — 5-digit code violates @Size(min=6,max=6) before service is reached");

        log.debug("Act: POST {} with 5-digit code", VERIFY_EMAIL_URL);
        mvc.perform(post(VERIFY_EMAIL_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","code":"12345"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("400 when code contains non-numeric characters")
    void should_return400_when_verifyEmailWithNonNumericCode() throws Exception {
        log.debug("Arrange: no mock needed — alphabetic code violates @Pattern(^[0-9]{6}$) before service is reached");

        log.debug("Act: POST {} with alphabetic code", VERIFY_EMAIL_URL);
        mvc.perform(post(VERIFY_EMAIL_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","code":"abcdef"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("400 when code field is blank")
    void should_return400_when_verifyEmailWithBlankCode() throws Exception {
        log.debug("Arrange: no mock needed — blank code fails @NotBlank before service is reached");

        log.debug("Act: POST {} with blank code", VERIFY_EMAIL_URL);
        mvc.perform(post(VERIFY_EMAIL_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","code":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("400 with VerificationErrorResponse containing code=INVALID_CODE when service throws VerificationException")
    void should_return400WithVerificationCode_when_serviceThrowsVerificationException() throws Exception {
        log.debug("Arrange: stub authService.verifyEmail() to throw VerificationException(INVALID_CODE)");

        when(authService.verifyEmail(any(VerifyEmailRequest.class)))
                .thenThrow(new VerificationException(VerificationException.Code.INVALID_CODE));

        log.debug("Act: POST {} with valid body that triggers INVALID_CODE from service", VERIFY_EMAIL_URL);
        mvc.perform(post(VERIFY_EMAIL_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","code":"123456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("INVALID_CODE"));
    }
}
