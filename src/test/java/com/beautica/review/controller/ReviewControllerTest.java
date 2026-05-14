package com.beautica.review.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.service.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.data.domain.Page;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReviewController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("ReviewController — @WebMvcTest slice")
class ReviewControllerTest {

    private static final Logger log = LoggerFactory.getLogger(ReviewControllerTest.class);
    private static final String REVIEWS_URL = "/api/v1/reviews";
    private static final String MASTERS_REVIEWS_URL = "/api/v1/masters/{masterId}/reviews";

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
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET,
                                    "/api/v1/masters/*/reviews",
                                    "/api/v1/reviews/**").permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private ReviewService reviewService;
    @MockBean(name = "authz") private AuthorizationService authorizationService;
    @MockBean private JwtTokenProvider jwtTokenProvider;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RequestPostProcessor authenticatedAs(UUID userId, String email, Role role) {
        var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
        var token = new UsernamePasswordAuthenticationToken(email, null, List.of(authority));
        token.setDetails(userId);
        return authentication(token);
    }

    private ReviewResponse stubReviewResponse() {
        return new ReviewResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Іван Франко",
                5,
                "Great service",
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    // ── POST /reviews ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /reviews — 201 when CLIENT submits a valid review")
    void should_return201_when_clientSubmitsValidReview() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, "Great service"));
        when(reviewService.createReview(any(), any())).thenReturn(stubReviewResponse());

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /reviews — 403 when SALON_OWNER attempts to submit a review")
    void should_return403_when_nonClientSubmitsReview() throws Exception {
        var ownerId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, null));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(ownerId, "owner@beautica.test", Role.SALON_OWNER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /reviews — 403 when SALON_MASTER attempts to submit a review")
    void should_return403_when_salonMasterSubmitsReview() throws Exception {
        var masterId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, null));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.SALON_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /reviews — 403 when INDEPENDENT_MASTER attempts to submit a review")
    void should_return403_when_independentMasterSubmitsReview() throws Exception {
        var masterId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, null));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(masterId, "master@beautica.test", Role.INDEPENDENT_MASTER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /reviews — 401 when no Authorization header is present")
    void should_return401_when_noTokenOnCreateReview() throws Exception {
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, null));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /reviews — 400 when rating exceeds maximum (6 > 5)")
    void should_return400_when_ratingOutOfRange() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 6, null));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /reviews — 400 when rating is zero (below minimum of 1)")
    void should_return400_when_ratingZero() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 0, null));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /reviews — 400 when bookingId is absent from the request body")
    void should_return400_when_bookingIdMissing() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(null, 5, null));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /reviews — 409 when a review already exists for this booking")
    void should_return409_when_reviewAlreadyExists() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, null));
        when(reviewService.createReview(any(), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT,
                        "Review already exists for this booking"));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /reviews — 400 when booking is not in COMPLETED status")
    void should_return400_when_bookingNotCompleted() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, null));
        when(reviewService.createReview(any(), any()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST,
                        "Review can only be submitted for completed bookings"));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── GET /masters/{masterId}/reviews ───────────────────────────────────────

    @Test
    @DisplayName("GET /masters/{masterId}/reviews — 200 for unauthenticated caller (public endpoint)")
    void should_return200_when_publicGetMasterReviews() throws Exception {
        var masterId = UUID.randomUUID();
        when(reviewService.getReviewsForMaster(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get(MASTERS_REVIEWS_URL, masterId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /masters/{masterId}/reviews — 200 with empty page when masterId does not exist")
    void should_return200_when_getMasterReviewsWithNonExistentMasterId() throws Exception {
        var nonExistentMasterId = UUID.randomUUID();
        when(reviewService.getReviewsForMaster(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get(MASTERS_REVIEWS_URL, nonExistentMasterId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    // ── GET /reviews/{reviewId} ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /reviews/{reviewId} — 200 and rating returned for unauthenticated caller")
    void should_return200_when_publicGetReviewById() throws Exception {
        var reviewId = UUID.randomUUID();
        when(reviewService.getReview(any())).thenReturn(stubReviewResponse());

        mockMvc.perform(get(REVIEWS_URL + "/{reviewId}", reviewId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(5));
    }

    @Test
    @DisplayName("GET /reviews/{reviewId} — 404 when review does not exist")
    void should_return404_when_reviewNotFound() throws Exception {
        var reviewId = UUID.randomUUID();
        when(reviewService.getReview(any()))
                .thenThrow(new NotFoundException("Review not found"));

        mockMvc.perform(get(REVIEWS_URL + "/{reviewId}", reviewId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
