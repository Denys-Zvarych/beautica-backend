package com.beautica.auth;

import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.RegistrationResponse;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.common.exception.EmailAlreadyRegisteredException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for the {@code POST /api/v1/auth/register} endpoint.
 *
 * <p>Covers HTTP-layer concerns only: request validation, happy-path dispatch to
 * {@link AuthService}, and the duplicate-email 409 path produced by
 * {@link EmailAlreadyRegisteredException} translated by
 * {@link com.beautica.common.exception.GlobalExceptionHandler}.
 *
 * <p>No real Spring Security filter chain or DB is required — pass-through filters
 * are provided by {@link WebMvcTestSupport} and authentication is not needed for
 * this unauthenticated endpoint.
 */
@WebMvcTest(AuthController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("POST /api/v1/auth/register — controller slice")
class AuthControllerRegisterTest {

    private static final Logger log = LoggerFactory.getLogger(AuthControllerRegisterTest.class);
    private static final String REGISTER_URL = "/api/v1/auth/register";

    // ── Security configuration ────────────────────────────────────────────────

    /**
     * Permit-all filter chain: {@code /api/v1/auth/register} is a public endpoint.
     * CSRF is disabled and the session is stateless to match production behaviour.
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

    private static final String VALID_BODY = """
            {
              "email": "new@beautica.test",
              "password": "Str0ngP@ss1!",
              "role": "CLIENT",
              "firstName": "Taras",
              "lastName": "Shevchenko",
              "phoneNumber": "+380501234567"
            }
            """;

    @Test
    @DisplayName("409 Conflict with EMAIL_ALREADY_REGISTERED code when AuthService throws EmailAlreadyRegisteredException")
    void should_return409_withEmailAlreadyRegisteredCode_when_authServiceThrowsDuplicateEmail() throws Exception {
        log.debug("Arrange: authService.register() is stubbed to throw EmailAlreadyRegisteredException");
        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new EmailAlreadyRegisteredException());

        log.debug("Act: POST {} with duplicate-email payload", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(EmailAlreadyRegisteredException.ERROR_CODE))
                .andExpect(jsonPath("$.message").value("Email already registered"));

        log.debug("Assert: verify service was called once (rate-limiter / guard consumed the call)");
        verify(authService).register(any(RegisterRequest.class));
    }

    @Test
    @DisplayName("400 Bad Request when role field is missing")
    void should_return400_when_roleIsMissing() throws Exception {
        log.debug("Arrange: no mock needed — missing role fails Bean Validation before service is reached");

        log.debug("Act: POST {} with missing role", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "new@beautica.test",
                                  "password": "Str0ngP@ss1!",
                                  "firstName": "Taras",
                                  "lastName": "Shevchenko"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        log.debug("Assert: service must NOT be invoked when Bean Validation fails");
        verify(authService, never()).register(any(RegisterRequest.class));
    }

    @Test
    @DisplayName("400 Bad Request when email field is blank")
    void should_return400_when_emailIsBlank() throws Exception {
        log.debug("Act: POST {} with blank email", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "",
                                  "password": "Str0ngP@ss1!",
                                  "role": "CLIENT"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(authService, never()).register(any(RegisterRequest.class));
    }

    // ── QA-CRITICAL: businessName @Pattern — control-char guard ──────────────

    @Test
    @DisplayName("400 Bad Request when businessName contains a control character (NUL byte)")
    void should_return400_when_businessNameContainsControlCharacter() throws Exception {
        log.debug("Arrange: no mock needed — control char in businessName fails @Pattern before service is reached");

        //   is the NUL control character; it matches \\p{Cntrl} and must be rejected.
        log.debug("Act: POST {} with businessName containing embedded NUL byte", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner-ctrl@beautica.test",
                                  "password": "Str0ngP@ss1!",
                                  "role": "SALON_OWNER",
                                  "firstName": "Olena",
                                  "lastName": "Kovalchuk",
                                  "businessName": "Valid Salon\\u0000"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        log.debug("Assert: service must NOT be invoked when Bean Validation fails");
        verify(authService, never()).register(any(RegisterRequest.class));
    }

    @Test
    @DisplayName("200 OK when businessName contains only printable Ukrainian text")
    void should_return200_when_businessNameIsPrintableUkrainianText() throws Exception {
        log.debug("Arrange: stub authService to return a minimal RegistrationResponse");
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(RegistrationResponse.of("owner-ua@beautica.test"));

        // Ukrainian text is entirely outside \\p{Cntrl} — @Pattern must pass.
        log.debug("Act: POST {} with businessName containing valid Ukrainian text", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner-ua@beautica.test",
                                  "password": "Str0ngP@ss1!",
                                  "role": "SALON_OWNER",
                                  "firstName": "Олена",
                                  "lastName": "Коваль",
                                  "phoneNumber": "+380501234567",
                                  "businessName": "Salon Ніжність"
                                }
                                """))
                .andExpect(status().isOk());
    }

    // ── QA-MEDIUM: RegisterRequest.firstName @Pattern — control-char guard ──────

    @Test
    @DisplayName("400 Bad Request when firstName contains a control character (NUL byte)")
    void should_return400_when_firstNameContainsControlCharacter() throws Exception {
        log.debug("Arrange: no mock needed — control char in firstName fails @Pattern before service is reached");

        // NUL byte in firstName — @Pattern(^[^\p{Cntrl}]*$) must reject at the controller boundary
        log.debug("Act: POST {} with firstName containing embedded NUL byte", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "ctrl-first@beautica.test",
                                  "password": "Str0ngP@ss1!",
                                  "role": "CLIENT",
                                  "firstName": "Taras\\u0000",
                                  "lastName": "Shevchenko"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        log.debug("Assert: service must NOT be invoked when Bean Validation fails");
        verify(authService, never()).register(any(RegisterRequest.class));
    }

    // ── QA: RegisterRequest firstName/lastName @NoDigits — number guard ────────

    @Test
    @DisplayName("400 Bad Request with errors.firstName when firstName contains a digit (@NoDigits)")
    void should_return400_when_firstNameContainsDigit() throws Exception {
        log.debug("Arrange: no mock needed — digit in firstName fails @NoDigits before service is reached");

        log.debug("Act: POST {} with firstName containing a digit", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "digit-first@beautica.test",
                                  "password": "Str0ngP@ss1!",
                                  "role": "CLIENT",
                                  "firstName": "Taras2",
                                  "lastName": "Shevchenko"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.firstName").value("First name must not contain a number"));

        log.debug("Assert: service must NOT be invoked when Bean Validation fails");
        verify(authService, never()).register(any(RegisterRequest.class));
    }

    @Test
    @DisplayName("400 Bad Request with errors.lastName when lastName contains a digit (@NoDigits)")
    void should_return400_when_lastNameContainsDigit() throws Exception {
        log.debug("Act: POST {} with lastName containing a digit", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "digit-last@beautica.test",
                                  "password": "Str0ngP@ss1!",
                                  "role": "CLIENT",
                                  "firstName": "Taras",
                                  "lastName": "Shevchenko1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.lastName").value("Last name must not contain a number"));

        verify(authService, never()).register(any(RegisterRequest.class));
    }

    @Test
    @DisplayName("200 OK when firstName/lastName carry a hyphen, apostrophe and space but no digit (@NoDigits)")
    void should_return200_when_namesUseHyphenApostropheSpaceWithoutDigit() throws Exception {
        log.debug("Arrange: stub authService to return a minimal RegistrationResponse");
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(RegistrationResponse.of("nodigit-name@beautica.test"));

        // Hyphen + apostrophe + space, all letters Cyrillic/Latin — @NoDigits must pass.
        log.debug("Act: POST {} with digit-free hyphen/apostrophe/space names", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "nodigit-name@beautica.test",
                                  "password": "Str0ngP@ss1!",
                                  "role": "CLIENT",
                                  "firstName": "Анна-Марія",
                                  "lastName": "О’Коннор",
                                  "phoneNumber": "+380501234567"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(authService).register(any(RegisterRequest.class));
    }

    @Test
    @DisplayName("400 Bad Request when password does not meet @StrongPassword policy")
    void should_return400_when_passwordIsWeak() throws Exception {
        log.debug("Act: POST {} with password that lacks uppercase", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@beautica.test",
                                  "password": "weakpassword1!",
                                  "role": "CLIENT"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(authService, never()).register(any(RegisterRequest.class));
    }

    // ── QA: RegisterRequest.phoneNumber @NotBlank guard ──────────────────────

    @Test
    @DisplayName("400 Bad Request when phoneNumber is blank (empty string)")
    void should_return400_when_phoneNumberIsBlank() throws Exception {
        log.debug("Arrange: no mock needed — blank phoneNumber fails @NotBlank before service is reached");

        log.debug("Act: POST {} with blank phoneNumber", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "new@beautica.test",
                                  "password": "Str0ngP@ss1!",
                                  "role": "CLIENT",
                                  "firstName": "Taras",
                                  "lastName": "Shevchenko",
                                  "phoneNumber": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        log.debug("Assert: service must NOT be invoked when Bean Validation fails");
        verify(authService, never()).register(any(RegisterRequest.class));
    }

    @Test
    @DisplayName("400 Bad Request when phoneNumber is null (omitted)")
    void should_return400_when_phoneNumberIsNull() throws Exception {
        log.debug("Arrange: no mock needed — null phoneNumber fails @NotBlank before service is reached");

        log.debug("Act: POST {} with phoneNumber omitted", REGISTER_URL);
        mvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "new@beautica.test",
                                  "password": "Str0ngP@ss1!",
                                  "role": "CLIENT",
                                  "firstName": "Taras",
                                  "lastName": "Shevchenko"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        log.debug("Assert: service must NOT be invoked when Bean Validation fails");
        verify(authService, never()).register(any(RegisterRequest.class));
    }
}
