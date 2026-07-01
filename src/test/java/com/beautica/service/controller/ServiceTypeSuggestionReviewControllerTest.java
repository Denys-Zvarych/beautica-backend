package com.beautica.service.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.service.service.ServiceTypeSuggestionService;
import com.beautica.service.service.ServiceTypeSuggestionService.DecisionOutcome;
import com.beautica.service.service.ServiceTypeSuggestionService.ReviewView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link ServiceTypeSuggestionReviewController}.
 * Mirrors {@code CategoryRequestReviewControllerTest}.
 *
 * <p>Pins the security contract of these {@code permitAll} token pages:
 * <ul>
 *   <li>GET /review renders the page and performs NO mutation (only {@code loadForReview}).</li>
 *   <li>POST approve / reject invoke the mutating service methods.</li>
 *   <li>Bad / expired / already-decided tokens render a neutral page (no existence leak).</li>
 *   <li>An oversized token (&gt; 128 chars) is rejected by validation (400).</li>
 * </ul>
 */
@WebMvcTest(ServiceTypeSuggestionReviewController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("ServiceTypeSuggestionReviewController — @WebMvcTest slice")
class ServiceTypeSuggestionReviewControllerTest {

    private static final String REVIEW = "/api/v1/service-types/suggestions/review";
    private static final String APPROVE = "/api/v1/service-types/suggestions/approve";
    private static final String REJECT = "/api/v1/service-types/suggestions/reject";

    @TestConfiguration
    static class SecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            // All three token pages are permitAll — the token is the credential.
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
    }

    @Autowired private MockMvc mockMvc;
    @MockBean private ServiceTypeSuggestionService suggestionService;
    @MockBean private JwtTokenProvider jwtTokenProvider;

    // ── GET /review (no state change) ──────────────────────────────────────────

    @Test
    @DisplayName("should_renderReviewPageAndNotMutate_when_tokenValid")
    void should_renderReviewPageAndNotMutate_when_tokenValid() throws Exception {
        when(suggestionService.loadForReview("good-token"))
                .thenReturn(new ReviewView(true, "HAIR", "Балаяж", "Освітлення кінчиків"));

        mockMvc.perform(get(REVIEW).param("token", "good-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Балаяж")))
                // Token rides in the URL query → no-store stops cache retention,
                // no-referrer stops the token leaking via a later cross-origin Referer.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-cache")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("must-revalidate")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        // Critical: GET must NOT mutate state — email-scanner pre-fetch must not approve.
        verify(suggestionService, never()).approve(anyString());
        verify(suggestionService, never()).reject(anyString());
    }

    @Test
    @DisplayName("should_renderNeutralPage_when_reviewTokenInvalid")
    void should_renderNeutralPage_when_reviewTokenInvalid() throws Exception {
        when(suggestionService.loadForReview("bad-token"))
                .thenReturn(new ReviewView(false, null, null, null));

        mockMvc.perform(get(REVIEW).param("token", "bad-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Балаяж"))));

        verify(suggestionService, never()).approve(anyString());
        verify(suggestionService, never()).reject(anyString());
    }

    @Test
    @DisplayName("should_return400_when_reviewTokenMissing")
    void should_return400_when_reviewTokenMissing() throws Exception {
        mockMvc.perform(get(REVIEW))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(suggestionService);
    }

    @Test
    @DisplayName("should_return400_when_reviewTokenExceedsMaxLength")
    void should_return400_when_reviewTokenExceedsMaxLength() throws Exception {
        String oversized = "x".repeat(129);

        mockMvc.perform(get(REVIEW).param("token", oversized))
                .andExpect(status().isBadRequest());

        // Validation rejects before the service is consulted — no existence probe.
        verify(suggestionService, never()).loadForReview(anyString());
    }

    @Test
    @DisplayName("should_return400AndNotProbe_when_reviewTokenHasIllegalChar")
    void should_return400AndNotProbe_when_reviewTokenHasIllegalChar() throws Exception {
        // '!' is outside the base64url charset [A-Za-z0-9-_] → @Pattern rejects it.
        mockMvc.perform(get(REVIEW).param("token", "bad!token"))
                .andExpect(status().isBadRequest());

        // Charset guard fires before routing/service — no existence probe.
        verify(suggestionService, never()).loadForReview(anyString());
    }

    // ── POST /approve and /reject (state change) ───────────────────────────────

    @Test
    @DisplayName("should_invokeApproveAndRenderApprovedPage_when_approvePosted")
    void should_invokeApproveAndRenderApprovedPage_when_approvePosted() throws Exception {
        when(suggestionService.approve("good-token")).thenReturn(DecisionOutcome.APPROVED);

        mockMvc.perform(post(APPROVE).with(csrf()).param("token", "good-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        verify(suggestionService).approve(eq("good-token"));
        verify(suggestionService, never()).reject(anyString());
    }

    @Test
    @DisplayName("should_invokeRejectAndRenderRejectedPage_when_rejectPosted")
    void should_invokeRejectAndRenderRejectedPage_when_rejectPosted() throws Exception {
        when(suggestionService.reject("good-token")).thenReturn(DecisionOutcome.REJECTED);

        mockMvc.perform(post(REJECT).with(csrf()).param("token", "good-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        verify(suggestionService).reject(eq("good-token"));
        verify(suggestionService, never()).approve(anyString());
    }

    @Test
    @DisplayName("should_renderNeutralResult_when_secondApproveIsAlreadyDecided")
    void should_renderNeutralResult_when_secondApproveIsAlreadyDecided() throws Exception {
        when(suggestionService.approve("stale-token")).thenReturn(DecisionOutcome.ALREADY_DECIDED);

        mockMvc.perform(post(APPROVE).with(csrf()).param("token", "stale-token"))
                .andExpect(status().isOk());

        verify(suggestionService).approve(eq("stale-token"));
    }

    @Test
    @DisplayName("should_renderNeutralResult_when_approveTokenInvalidOrExpired")
    void should_renderNeutralResult_when_approveTokenInvalidOrExpired() throws Exception {
        when(suggestionService.approve("expired-token")).thenReturn(DecisionOutcome.INVALID_OR_EXPIRED);

        mockMvc.perform(post(APPROVE).with(csrf()).param("token", "expired-token"))
                .andExpect(status().isOk())
                // Neutral page — must not leak any record-specific detail.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Балаяж"))));
    }

    @Test
    @DisplayName("should_return400AndNotMutate_when_approveTokenExceedsMaxLength")
    void should_return400AndNotMutate_when_approveTokenExceedsMaxLength() throws Exception {
        String oversized = "x".repeat(129);

        mockMvc.perform(post(APPROVE).with(csrf()).param("token", oversized))
                .andExpect(status().isBadRequest());

        verify(suggestionService, never()).approve(anyString());
    }

    @Test
    @DisplayName("should_return400AndNotMutate_when_approveTokenHasIllegalChar")
    void should_return400AndNotMutate_when_approveTokenHasIllegalChar() throws Exception {
        // A space is outside the base64url charset [A-Za-z0-9-_] → @Pattern rejects it
        // before the mutating service is ever reached.
        mockMvc.perform(post(APPROVE).with(csrf()).param("token", "bad token"))
                .andExpect(status().isBadRequest());

        verify(suggestionService, never()).approve(anyString());
    }
}
