package com.beautica.notification.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.notification.entity.DeviceToken;
import com.beautica.notification.entity.Platform;
import com.beautica.notification.repository.DeviceTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link DeviceController}.
 *
 * <p>{@link DeviceTokenRepository} and {@link UserRepository} are {@code @MockBean}s;
 * no real database is required.
 */
@WebMvcTest(DeviceController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("DeviceController — @WebMvcTest slice")
class DeviceControllerTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceControllerTest.class);

    // ── Security configuration ────────────────────────────────────────────────

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    // ── Slice infrastructure ──────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DeviceTokenRepository deviceTokenRepository;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    // ── POST /api/v1/devices/token ────────────────────────────────────────────

    @Test
    @DisplayName("POST /devices/token — 204 when authenticated client registers a valid token")
    void should_return204_when_validTokenRegistered() throws Exception {
        var userId = UUID.randomUUID();
        when(userRepository.getReferenceById(userId)).thenReturn(mock(User.class));
        when(deviceTokenRepository.existsByUserIdAndToken(userId, "abc")).thenReturn(false);

        String body = "{\"token\":\"abc\",\"platform\":\"ANDROID\"}";

        log.debug("Act: POST /api/v1/devices/token with valid body");
        mockMvc.perform(post("/api/v1/devices/token")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        verify(deviceTokenRepository).save(any(DeviceToken.class));
    }

    @Test
    @DisplayName("POST /devices/token — 400 when platform is not ANDROID/IOS")
    void should_return400_when_platformInvalid() throws Exception {
        var userId = UUID.randomUUID();

        String body = "{\"token\":\"abc\",\"platform\":\"WINDOWS\"}";

        log.debug("Act: POST /api/v1/devices/token with platform=WINDOWS — must return 400");
        mockMvc.perform(post("/api/v1/devices/token")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(deviceTokenRepository, never()).save(any(DeviceToken.class));
    }

    @Test
    @DisplayName("POST /devices/token — 400 when token is blank")
    void should_return400_when_tokenBlank() throws Exception {
        var userId = UUID.randomUUID();

        String body = "{\"token\":\"\",\"platform\":\"ANDROID\"}";

        log.debug("Act: POST /api/v1/devices/token with empty token — must return 400");
        mockMvc.perform(post("/api/v1/devices/token")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(deviceTokenRepository, never()).save(any(DeviceToken.class));
    }

    @Test
    @DisplayName("POST /devices/token — 401 when no Authorization is present")
    void should_return401_when_noAuthToken() throws Exception {
        String body = "{\"token\":\"abc\",\"platform\":\"ANDROID\"}";

        log.debug("Act: POST /api/v1/devices/token without auth — must return 401");
        mockMvc.perform(post("/api/v1/devices/token")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(deviceTokenRepository, never()).save(any(DeviceToken.class));
    }

    @Test
    @DisplayName("POST /devices/token — 204 (idempotent) and no save when token already registered for user")
    void should_return204_andSkipSave_when_tokenAlreadyRegistered() throws Exception {
        var userId = UUID.randomUUID();
        when(deviceTokenRepository.existsByUserIdAndToken(userId, "abc")).thenReturn(true);

        String body = "{\"token\":\"abc\",\"platform\":\"ANDROID\"}";

        log.debug("Act: POST /api/v1/devices/token where existsByUserIdAndToken=true — must return 204 without saving");
        mockMvc.perform(post("/api/v1/devices/token")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        verify(deviceTokenRepository, never()).save(any(DeviceToken.class));
    }

    @Test
    @DisplayName("POST /devices/token — saved entity carries authenticated principal's userId, not body-supplied data")
    void should_callRepositoryWithPrincipalUserId_when_register() throws Exception {
        var principalUserId = UUID.randomUUID();
        var principalUser = mock(User.class);
        when(userRepository.getReferenceById(principalUserId)).thenReturn(principalUser);
        when(deviceTokenRepository.existsByUserIdAndToken(eq(principalUserId), anyString())).thenReturn(false);

        String body = "{\"token\":\"abc\",\"platform\":\"ANDROID\"}";

        log.debug("Act: POST authenticated as principalUserId — captured DeviceToken must reference that user");
        mockMvc.perform(post("/api/v1/devices/token")
                        .with(authenticatedAs(principalUserId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        verify(userRepository).getReferenceById(eq(principalUserId));

        ArgumentCaptor<DeviceToken> captor = ArgumentCaptor.forClass(DeviceToken.class);
        verify(deviceTokenRepository).save(captor.capture());
        DeviceToken saved = captor.getValue();

        assertThat(saved.getUser()).isSameAs(principalUser);
        assertThat(saved.getToken()).isEqualTo("abc");
        assertThat(saved.getPlatform()).isEqualTo(Platform.ANDROID);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    @DisplayName("POST /devices/token — 204 when token is exactly 500 chars (boundary)")
    void should_return204_when_tokenIs500CharsLong() throws Exception {
        var userId = UUID.randomUUID();
        String token500 = "a".repeat(500);
        when(userRepository.getReferenceById(userId)).thenReturn(mock(User.class));
        when(deviceTokenRepository.existsByUserIdAndToken(userId, token500)).thenReturn(false);

        String body = objectMapper.writeValueAsString(
                java.util.Map.of("token", token500, "platform", "ANDROID"));

        log.debug("Act: POST /api/v1/devices/token with token of exactly 500 chars — must return 204");
        mockMvc.perform(post("/api/v1/devices/token")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        verify(deviceTokenRepository).save(any(DeviceToken.class));
    }

    @Test
    @DisplayName("POST /devices/token — 400 when token is 501 chars (exceeds @Size(max=500))")
    void should_return400_when_tokenIs501CharsLong() throws Exception {
        var userId = UUID.randomUUID();
        String token501 = "a".repeat(501);

        String body = objectMapper.writeValueAsString(
                java.util.Map.of("token", token501, "platform", "ANDROID"));

        log.debug("Act: POST /api/v1/devices/token with token of 501 chars — must return 400");
        mockMvc.perform(post("/api/v1/devices/token")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(deviceTokenRepository, never()).save(any(DeviceToken.class));
    }

    // ── DELETE /api/v1/devices/token ──────────────────────────────────────────

    @Test
    @DisplayName("DELETE /devices/token — 204 when authenticated client deregisters a token")
    void should_return204_when_tokenDeregistered() throws Exception {
        var userId = UUID.randomUUID();

        String body = "{\"token\":\"abc\"}";

        log.debug("Act: DELETE /api/v1/devices/token with token=abc");
        mockMvc.perform(delete("/api/v1/devices/token")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        verify(deviceTokenRepository).deleteByUserIdAndToken(eq(userId), eq("abc"));
    }

    @Test
    @DisplayName("DELETE /devices/token — 401 when no Authorization is present and repository never called")
    void should_return401_when_deleteWithoutAuth() throws Exception {
        String body = "{\"token\":\"abc\"}";

        log.debug("Act: DELETE /api/v1/devices/token without auth — must return 401 and never invoke repository");
        mockMvc.perform(delete("/api/v1/devices/token")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(deviceTokenRepository, never()).deleteByUserIdAndToken(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("DELETE /devices/token — 400 when token field is blank and repository never called")
    void should_return400_when_unregisterTokenBlank() throws Exception {
        var userId = UUID.randomUUID();

        String body = "{\"token\":\"\"}";

        log.debug("Act: DELETE /api/v1/devices/token with empty token — must return 400 and never invoke repository");
        mockMvc.perform(delete("/api/v1/devices/token")
                        .with(authenticatedAs(userId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(deviceTokenRepository, never()).deleteByUserIdAndToken(any(UUID.class), anyString());
    }
}
