package com.beautica.user;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.auth.appcheck.AppCheckGuard;
import com.beautica.common.exception.GlobalExceptionHandler;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.common.exception.VerificationException;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.user.email.NotificationEmailService;
import com.beautica.user.phone.UserPhoneService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link UserContactController} (AUTH-44, AUTH-D19, phone-first hardening): status and
 * {@code data.code} per error of the signed-in phone / email contact endpoints.
 */
@WebMvcTest(UserContactController.class)
@Import({WebMvcTestSupport.class, GlobalExceptionHandler.class})
@DisplayName("UserContactController — @WebMvcTest slice")
class UserContactControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
                throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, exc) ->
                            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    private static final UUID USER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String PHONE = "+380671234567";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserPhoneService userPhoneService;

    @MockBean
    private NotificationEmailService notificationEmailService;

    @MockBean
    private AppCheckGuard appCheckGuard;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static RequestPostProcessor signedIn() {
        var token = new UsernamePasswordAuthenticationToken("", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + Role.CLIENT.name())));
        token.setDetails(USER_ID);
        return authentication(token);
    }

    private ResultActions postJson(String url, String json, boolean authenticated) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(json);
        return mockMvc.perform(authenticated ? request.with(signedIn()) : request);
    }

    private ResultActions sendPhone() throws Exception {
        return postJson("/api/v1/users/me/phone/otp/send", "{\"phone\":\"" + PHONE + "\"}", true);
    }

    private ResultActions verifyPhone() throws Exception {
        return postJson("/api/v1/users/me/phone/otp/verify",
                "{\"phone\":\"" + PHONE + "\",\"code\":\"123456\",\"currentPassword\":\"pw\"}", true);
    }

    @Test
    @DisplayName("should_return200_when_phoneCodeSent")
    void should_return200_when_phoneCodeSent() throws Exception {
        sendPhone().andExpect(status().isOk());

        verify(userPhoneService).sendCode(eq(USER_ID), eq(PHONE), any());
    }

    @Test
    @DisplayName("should_return401_when_notSignedIn")
    void should_return401_when_notSignedIn() throws Exception {
        postJson("/api/v1/users/me/phone/otp/send", "{\"phone\":\"" + PHONE + "\"}", false)
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userPhoneService);
    }

    @Test
    @DisplayName("should_return429WithRetryAfter_when_phoneCodeCooldownRunning")
    void should_return429WithRetryAfter_when_phoneCodeCooldownRunning() throws Exception {
        doThrow(new ResendThrottledException(37)).when(userPhoneService).sendCode(any(), anyString(), any());

        sendPhone().andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "37"));
    }

    @Test
    @DisplayName("should_return403AppCheckFailed_when_appCheckRejectsPhoneSend")
    void should_return403AppCheckFailed_when_appCheckRejectsPhoneSend() throws Exception {
        doThrow(new IdentityException(IdentityException.Code.APP_CHECK_FAILED)).when(appCheckGuard).check(any());

        sendPhone().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.code").value("APP_CHECK_FAILED"));
        verifyNoInteractions(userPhoneService);
    }

    @Test
    @DisplayName("should_return400PhoneNotUaMobile_when_numberNotUaMobile")
    void should_return400PhoneNotUaMobile_when_numberNotUaMobile() throws Exception {
        doThrow(new IdentityException(IdentityException.Code.PHONE_NOT_UA_MOBILE))
                .when(userPhoneService).sendCode(any(), anyString(), any());

        sendPhone().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("PHONE_NOT_UA_MOBILE"));
    }

    @Test
    @DisplayName("should_return400CurrentPasswordInvalid_when_wrongPasswordOnChange")
    void should_return400CurrentPasswordInvalid_when_wrongPasswordOnChange() throws Exception {
        doThrow(new VerificationException(VerificationException.Code.CURRENT_PASSWORD_INVALID))
                .when(userPhoneService).verifyCode(any(), anyString(), anyString(), any());

        verifyPhone().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("CURRENT_PASSWORD_INVALID"));
    }

    @Test
    @DisplayName("should_return400InvalidCode_when_codeWrongOrDailyCapped")
    void should_return400InvalidCode_when_codeWrongOrDailyCapped() throws Exception {
        doThrow(new VerificationException(VerificationException.Code.INVALID_CODE))
                .when(userPhoneService).verifyCode(any(), anyString(), anyString(), any());

        verifyPhone().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_CODE"));
    }

    @Test
    @DisplayName("should_return409PhoneAlreadyRegistered_when_anotherAccountHoldsPhone")
    void should_return409PhoneAlreadyRegistered_when_anotherAccountHoldsPhone() throws Exception {
        doThrow(new IdentityException(IdentityException.Code.PHONE_ALREADY_REGISTERED))
                .when(userPhoneService).verifyCode(any(), anyString(), anyString(), any());

        verifyPhone().andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("should_return200_when_phoneVerified")
    void should_return200_when_phoneVerified() throws Exception {
        verifyPhone().andExpect(status().isOk());

        verify(userPhoneService).verifyCode(USER_ID, PHONE, "123456", "pw");
    }

    @Test
    @DisplayName("should_return429WithRetryAfter_when_emailCodeCooldownRunning")
    void should_return429WithRetryAfter_when_emailCodeCooldownRunning() throws Exception {
        doThrow(new ResendThrottledException(20)).when(notificationEmailService).requestCode(any(), anyString());

        postJson("/api/v1/users/me/email/request-code", "{\"email\":\"olena@beautica.test\"}", true)
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "20"));
    }

    @Test
    @DisplayName("should_return400CodeExpired_when_emailCodeExpired")
    void should_return400CodeExpired_when_emailCodeExpired() throws Exception {
        doThrow(new VerificationException(VerificationException.Code.CODE_EXPIRED))
                .when(notificationEmailService).verifyCode(any(), anyString(), anyString(), any());

        postJson("/api/v1/users/me/email/verify",
                "{\"email\":\"olena@beautica.test\",\"code\":\"123456\",\"currentPassword\":\"pw\"}", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("CODE_EXPIRED"));
    }
}
