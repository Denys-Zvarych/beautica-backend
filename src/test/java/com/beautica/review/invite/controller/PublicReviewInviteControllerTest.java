package com.beautica.review.invite.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.filter.VisitorIpResolver;
import com.beautica.auth.turnstile.TurnstileVerifier;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.GlobalExceptionHandler;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.TurnstileRejectedException;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.review.invite.dto.ReviewInviteItemResponse;
import com.beautica.review.invite.dto.ReviewInviteResponse;
import com.beautica.review.invite.service.GuestReviewService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 401 — {@code @WebMvcTest} slice for {@link PublicReviewInviteController}: anonymous access, Bean Validation
 * (same rules as the app review), Turnstile before the service, status mapping. The 429 is the
 * {@code AuthRateLimitFilter}'s, pinned by {@code ReviewInviteRateLimitTest} against the real filter.
 */
@WebMvcTest(PublicReviewInviteController.class)
@Import({WebMvcTestSupport.class, GlobalExceptionHandler.class})
@DisplayName("PublicReviewInviteController — @WebMvcTest slice")
class PublicReviewInviteControllerTest {

    @TestConfiguration
    static class SecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
                throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/public/review-invites/*").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/public/review-invites/*/reviews").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/public/review-invites/*/opt-out").permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, exc) ->
                            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUv";
    private static final String BASE = "/api/v1/public/review-invites/" + TOKEN;
    private static final UUID BOOKING_ID = UUID.fromString("5f0c3c1e-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GuestReviewService guestReviewService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private TurnstileVerifier turnstileVerifier;

    @MockBean
    private VisitorIpResolver visitorIpResolver;

    private static String submitBody(String items) {
        return "{\"turnstileToken\":\"cf-token\",\"items\":" + items + "}";
    }

    private static String item(Object rating, String commentJson) {
        return "{\"bookingId\":\"" + BOOKING_ID + "\",\"rating\":" + rating
                + (commentJson == null ? "" : ",\"comment\":" + commentJson) + "}";
    }

    @Test
    @DisplayName("should_return200WithForm_when_inviteOpen")
    void should_return200WithForm_when_inviteOpen() throws Exception {
        when(guestReviewService.getInvite(TOKEN)).thenReturn(ReviewInviteResponse.open("Оксана Коваленко", "MASTER",
                LocalDate.of(2026, 10, 24),
                List.of(new ReviewInviteItemResponse(BOOKING_ID, "Манікюр", "Оксана Коваленко")), "Олена К.",
                "https://cdn.beautica.app/u/a.jpg", new BigDecimal("4.50"), 12, "Майстер манікюру"));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.providerName").value("Оксана Коваленко"))
                .andExpect(jsonPath("$.providerType").value("MASTER"))
                .andExpect(jsonPath("$.visitDate").value("2026-10-24"))
                .andExpect(jsonPath("$.items[0].bookingId").value(BOOKING_ID.toString()))
                .andExpect(jsonPath("$.items[0].serviceName").value("Манікюр"))
                .andExpect(jsonPath("$.items[0].masterName").value("Оксана Коваленко"))
                .andExpect(jsonPath("$.displayName").value("Олена К."))
                .andExpect(jsonPath("$.avatarUrl").value("https://cdn.beautica.app/u/a.jpg"))
                .andExpect(jsonPath("$.avgRating").value(4.5))
                .andExpect(jsonPath("$.reviewCount").value(12))
                .andExpect(jsonPath("$.professionalTitle").value("Майстер манікюру"));
    }

    @Test
    @DisplayName("should_returnZeroCountAndOmitNullCardFields_when_providerHasNoReviewsOrAvatar")
    void should_returnZeroCountAndOmitNullCardFields_when_providerHasNoReviewsOrAvatar() throws Exception {
        when(guestReviewService.getInvite(TOKEN)).thenReturn(ReviewInviteResponse.open("Лаванда", "SALON",
                LocalDate.of(2026, 10, 24),
                List.of(new ReviewInviteItemResponse(BOOKING_ID, "Манікюр", "Оксана Коваленко")), "Олена К.",
                null, null, 0, null));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(0))
                .andExpect(jsonPath("$.avgRating").doesNotExist())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.professionalTitle").doesNotExist());
    }

    @Test
    @DisplayName("should_returnOnlyState_when_inviteAlreadyReviewed")
    void should_returnOnlyState_when_inviteAlreadyReviewed() throws Exception {
        when(guestReviewService.getInvite(TOKEN)).thenReturn(ReviewInviteResponse.alreadyReviewed());

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ALREADY_REVIEWED"))
                .andExpect(jsonPath("$.providerName").doesNotExist())
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.reviewCount").doesNotExist());
    }

    @Test
    @DisplayName("should_return404Generic_when_inviteInvalid")
    void should_return404Generic_when_inviteInvalid() throws Exception {
        when(guestReviewService.getInvite(anyString())).thenThrow(new NotFoundException("Review invite not found"));

        mockMvc.perform(get(BASE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    @DisplayName("should_return201WithCount_when_submitValid")
    void should_return201WithCount_when_submitValid() throws Exception {
        when(visitorIpResolver.resolve(any())).thenReturn("203.0.113.7");
        when(guestReviewService.submit(eq(TOKEN), anyList())).thenReturn(1);

        mockMvc.perform(post(BASE + "/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[" + item(5, "\"Чудово\"") + "]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(1));

        var order = inOrder(turnstileVerifier, guestReviewService);
        order.verify(turnstileVerifier).verifyReviewInvite("cf-token", "203.0.113.7");
        order.verify(guestReviewService).submit(eq(TOKEN), anyList());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "[]",
            "null",
            "[{\"rating\":5}]",
            "[{\"bookingId\":\"5f0c3c1e-0000-4000-8000-000000000001\"}]",
            "[{\"bookingId\":\"5f0c3c1e-0000-4000-8000-000000000001\",\"rating\":0}]",
            "[{\"bookingId\":\"5f0c3c1e-0000-4000-8000-000000000001\",\"rating\":6}]",
            "[{\"bookingId\":\"5f0c3c1e-0000-4000-8000-000000000001\",\"rating\":5,\"comment\":\"\"}]",
            "[{\"bookingId\":\"5f0c3c1e-0000-4000-8000-000000000001\",\"rating\":5,\"comment\":\" leading\"}]",
            "[{\"bookingId\":\"5f0c3c1e-0000-4000-8000-000000000001\",\"rating\":5,\"comment\":\"a\\u0000b\"}]",
            "[null]"
    })
    @DisplayName("should_return400WithoutTurnstileOrService_when_submitInvalid")
    void should_return400WithoutTurnstileOrService_when_submitInvalid(String items) throws Exception {
        mockMvc.perform(post(BASE + "/reviews").contentType(MediaType.APPLICATION_JSON).content(submitBody(items)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(turnstileVerifier, guestReviewService);
    }

    @Test
    @DisplayName("should_return400_when_commentLongerThan2000")
    void should_return400_when_commentLongerThan2000() throws Exception {
        String tooLong = "\"" + "а".repeat(2001) + "\"";

        mockMvc.perform(post(BASE + "/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[" + item(4, tooLong) + "]")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(guestReviewService);
    }

    @Test
    @DisplayName("should_return400WithoutService_when_turnstileRejects")
    void should_return400WithoutService_when_turnstileRejects() throws Exception {
        doThrow(new TurnstileRejectedException()).when(turnstileVerifier).verifyReviewInvite(any(), any());

        mockMvc.perform(post(BASE + "/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[" + item(5, null) + "]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Verification failed"));

        verifyNoInteractions(guestReviewService);
    }

    @Test
    @DisplayName("should_return409_when_bookingNotInInvite")
    void should_return409_when_bookingNotInInvite() throws Exception {
        when(guestReviewService.submit(eq(TOKEN), anyList()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "Booking is not part of this review invite"));

        mockMvc.perform(post(BASE + "/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[" + item(5, null) + "]")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("should_return404_when_submitTokenSpent")
    void should_return404_when_submitTokenSpent() throws Exception {
        when(guestReviewService.submit(eq(TOKEN), anyList())).thenThrow(new NotFoundException("x"));

        mockMvc.perform(post(BASE + "/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[" + item(5, null) + "]")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("should_return204_when_optOutValid")
    void should_return204_when_optOutValid() throws Exception {
        when(visitorIpResolver.resolve(any())).thenReturn("203.0.113.7");

        mockMvc.perform(post(BASE + "/opt-out").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"turnstileToken\":\"cf-token\"}"))
                .andExpect(status().isNoContent());

        var order = inOrder(turnstileVerifier, guestReviewService);
        order.verify(turnstileVerifier).verifyReviewInvite("cf-token", "203.0.113.7");
        order.verify(guestReviewService).optOut(TOKEN);
    }

    @Test
    @DisplayName("should_verifyNullTurnstileToken_when_optOutBodyEmpty")
    void should_verifyNullTurnstileToken_when_optOutBodyEmpty() throws Exception {
        mockMvc.perform(post(BASE + "/opt-out").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());

        verify(turnstileVerifier).verifyReviewInvite(eq(null), any());
    }

    @Test
    @DisplayName("should_return400WithoutService_when_optOutTurnstileRejects")
    void should_return400WithoutService_when_optOutTurnstileRejects() throws Exception {
        doThrow(new TurnstileRejectedException()).when(turnstileVerifier).verifyReviewInvite(any(), any());

        mockMvc.perform(post(BASE + "/opt-out").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(guestReviewService);
    }
}
