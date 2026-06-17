package com.beautica.auth.phoneotp;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.GlobalExceptionHandler;
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

    private static final String VALID_PHONE = "+380671234567";

    @Test
    @DisplayName("POST /book/otp/send — 200 without auth, delegates to service")
    void should_return200_when_sendValidPhone() throws Exception {
        mockMvc.perform(post("/api/v1/book/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + VALID_PHONE + "\"}"))
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
