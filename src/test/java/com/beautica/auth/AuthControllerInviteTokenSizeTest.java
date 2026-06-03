package com.beautica.auth;

import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.config.WebMvcTestSupport;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} regression slice for the {@code @Size} cap on the
 * {@code token} {@code @RequestParam} of {@code GET /api/v1/auth/invite/validate}.
 *
 * <p><b>Originating change.</b> The cap was raised {@code 200 → 512} with the
 * readable message {@code "Token is invalid"}. Before the change a legitimate
 * invite token in the 201–512 char band was rejected with a 400 at the size check
 * (the request never reached {@link InviteService#previewInvite}). The cap lives on
 * a {@code @RequestParam}, and {@code AuthController} is {@code @Validated}, so the
 * violation surfaces as a {@code ConstraintViolationException} → {@code 400} with
 * the leaf-keyed {@code errors.token} message (see {@code GlobalExceptionHandler}).
 *
 * <p>This is a controller-slice (not an IT): the change is purely the param
 * constraint + message — no DB or real invite row is needed. {@link InviteService}
 * is mocked so the test can prove the request now <em>reaches</em> the service for a
 * 256-char token (cap raised) and is <em>blocked before</em> the service for a
 * 513-char token (cap enforced with the readable message).
 */
@WebMvcTest(AuthController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("GET /api/v1/auth/invite/validate — token @Size(max=512) contract (regression)")
class AuthControllerInviteTokenSizeTest {

    private static final Logger log = LoggerFactory.getLogger(AuthControllerInviteTokenSizeTest.class);
    private static final String VALIDATE_URL = "/api/v1/auth/invite/validate";

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

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private InviteService inviteService;

    @MockBean
    private PasswordResetService passwordResetService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Cap raised: a 256-char token now passes the size check and reaches the service ─

    @Test
    @DisplayName("token of 256 chars (>200, ≤512) passes the @Size check and is delegated to InviteService — proves the cap was raised")
    void should_delegateToService_when_tokenIs256Chars() throws Exception {
        String token = "a".repeat(256);
        var preview = new InvitePreviewResponse(
                "master@beautica.test", Role.SALON_MASTER,
                Instant.now().plus(7, ChronoUnit.DAYS));
        when(inviteService.previewInvite(eq(token))).thenReturn(preview);

        log.debug("Act: GET invite/validate with a 256-char token — must clear @Size(max=512) and reach previewInvite");
        mvc.perform(get(VALIDATE_URL).param("token", token).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.invitedEmail").value("master@beautica.test"))
                .andExpect(jsonPath("$.data.role").value("SALON_MASTER"));

        verify(inviteService).previewInvite(eq(token));
    }

    @Test
    @DisplayName("token of exactly 512 chars passes the @Size check (boundary) and is delegated to InviteService")
    void should_delegateToService_when_tokenIsExactly512Chars() throws Exception {
        String token = "b".repeat(512);
        var preview = new InvitePreviewResponse(
                "edge@beautica.test", Role.SALON_ADMIN,
                Instant.now().plus(1, ChronoUnit.DAYS));
        when(inviteService.previewInvite(eq(token))).thenReturn(preview);

        log.debug("Act: GET invite/validate with a 512-char token — upper boundary must be accepted");
        mvc.perform(get(VALIDATE_URL).param("token", token).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(inviteService).previewInvite(eq(token));
    }

    // ── Cap enforced: a 513-char token is blocked before the service with the readable message ─

    @Test
    @DisplayName("token of 513 chars (>512) is rejected with 400, errors.token = \"Token is invalid\", and never reaches InviteService")
    void should_return400WithReadableMessage_when_tokenExceeds512Chars() throws Exception {
        String token = "c".repeat(513);

        log.debug("Act: GET invite/validate with a 513-char token — must 400 at @Size(max=512) with readable message");
        mvc.perform(get(VALIDATE_URL).param("token", token).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.token").value("Token is invalid"));

        verify(inviteService, never()).previewInvite(any());
    }
}
