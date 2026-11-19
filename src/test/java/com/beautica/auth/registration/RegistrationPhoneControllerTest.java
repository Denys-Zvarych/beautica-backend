package com.beautica.auth.registration;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.appcheck.AppCheckGuard;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.GlobalExceptionHandler;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.common.exception.VerificationException;
import com.beautica.config.WebMvcTestSupport;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link RegistrationPhoneController} (AUTH-41, phone-first hardening): status and
 * {@code data.code} per error of the registration phone OTP send / verify, as the app maps them.
 */
@WebMvcTest(RegistrationPhoneController.class)
@Import({WebMvcTestSupport.class, GlobalExceptionHandler.class})
@DisplayName("RegistrationPhoneController — @WebMvcTest slice")
class RegistrationPhoneControllerTest {

    @TestConfiguration
    static class SecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
                throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/register/phone/otp/**").permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, exc) ->
                            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    private static final String SEND = "/api/v1/auth/register/phone/otp/send";
    private static final String VERIFY = "/api/v1/auth/register/phone/otp/verify";
    private static final String PHONE = "+380671234567";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RegistrationPhoneService registrationPhoneService;

    @MockBean
    private AppCheckGuard appCheckGuard;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private ResultActions send(String phone) throws Exception {
        return mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
    }

    private ResultActions verify(String code) throws Exception {
        return mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + PHONE + "\",\"code\":\"" + code + "\"}"));
    }

    @Test
    @DisplayName("should_return200_when_sendAccepted")
    void should_return200_when_sendAccepted() throws Exception {
        send(PHONE).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("should_return400PhoneNotUaMobile_when_numberNotUaMobile")
    void should_return400PhoneNotUaMobile_when_numberNotUaMobile() throws Exception {
        doThrow(new IdentityException(IdentityException.Code.PHONE_NOT_UA_MOBILE))
                .when(registrationPhoneService).sendCode(anyString(), any());

        send("+380441234567").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("PHONE_NOT_UA_MOBILE"));
    }

    @Test
    @DisplayName("should_return429WithRetryAfter_when_resendCooldownRunning")
    void should_return429WithRetryAfter_when_resendCooldownRunning() throws Exception {
        doThrow(new ResendThrottledException(42)).when(registrationPhoneService).sendCode(anyString(), any());

        send(PHONE).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "42"));
    }

    @Test
    @DisplayName("should_return429_when_perPhoneLimitReached")
    void should_return429_when_perPhoneLimitReached() throws Exception {
        doThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "OTP limit exceeded"))
                .when(registrationPhoneService).sendCode(anyString(), any());

        send(PHONE).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("should_return403AppCheckFailedAndSendNothing_when_appCheckRejects")
    void should_return403AppCheckFailedAndSendNothing_when_appCheckRejects() throws Exception {
        doThrow(new IdentityException(IdentityException.Code.APP_CHECK_FAILED)).when(appCheckGuard).check(any());

        send(PHONE).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.code").value("APP_CHECK_FAILED"));
        verifyNoInteractions(registrationPhoneService);
    }

    @Test
    @DisplayName("should_return503_when_smsProviderUnreachable")
    void should_return503_when_smsProviderUnreachable() throws Exception {
        doThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableMessages.OTP_SEND_FAILED))
                .when(registrationPhoneService).sendCode(anyString(), any());

        send(PHONE).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("should_return400_when_sendBodyInvalid")
    void should_return400_when_sendBodyInvalid() throws Exception {
        send("").andExpect(status().isBadRequest());

        verifyNoInteractions(registrationPhoneService);
    }

    @Test
    @DisplayName("should_returnTicket_when_codeVerified")
    void should_returnTicket_when_codeVerified() throws Exception {
        when(registrationPhoneService.verifyCode(PHONE, "123456")).thenReturn("ticket-1");

        verify("123456").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registrationTicket").value("ticket-1"));
    }

    @Test
    @DisplayName("should_return400InvalidCode_when_codeWrongExpiredOrDailyCapped")
    void should_return400InvalidCode_when_codeWrongExpiredOrDailyCapped() throws Exception {
        when(registrationPhoneService.verifyCode(PHONE, "123456"))
                .thenThrow(new VerificationException(VerificationException.Code.INVALID_CODE));

        verify("123456").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_CODE"));
    }

    @Test
    @DisplayName("should_return409PhoneAlreadyRegistered_when_verifiedAccountHoldsPhone")
    void should_return409PhoneAlreadyRegistered_when_verifiedAccountHoldsPhone() throws Exception {
        when(registrationPhoneService.verifyCode(PHONE, "123456"))
                .thenThrow(new IdentityException(IdentityException.Code.PHONE_ALREADY_REGISTERED));

        verify("123456").andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("should_return400_when_codeNotSixDigits")
    void should_return400_when_codeNotSixDigits() throws Exception {
        verify("12ab").andExpect(status().isBadRequest());

        verifyNoInteractions(registrationPhoneService);
    }
}
