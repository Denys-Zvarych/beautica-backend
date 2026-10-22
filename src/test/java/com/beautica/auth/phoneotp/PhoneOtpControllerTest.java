package com.beautica.auth.phoneotp;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.GlobalExceptionHandler;
import com.beautica.config.WebMvcTestSupport;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link PhoneOtpController}. The inner
 * {@code @TestConfiguration} mirrors the production SecurityConfig contract:
 * {@code POST /api/v1/book/otp/**} is {@code permitAll}, everything else authenticated.
 * {@link GlobalExceptionHandler} is imported so domain exceptions map to real statuses.
 */
@WebMvcTest(PhoneOtpController.class)
@Import({WebMvcTestSupport.class, GlobalExceptionHandler.class})
@DisplayName("PhoneOtpController — @WebMvcTest slice")
class PhoneOtpControllerTest {

    @TestConfiguration
    static class SecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.POST, "/api/v1/book/otp/**").permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PhoneOtpService phoneOtpService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // Phase 382: the controller's new collaborator — a slice without it fails to start.
    @MockBean
    private com.beautica.auth.turnstile.TurnstileVerifier turnstileVerifier;

    private static final String VALID_PHONE = "+380671234567";

    @Test
    @DisplayName("POST /book/otp/send — 200 without auth, delegates to service")
    void should_return200_when_sendValidPhone() throws Exception {
        mockMvc.perform(post("/api/v1/book/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + VALID_PHONE + "\",\"privacyAccepted\":true,\"privacyPolicyVersion\":\"2026-10-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /book/otp/send — 400 when phone is not E.164")
    void should_return400_when_sendBadPhone() throws Exception {
        mockMvc.perform(post("/api/v1/book/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"12345\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(phoneOtpService);
    }

    @Test
    @DisplayName("POST /book/otp/send — Turnstile gets the body token and the resolved IP BEFORE the SMS (phase 382)")
    void should_verifyTurnstileBeforeSendingOtp_when_sendValid() throws Exception {
        mockMvc.perform(post("/api/v1/book/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "203.0.113.7")
                        .content("{\"phone\":\"" + VALID_PHONE + "\",\"privacyAccepted\":true,"
                                + "\"privacyPolicyVersion\":\"2026-10-20\",\"turnstileToken\":\"tok-1\"}"))
                .andExpect(status().isOk());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(turnstileVerifier, phoneOtpService);
        order.verify(turnstileVerifier).verify("tok-1", "203.0.113.7");
        order.verify(phoneOtpService).sendOtp(VALID_PHONE);
    }

    @Test
    @DisplayName("POST /book/otp/send — a Turnstile rejection answers 400 'Verification failed' and sendOtp is never called")
    void should_return400AndNeverSend_when_turnstileRejects() throws Exception {
        org.mockito.Mockito.doThrow(new com.beautica.common.exception.TurnstileRejectedException())
                .when(turnstileVerifier).verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        mockMvc.perform(post("/api/v1/book/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + VALID_PHONE + "\",\"privacyAccepted\":true,"
                                + "\"privacyPolicyVersion\":\"2026-10-20\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Verification failed"));

        verifyNoInteractions(phoneOtpService);
    }

    @Test
    @DisplayName("POST /book/otp/send — Turnstile unavailable answers 503 with the allow-listed message and sendOtp is never called")
    void should_return503AndNeverSend_when_turnstileUnavailable() throws Exception {
        org.mockito.Mockito.doThrow(new com.beautica.common.exception.BusinessException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        com.beautica.common.exception.ServiceUnavailableMessages.TURNSTILE_UNAVAILABLE))
                .when(turnstileVerifier).verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        mockMvc.perform(post("/api/v1/book/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + VALID_PHONE + "\",\"privacyAccepted\":true,"
                                + "\"privacyPolicyVersion\":\"2026-10-20\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value(com.beautica.common.exception.ServiceUnavailableMessages.TURNSTILE_UNAVAILABLE));

        verifyNoInteractions(phoneOtpService);
    }

    @Test
    @DisplayName("POST /book/otp/send — an oversized turnstileToken is a 400 before the verifier is reached")
    void should_return400_when_turnstileTokenLongerThan2048() throws Exception {
        mockMvc.perform(post("/api/v1/book/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + VALID_PHONE + "\",\"privacyAccepted\":true,"
                                + "\"privacyPolicyVersion\":\"2026-10-20\",\"turnstileToken\":\""
                                + "a".repeat(2049) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(turnstileVerifier, phoneOtpService);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"phone\":\"+380671234567\"}",
            "{\"phone\":\"+380671234567\",\"privacyAccepted\":false,\"privacyPolicyVersion\":\"2026-10-20\"}",
            "{\"phone\":\"+380671234567\",\"privacyAccepted\":true}",
            "{\"phone\":\"+380671234567\",\"privacyAccepted\":true,\"privacyPolicyVersion\":\"v1\"}",
            "{\"phone\":\"+380671234567\",\"privacyAccepted\":true,\"privacyPolicyVersion\":\"2026-10-20; DROP\"}"
    })
    @DisplayName("POST /book/otp/send — 400 and no SMS when consent is missing, false or the version is malformed (Phase 381)")
    void should_return400AndNotSend_when_consentMissingOrInvalid(String body) throws Exception {
        mockMvc.perform(post("/api/v1/book/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(phoneOtpService);
    }

    @Test
    @DisplayName("POST /book/otp/verify — 200 with guestToken in body")
    void should_return200WithGuestToken_when_verifyValid() throws Exception {
        when(phoneOtpService.verifyOtp(eq(VALID_PHONE), eq("482913"))).thenReturn("guest.jwt.token");

        mockMvc.perform(post("/api/v1/book/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + VALID_PHONE + "\",\"code\":\"482913\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.guestToken").value("guest.jwt.token"));
    }

    @Test
    @DisplayName("POST /book/otp/verify — 400 when code is not 6 digits")
    void should_return400_when_verifyBadCode() throws Exception {
        mockMvc.perform(post("/api/v1/book/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + VALID_PHONE + "\",\"code\":\"12\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(phoneOtpService);
    }

    @Test
    @DisplayName("POST /book/otp/verify — 401 generic when service rejects the code")
    void should_return401_when_serviceRejectsCode() throws Exception {
        when(phoneOtpService.verifyOtp(anyString(), anyString()))
                .thenThrow(new BusinessException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid or expired code"));

        mockMvc.perform(post("/api/v1/book/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + VALID_PHONE + "\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());
    }
}
