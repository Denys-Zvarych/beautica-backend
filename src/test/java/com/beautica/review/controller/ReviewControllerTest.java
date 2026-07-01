package com.beautica.review.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.common.PageResponse;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.MyReviewResponse;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.service.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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

import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

    @ParameterizedTest(name = "{0} → 403")
    @EnumSource(value = Role.class, names = "CLIENT", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("POST /reviews — 403 when a non-CLIENT role attempts to submit a review")
    void should_return403_when_nonClientRoleSubmitsReview(Role role) throws Exception {
        // @PreAuthorize("hasRole('CLIENT')") gates POST /reviews — every other role is
        // forbidden. EXCLUDE CLIENT so the matrix covers SALON_OWNER, SALON_ADMIN,
        // SALON_MASTER, INDEPENDENT_MASTER. The body is otherwise valid, so a 403 proves
        // the authorization gate fires BEFORE service invocation.
        var userId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, null));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(userId, role.name().toLowerCase() + "@beautica.test", role))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        // Authorization must short-circuit — the service is never reached for a denied role.
        verifyNoInteractions(reviewService);
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

    // ── rating boundary matrix (@Min(1) / @Max(5)) ────────────────────────────

    @ParameterizedTest(name = "rating={0} → 201")
    @ValueSource(ints = {1, 5})
    @DisplayName("POST /reviews — 201 when rating is at an in-range boundary (1 or 5)")
    void should_return201_when_ratingAtInRangeBoundary(int rating) throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), rating, null));
        when(reviewService.createReview(any(), any())).thenReturn(stubReviewResponse());

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @ParameterizedTest(name = "rating={0} → 400")
    @ValueSource(ints = {0, 6})
    @DisplayName("POST /reviews — 400 when rating is just outside [1,5] (0 below min, 6 above max)")
    void should_return400_when_ratingOutOfRange(int rating) throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), rating, null));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        // Bean Validation rejects the body before the service is invoked.
        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("POST /reviews — 400 when rating is null (@NotNull)")
    void should_return400_when_ratingNull() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), null, null));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reviewService);
    }

    // ── comment boundary matrix (@Size(min=1, max=2000)) ──────────────────────

    @Test
    @DisplayName("POST /reviews — 201 when comment is exactly 2000 chars (max boundary accepted)")
    void should_return201_when_commentAtMaxLength() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, "x".repeat(2000)));
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
    @DisplayName("POST /reviews — 201 when comment is null (optional — no comment is valid)")
    void should_return201_when_commentNull() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, null));
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
    @DisplayName("POST /reviews — 400 when comment is 2001 chars (one over max)")
    void should_return400_when_commentOverMaxLength() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, "x".repeat(2001)));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("POST /reviews — 400 when comment is empty string (@Size min=1 / blank rejected)")
    void should_return400_when_commentBlank() throws Exception {
        var clientId = UUID.randomUUID();
        var body = objectMapper.writeValueAsString(
                new CreateReviewRequest(UUID.randomUUID(), 5, ""));

        mockMvc.perform(post(REVIEWS_URL)
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reviewService);
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

    @Test
    @DisplayName("GET /masters/{masterId}/reviews — 400 when page number exceeds 10000")
    void should_return400_when_pageNumberExceedsMaximum() throws Exception {
        var masterId = UUID.randomUUID();
        when(reviewService.getReviewsForMaster(any(), any()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST,
                        "Page number must not exceed 10000"));

        mockMvc.perform(get(MASTERS_REVIEWS_URL, masterId)
                        .param("page", "2147483647")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── GET /reviews/me (Phase 19.4) ──────────────────────────────────────────

    private MyReviewResponse stubMyReviewResponse() {
        return new MyReviewResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Anna",
                "Smith",
                "Manicure",
                5,
                "Great service",
                Instant.parse("2026-06-01T10:00:00Z"),
                UUID.randomUUID());
    }

    private PageResponse<MyReviewResponse> stubMyReviewsPage(MyReviewResponse row) {
        return PageResponse.of(List.of(row), 0, 20, 1, 1);
    }

    @Test
    @DisplayName("GET /reviews/me — 200 with the principal's reviews when caller is a CLIENT")
    void should_return200_when_clientGetsOwnReviews() throws Exception {
        var clientId = UUID.randomUUID();
        var row = stubMyReviewResponse();
        when(reviewService.getMyReviews(eq(clientId), any())).thenReturn(stubMyReviewsPage(row));

        mockMvc.perform(get(REVIEWS_URL + "/me")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.data[0].masterFirstName").value("Anna"))
                .andExpect(jsonPath("$.data.data[0].serviceName").value("Manicure"))
                .andExpect(jsonPath("$.data.data[0].rating").value(5));
    }

    @Test
    @DisplayName("GET /reviews/me — passes the principal id (not a parameter) to the service")
    void should_passPrincipalIdToService_when_gettingOwnReviews() throws Exception {
        var principalId = UUID.randomUUID();
        // A query param that an attacker might use to impersonate another client must be ignored:
        // the controller derives clientUserId solely from the authenticated principal.
        var attackerSuppliedId = UUID.randomUUID();
        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        when(reviewService.getMyReviews(any(), any()))
                .thenReturn(stubMyReviewsPage(stubMyReviewResponse()));

        mockMvc.perform(get(REVIEWS_URL + "/me")
                        .param("clientId", attackerSuppliedId.toString())
                        .with(authenticatedAs(principalId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(reviewService).getMyReviews(idCaptor.capture(), any());
        assertThat(idCaptor.getValue())
                .as("service must receive the principal id, never the attacker-supplied clientId query param")
                .isEqualTo(principalId)
                .isNotEqualTo(attackerSuppliedId);
    }

    @Test
    @DisplayName("GET /reviews/me — 401 when no authentication is present")
    void should_return401_when_noAuthOnGetMyReviews() throws Exception {
        mockMvc.perform(get(REVIEWS_URL + "/me")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        // The authorization gate fires before the service is reached.
        verifyNoInteractions(reviewService);
    }

    @ParameterizedTest(name = "{0} → 403")
    @EnumSource(value = Role.class, names = "CLIENT", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("GET /reviews/me — 403 when an authenticated non-CLIENT role attempts to read own reviews")
    void should_return403_when_nonClientGetsMyReviews(Role role) throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(get(REVIEWS_URL + "/me")
                        .with(authenticatedAs(userId, role.name().toLowerCase() + "@beautica.test", role))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("GET /reviews/me — routes to getMyReviews, NOT getReview({reviewId}) (literal 'me' wins over the path variable)")
    void should_routeToGetMyReviews_when_pathIsMe() throws Exception {
        var clientId = UUID.randomUUID();
        when(reviewService.getMyReviews(any(), any()))
                .thenReturn(stubMyReviewsPage(stubMyReviewResponse()));

        mockMvc.perform(get(REVIEWS_URL + "/me")
                        .with(authenticatedAs(clientId, "client@beautica.test", Role.CLIENT))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // "me" must not be interpreted as a {reviewId} path variable.
        verify(reviewService).getMyReviews(eq(clientId), any());
        verifyNoMoreInteractions(reviewService);
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
                .andExpect(jsonPath("$.success").value(false))
                // GlobalExceptionHandler#handleNotFound redacts the exception message to a
                // generic constant — the raw "Review not found" detail must NOT leak to clients.
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }
}
