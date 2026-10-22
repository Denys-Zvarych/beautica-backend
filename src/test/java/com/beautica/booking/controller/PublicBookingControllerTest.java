package com.beautica.booking.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.BookingSlugInfoResponse;
import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.dto.GuestBookingRequest;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.booking.dto.SalonBookingSlugInfoResponse;
import com.beautica.booking.dto.SalonBookingSlugMasterItem;
import com.beautica.booking.dto.ServiceSummaryDto;
import com.beautica.booking.service.BookingCancellationService;
import com.beautica.booking.service.BookingSlugService;
import com.beautica.booking.service.GuestBookingService;
import com.beautica.booking.service.SalonBookingSlugService;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link PublicBookingController}.
 *
 * <p>The inner {@code @TestConfiguration} mirrors the production
 * {@code SecurityConfig} contract: {@code GET /api/v1/book/**} is
 * {@code permitAll()} and everything else is {@code authenticated()}. This also
 * guards against matcher over-broadening — a non-book GET must still be 401.
 */
@WebMvcTest(PublicBookingController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("PublicBookingController — @WebMvcTest slice")
class PublicBookingControllerTest {

    @TestConfiguration
    static class SecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/book/**").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/book/*/booking").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/book/cancel/*").permitAll()
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

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BookingSlugService bookingSlugService;

    @MockBean
    private GuestBookingService guestBookingService;

    @MockBean
    private BookingCancellationService bookingCancellationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private SalonBookingSlugService salonBookingSlugService;

    @Test
    @DisplayName("GET /book/{slug}/info — 200 with master + services and NO Authorization header")
    void should_return200WithMasterAndServices_when_slugExists() throws Exception {
        var response = masterPage(
                "Марія Левченко",
                "https://cdn.example/a.jpg",
                "Манікюр та педикюр",
                List.of(new ServiceSummaryDto(UUID.randomUUID(), "Манікюр", 60, new BigDecimal("350.00"), "NAILS")));
        when(bookingSlugService.findBySlug("marija-l-cd34")).thenReturn(response);

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masterName").value("Марія Левченко"))
                .andExpect(jsonPath("$.avatarUrl").value("https://cdn.example/a.jpg"))
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].name").value("Манікюр"))
                .andExpect(jsonPath("$.services[0].durationMinutes").value(60))
                .andExpect(jsonPath("$.services[0].priceFrom").value(350.00));
    }

    @Test
    @DisplayName("GET /book/{slug}/info — public response carries no owner/master/user identifiers")
    void should_notLeakInternalIds_when_slugExists() throws Exception {
        var response = masterPage(
                "Ivan Petrenko", null, null,
                List.of(new ServiceSummaryDto(UUID.randomUUID(), "Стрижка", 45, new BigDecimal("200.00"), "HAIR")));
        when(bookingSlugService.findBySlug("ivan-petrenko-ab12")).thenReturn(response);

        mockMvc.perform(get("/api/v1/book/ivan-petrenko-ab12/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.services[0].ownerId").doesNotExist())
                .andExpect(jsonPath("$.services[0].ownerType").doesNotExist());
    }

    @Test
    @DisplayName("GET /book/{slug}/info — 404 when slug unknown")
    void should_return404_when_slugUnknown() throws Exception {
        when(bookingSlugService.findBySlug("unknown-slug"))
                .thenThrow(new NotFoundException("Booking page not found"));

        mockMvc.perform(get("/api/v1/book/unknown-slug/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /book/{slug}/info — 400 when slug violates the charset pattern (no DB hit)")
    void should_return400_when_slugMalformed() throws Exception {
        mockMvc.perform(get("/api/v1/book/Bad_Slug!/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingSlugService);
    }

    // ── Phase 13.3: availability ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /book/{slug}/availability — 200 with slots, NO auth required")
    void should_return200WithSlots_when_availabilityRequested() throws Exception {
        UUID svc = UUID.randomUUID();
        var slot = new AvailableSlotResponse(
                ZonedDateTime.parse("2026-06-10T12:00:00+03:00[Europe/Kyiv]"),
                ZonedDateTime.parse("2026-06-10T13:00:00+03:00[Europe/Kyiv]"));
        when(guestBookingService.availableSlots(eq("marija-l-cd34"), any(), eq(svc)))
                .thenReturn(List.of(slot));

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/availability")
                        .param("date", "2026-06-10")
                        .param("serviceId", svc.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("GET /book/{slug}/availability — 400 when date is in the past / over the window")
    void should_return400_when_availabilityDateOutOfWindow() throws Exception {
        UUID svc = UUID.randomUUID();
        // A single serviceId param routes to the single-UUID availableSlots overload; disambiguate the
        // mock from the multi-service List overload added in BE-7.
        when(guestBookingService.availableSlots(anyString(), any(), any(UUID.class)))
                .thenThrow(new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "date is in the past"));

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/availability")
                        .param("date", "2020-01-01")
                        .param("serviceId", svc.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── Phase 13.3: guest booking creation ────────────────────────────────────

    @Test
    @DisplayName("POST /book/{slug}/booking — 201 with confirmed booking")
    void should_return201_when_guestBookingCreated() throws Exception {
        UUID svc = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var req = new GuestBookingRequest(svc, null, OffsetDateTime.parse("2099-06-10T12:00:00+03:00"), "Олена", "Коваль", true, "2026-10-20");
        var resp = new GuestBookingResponse(bookingId, req.startsAt(), "Марія Левченко", "Манікюр", 60,
                "https://app.beautica.test/book/cancel/" + UUID.randomUUID());
        when(guestBookingService.createGuestBooking(eq("Bearer guest.token"), eq("marija-l-cd34"), any()))
                .thenReturn(resp);

        mockMvc.perform(post("/api/v1/book/marija-l-cd34/booking")
                        .header("Authorization", "Bearer guest.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.serviceName").value("Манікюр"))
                .andExpect(jsonPath("$.cancelUrl").value(resp.cancelUrl()));
    }

    @Test
    @DisplayName("POST /book/{slug}/booking — 401 when guest token is expired/invalid")
    void should_return401_when_tokenInvalid() throws Exception {
        UUID svc = UUID.randomUUID();
        var req = new GuestBookingRequest(svc, null, OffsetDateTime.parse("2099-06-10T12:00:00+03:00"), "A", "B", true, "2026-10-20");
        when(guestBookingService.createGuestBooking(anyString(), anyString(), any()))
                .thenThrow(new io.jsonwebtoken.ExpiredJwtException(null, null, "expired"));

        mockMvc.perform(post("/api/v1/book/marija-l-cd34/booking")
                        .header("Authorization", "Bearer expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /book/{slug}/booking — 400 when body fails Bean Validation (blank name)")
    void should_return400_when_nameBlank() throws Exception {
        UUID svc = UUID.randomUUID();
        String body = """
                {"serviceId":"%s","startsAt":"2099-06-10T12:00:00+03:00","name":"","surname":"Коваль","privacyAccepted":true,"privacyPolicyVersion":"2026-10-20"}
                """.formatted(svc);

        mockMvc.perform(post("/api/v1/book/marija-l-cd34/booking")
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(guestBookingService);
    }

    // ── Phase 13.4: guest cancel by link ──────────────────────────────────────

    @Test
    @DisplayName("GET /book/cancel/{token} — 200 with cancellable=true, NO auth required")
    void should_return200WithCancellableTrue_when_tokenValid() throws Exception {
        UUID token = UUID.randomUUID();
        var info = new CancelTokenInfoResponse(
                "Марія Левченко", "Манікюр",
                OffsetDateTime.parse("2099-06-10T12:00:00+03:00"), true,
                OffsetDateTime.parse("2099-06-10T10:00:00+03:00"));
        when(bookingCancellationService.getInfo(token)).thenReturn(info);

        mockMvc.perform(get("/api/v1/book/cancel/" + token).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masterName").value("Марія Левченко"))
                .andExpect(jsonPath("$.serviceName").value("Манікюр"))
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.windowClosesAt").exists());
    }

    @Test
    @DisplayName("GET /book/cancel/{token} — 200 with cancellable=false when inside the window")
    void should_return200WithCancellableFalse_when_insideWindow() throws Exception {
        UUID token = UUID.randomUUID();
        var info = new CancelTokenInfoResponse(
                "Ivan", "Стрижка",
                OffsetDateTime.parse("2099-06-10T12:00:00+03:00"), false,
                OffsetDateTime.parse("2099-06-10T10:00:00+03:00"));
        when(bookingCancellationService.getInfo(token)).thenReturn(info);

        mockMvc.perform(get("/api/v1/book/cancel/" + token).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellable").value(false));
    }

    @Test
    @DisplayName("GET /book/cancel/{token} — 404 when token unknown / consumed (no hint)")
    void should_return404_when_cancelTokenUnknown() throws Exception {
        UUID token = UUID.randomUUID();
        when(bookingCancellationService.getInfo(token))
                .thenThrow(new NotFoundException("Cancel token not found"));

        mockMvc.perform(get("/api/v1/book/cancel/" + token).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /book/cancel/{token} — 400 when the token is not a UUID (Spring converter, no DB hit)")
    void should_return400_when_cancelTokenMalformed() throws Exception {
        mockMvc.perform(get("/api/v1/book/cancel/not-a-uuid").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingCancellationService);
    }

    @Test
    @DisplayName("POST /book/cancel/{token} — 204 No Content, NO auth required")
    void should_return204_when_cancelSucceeds() throws Exception {
        UUID token = UUID.randomUUID();
        org.mockito.Mockito.doNothing().when(bookingCancellationService).cancel(token);

        mockMvc.perform(post("/api/v1/book/cancel/" + token))
                .andExpect(status().isNoContent());

        verify(bookingCancellationService).cancel(token);
    }

    @Test
    @DisplayName("POST /book/cancel/{token} — 422 when inside the cancellation window")
    void should_return422_when_postInsideWindow() throws Exception {
        UUID token = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new BusinessException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "Скасування недоступне — менше ніж 2 год до запису"))
                .when(bookingCancellationService).cancel(token);

        mockMvc.perform(post("/api/v1/book/cancel/" + token))
                .andExpect(status().isUnprocessableEntity())
                // End-to-end through the real GlobalExceptionHandler: the cancel-window 422 copy
                // must reach the client verbatim (Ukrainian, with the window-hours interpolated) —
                // not redacted like CONFLICT/BAD_REQUEST. Pins the cancel path specifically, not
                // just the generic handler unit test.
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Скасування недоступне — менше ніж 2 год до запису"));
    }

    @Test
    @DisplayName("POST /book/cancel/{token} — 404 when token already consumed (idempotent replay)")
    void should_return404_when_postTokenConsumed() throws Exception {
        UUID token = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new NotFoundException("Cancel token not found"))
                .when(bookingCancellationService).cancel(token);

        mockMvc.perform(post("/api/v1/book/cancel/" + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /book/cancel/{token} — 400 when the token is not a UUID (no DB hit, no hint)")
    void should_return400_when_postCancelTokenMalformed() throws Exception {
        mockMvc.perform(post("/api/v1/book/cancel/not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingCancellationService);
    }

    /** Phase 380: a fully populated enriched master payload around the legacy four fields. */
    private static BookingSlugInfoResponse masterPage(
            String name, String avatarUrl, String bio, List<ServiceSummaryDto> services) {
        return new BookingSlugInfoResponse(
                name, avatarUrl, bio, services, UUID.randomUUID(),
                com.beautica.master.entity.MasterType.INDEPENDENT_MASTER, null, null, null, null, null, 0,
                List.of(), List.of(), List.of(), true, java.time.Instant.parse("2026-10-18T09:12:44Z"),
                List.of(), null, null, null);
    }

    // ── Phase 385: guest booking comment ──────────────────────────────────────

    @Test
    @DisplayName("POST /book/{slug}/booking — a clientComment over 1000 chars or with control characters is 400, never reaches the service")
    void should_return400_when_clientCommentTooLongOrHasControlCharacters() throws Exception {
        UUID svc = UUID.randomUUID();
        for (String bad : new String[] {"а".repeat(1001), "до\\u0000бре"}) {
            String body = "{\"serviceId\":\"" + svc + "\",\"startsAt\":\"2099-06-10T12:00:00+03:00\","
                    + "\"name\":\"Олена\",\"surname\":\"Коваль\",\"privacyAccepted\":true,"
                    + "\"privacyPolicyVersion\":\"2026-10-20\",\"clientComment\":\"" + bad + "\"}";
            mockMvc.perform(post("/api/v1/book/marija-l-cd34/booking")
                            .header("Authorization", "Bearer guest.jwt.token")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(guestBookingService, org.mockito.Mockito.never()).createGuestBooking(any(), any(), any());
    }

    // ── Phase 383: GET /book/{slug}/reviews ────────────────────────────────────

    @Test
    @DisplayName("GET /book/{slug}/reviews — 200 PageResponse with the info Cache-Control, sort and size passed through")
    void should_return200WithPage_when_reviewsRequested() throws Exception {
        var review = new com.beautica.review.dto.ReviewResponse(UUID.randomUUID(), UUID.randomUUID(), "Олена К.",
                "Манікюр", 5, "Дуже акуратно", java.time.OffsetDateTime.parse("2026-10-12T15:20:00Z"));
        when(bookingSlugService.reviewsForSlug(eq("marija-l-cd34"),
                eq(com.beautica.review.dto.SalonReviewSort.HIGHEST), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(review),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/book/marija-l-cd34/reviews?sort=HIGHEST&page=0&size=20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=0, public, s-maxage=30"))
                .andExpect(jsonPath("$.data[0].clientDisplayName").value("Олена К."))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /book/{slug}/reviews — unknown sort is 400, malformed slug is 400, neither reaches the service")
    void should_return400_when_reviewsSortOrSlugInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/book/marija-l-cd34/reviews?sort=BOGUS").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/BAD_SLUG/reviews").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingSlugService);
    }

    @Test
    @DisplayName("GET /book/{slug}/reviews — unknown slug is 404")
    void should_return404_when_reviewsSlugUnknown() throws Exception {
        when(bookingSlugService.reviewsForSlug(eq("nope-zz99"), any(), any()))
                .thenThrow(new NotFoundException("Booking page not found"));

        mockMvc.perform(get("/api/v1/book/nope-zz99/reviews").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /book/s/{slug}/reviews — 200 PageResponse<SalonReviewResponse> with the info Cache-Control")
    void should_return200WithSalonReviewPage_when_salonReviewsRequested() throws Exception {
        var review = new com.beautica.review.dto.SalonReviewResponse(UUID.randomUUID(), UUID.randomUUID(), "Anna",
                "Bila", "Олена К.", "Манікюр", 5, "Дуже акуратно",
                java.time.OffsetDateTime.parse("2026-10-12T15:20:00Z"));
        when(bookingSlugService.salonReviewsForSlug(eq("studiia-krasa-ab12"),
                eq(com.beautica.review.dto.SalonReviewSort.OLDEST), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(review),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/book/s/studiia-krasa-ab12/reviews?sort=OLDEST").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=0, public, s-maxage=30"))
                .andExpect(jsonPath("$.data[0].masterFirstName").value("Anna"))
                .andExpect(jsonPath("$.data[0].clientDisplayName").value("Олена К."));
    }

    @Test
    @DisplayName("GET /book/s/{slug}/reviews — bad sort / malformed slug are 400 without a service call; unknown slug is 404")
    void should_return400Or404_when_salonReviewsInputInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/book/s/studiia-krasa-ab12/reviews?sort=NOPE")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/book/s/BAD_SLUG/reviews")).andExpect(status().isBadRequest());
        verifyNoInteractions(bookingSlugService);
        when(bookingSlugService.salonReviewsForSlug(eq("nope-zz99"), any(), any()))
                .thenThrow(new NotFoundException("Booking page not found"));

        mockMvc.perform(get("/api/v1/book/s/nope-zz99/reviews")).andExpect(status().isNotFound());
    }

    // ── Phase 377: public salon booking page ──────────────────────────────────

    private static SalonBookingSlugInfoResponse salonPage(UUID salonId) {
        return new SalonBookingSlugInfoResponse(
                salonId, "Студія Краса", "Вінниця", "https://cdn.example/logo.jpg", null,
                new BigDecimal("4.80"), 12, true,
                List.of(new SalonBookingSlugMasterItem("anna-bila-aa11", "Anna Bila", null, "Стиліст", new BigDecimal("4.90"), 7)),
                "Вінницька область", "вул. Соборна", "12", java.time.Instant.parse("2026-10-15T08:00:00Z"),
                null, null, List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("GET /book/s/{slug}/info — 200 with salon + bookable masters and NO Authorization header")
    void should_return200WithSalonAndMasters_when_salonSlugExists() throws Exception {
        UUID salonId = UUID.randomUUID();
        when(salonBookingSlugService.findBySlug("studiia-krasa-ab12")).thenReturn(salonPage(salonId));

        mockMvc.perform(get("/api/v1/book/s/studiia-krasa-ab12/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salonId").value(salonId.toString()))
                .andExpect(jsonPath("$.name").value("Студія Краса"))
                .andExpect(jsonPath("$.city").value("Вінниця"))
                .andExpect(jsonPath("$.avgRating").value(4.80))
                .andExpect(jsonPath("$.reviewCount").value(12))
                .andExpect(jsonPath("$.bookable").value(true))
                .andExpect(jsonPath("$.masters.length()").value(1))
                .andExpect(jsonPath("$.masters[0].masterSlug").value("anna-bila-aa11"))
                .andExpect(jsonPath("$.masters[0].professionalTitle").value("Стиліст"))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.masters[0].masterId").doesNotExist())
                .andExpect(jsonPath("$.masters[0].userId").doesNotExist());
        verifyNoInteractions(bookingSlugService);
    }

    @Test
    @DisplayName("GET /book/s/{slug}/info — 404 when the salon slug is unknown / inactive")
    void should_return404_when_salonSlugUnknown() throws Exception {
        when(salonBookingSlugService.findBySlug("no-such-salon"))
                .thenThrow(new NotFoundException("Booking page not found"));

        mockMvc.perform(get("/api/v1/book/s/no-such-salon/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /book/s/{slug}/info — 400 when the slug violates the charset pattern (no lookup)")
    void should_return400_when_salonSlugMalformed() throws Exception {
        mockMvc.perform(get("/api/v1/book/s/Bad_Slug!/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(salonBookingSlugService);
    }

    @Test
    @DisplayName("GET /book/s/info — routes to the MASTER handler with slug 's' (1 char) → 400, salon handler untouched")
    void should_notRouteToSalonHandler_when_pathIsBookSInfo() throws Exception {
        mockMvc.perform(get("/api/v1/book/s/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(salonBookingSlugService);
        verifyNoInteractions(bookingSlugService);
    }

    @Test
    @DisplayName("GET /book/{slug}/info for a master slug never reaches the salon handler")
    void should_routeMasterSlugToMasterHandler_when_notUnderS() throws Exception {
        when(bookingSlugService.findBySlug("ivan-petrenko-ab12"))
                .thenReturn(masterPage("Ivan Petrenko", null, null, List.of()));

        mockMvc.perform(get("/api/v1/book/ivan-petrenko-ab12/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verifyNoInteractions(salonBookingSlugService);
    }
}
