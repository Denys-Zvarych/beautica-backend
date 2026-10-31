package com.beautica.notification.telegram.link;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.common.exception.GlobalExceptionHandler;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.TelegramLinkRateLimitedException;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.notification.telegram.link.dto.GuestTelegramLinkResponse;
import com.beautica.notification.telegram.link.dto.GuestTelegramStatusResponse;
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

import java.time.Instant;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 407 — {@code @WebMvcTest} slice for {@link GuestTelegramLinkController}: anonymous POSTs, body validation,
 * response shapes, status mapping (generic 404, per-token 429 envelope). The per-IP 429s are the
 * {@code AuthRateLimitFilter}'s, pinned by {@code GuestTelegramLinkControllerIT} against the real filter chain.
 */
@WebMvcTest(GuestTelegramLinkController.class)
@Import({WebMvcTestSupport.class, GlobalExceptionHandler.class})
@DisplayName("GuestTelegramLinkController — @WebMvcTest slice (phase 407)")
class GuestTelegramLinkControllerTest {

    @TestConfiguration
    static class SecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
                throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.POST, "/api/v1/book/telegram/link-token",
                                    "/api/v1/book/telegram/status", "/api/v1/book/telegram/unlink").permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, exc) ->
                            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    private static final String BASE = "/api/v1/book/telegram";
    private static final String TOKEN = "00000000-0000-4000-8000-000000000001";
    private static final String BODY = "{\"cancelToken\":\"" + TOKEN + "\"}";
    private static final String URL = "https://t.me/beautica_test_bot?start=" + "A".repeat(43);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GuestTelegramLinkService service;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("should return 200 with URL and expiry when link token issued")
    void should_return200WithUrlAndExpiry_when_linkTokenIssued() throws Exception {
        when(service.issueLinkToken(TOKEN)).thenReturn(
                new GuestTelegramLinkResponse(URL, Instant.parse("2026-11-02T10:30:00Z"), false));

        mockMvc.perform(post(BASE + "/link-token").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-02T10:30:00Z"))
                .andExpect(jsonPath("$.alreadyLinked").value(false));
    }

    @Test
    @DisplayName("should return 200 with null URL when already linked")
    void should_return200WithNullUrl_when_alreadyLinked() throws Exception {
        when(service.issueLinkToken(TOKEN)).thenReturn(GuestTelegramLinkResponse.linked());

        mockMvc.perform(post(BASE + "/link-token").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"url\":null,\"expiresAt\":null,\"alreadyLinked\":true}", true));
    }

    @Test
    @DisplayName("should return 200 with exactly three flags when status")
    void should_return200WithExactlyThreeFlags_when_status() throws Exception {
        when(service.status(TOKEN)).thenReturn(new GuestTelegramStatusResponse(true, false, true));

        mockMvc.perform(post(BASE + "/status").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"available\":true,\"linked\":false,\"offerChoice\":true}", true));
    }

    @Test
    @DisplayName("should return 204 when unlinked")
    void should_return204_when_unlinked() throws Exception {
        mockMvc.perform(post(BASE + "/unlink").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).unlink(TOKEN);
    }

    @Test
    @DisplayName("should return 404 generic body when service not found")
    void should_return404GenericBody_when_serviceNotFound() throws Exception {
        when(service.status(TOKEN)).thenThrow(new NotFoundException("Telegram link target not found"));

        mockMvc.perform(post(BASE + "/status").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    @DisplayName("should return 429 with retry after when token budget spent")
    void should_return429WithRetryAfter_when_tokenBudgetSpent() throws Exception {
        doThrow(new TelegramLinkRateLimitedException(420)).when(service).issueLinkToken(TOKEN);

        mockMvc.perform(post(BASE + "/link-token").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "420"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Too many requests — please slow down"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"cancelToken\":null}",
            "{\"cancelToken\":\"\"}",
            "{\"cancelToken\":\"not-a-uuid\"}",
            "{\"cancelToken\":\" 00000000-0000-4000-8000-000000000001\"}",
            "{\"cancelToken\":\"00000000-0000-4000-8000-0000000000017\"}"})
    @DisplayName("should return 400 without service call when cancel token malformed")
    void should_return400WithoutServiceCall_when_cancelTokenMalformed(String body) throws Exception {
        mockMvc.perform(post(BASE + "/link-token").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("should return 401 when method outside permit all")
    void should_return401_when_methodOutsidePermitAll() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(BASE + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }
}
