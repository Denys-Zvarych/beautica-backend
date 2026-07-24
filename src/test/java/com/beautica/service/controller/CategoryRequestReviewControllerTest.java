package com.beautica.service.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.CategoryRequestService.DecisionOutcome;
import com.beautica.service.service.CategoryRequestService.ReviewView;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CategoryRequestReviewController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("CategoryRequestReviewController — @WebMvcTest slice")
class CategoryRequestReviewControllerTest {

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
    @MockBean private CategoryRequestService categoryRequestService;
    @MockBean private JwtTokenProvider jwtTokenProvider;

    // ── GET /review (no state change) ──────────────────────────────────────────

    @Test
    @DisplayName("should_renderReviewPage_when_tokenValid")
    void should_renderReviewPage_when_tokenValid() throws Exception {
        when(categoryRequestService.loadForReview("good-token"))
                .thenReturn(new ReviewView(true, "NAIL_ART", "Нейл-арт"));

        mockMvc.perform(get("/api/v1/service-categories/requests/review/{token}", "good-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("NAIL_ART")))
                // Token rides in the URL path → no-store stops cache retention,
                // no-referrer stops the token leaking via a later cross-origin Referer.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-cache")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("must-revalidate")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        // Critical: GET must NOT mutate state.
        verify(categoryRequestService, never()).approve(anyString());
        verify(categoryRequestService, never()).reject(anyString());
    }

    @Test
    @DisplayName("should_renderNeutralPage_when_reviewTokenInvalid")
    void should_renderNeutralPage_when_reviewTokenInvalid() throws Exception {
        when(categoryRequestService.loadForReview("bad-token"))
                .thenReturn(new ReviewView(false, null, null));

        mockMvc.perform(get("/api/v1/service-categories/requests/review/{token}", "bad-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("NAIL_ART"))));
    }

    @Test
    @DisplayName("should_return404_when_reviewTokenPathSegmentMissing")
    void should_return404_when_reviewTokenPathSegmentMissing() throws Exception {
        // Token now rides in the path; with no trailing segment the route does not
        // match at all, so the request never reaches the handler (404, not 400).
        mockMvc.perform(get("/api/v1/service-categories/requests/review"))
                .andExpect(status().isNotFound());
    }

    // ── POST /approve and /reject (state change) ───────────────────────────────

    @Test
    @DisplayName("should_renderApprovedPage_when_approvePosted")
    void should_renderApprovedPage_when_approvePosted() throws Exception {
        when(categoryRequestService.approve("good-token")).thenReturn(DecisionOutcome.APPROVED);

        mockMvc.perform(post("/api/v1/service-categories/requests/approve")
                        .with(csrf()).param("token", "good-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        verify(categoryRequestService).approve(eq("good-token"));
    }

    @Test
    @DisplayName("should_renderRejectedPage_when_rejectPosted")
    void should_renderRejectedPage_when_rejectPosted() throws Exception {
        when(categoryRequestService.reject("good-token")).thenReturn(DecisionOutcome.REJECTED);

        mockMvc.perform(post("/api/v1/service-categories/requests/reject")
                        .with(csrf()).param("token", "good-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        verify(categoryRequestService).reject(eq("good-token"));
    }

    @Test
    @DisplayName("should_renderNeutralResult_when_secondApproveIsAlreadyDecided")
    void should_renderNeutralResult_when_secondApproveIsAlreadyDecided() throws Exception {
        when(categoryRequestService.approve("stale-token")).thenReturn(DecisionOutcome.ALREADY_DECIDED);

        mockMvc.perform(post("/api/v1/service-categories/requests/approve")
                        .with(csrf()).param("token", "stale-token"))
                .andExpect(status().isOk());
    }
}
