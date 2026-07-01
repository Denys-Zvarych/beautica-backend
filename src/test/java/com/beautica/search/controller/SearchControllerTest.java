package com.beautica.search.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.common.exception.GlobalExceptionHandler;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchRequest;
import com.beautica.search.dto.SalonSearchResult;
import com.beautica.search.service.SearchService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link SearchController}.
 *
 * <p>Mirrors the slice pattern established by {@link com.beautica.salon.SalonControllerTest}:
 * pass-through filters from {@link WebMvcTestSupport}, an inner
 * {@code @TestConfiguration} that permits the search paths to anonymous callers
 * (matching production {@code SecurityConfig}), and direct mocking of the
 * controller's collaborator.</p>
 *
 * <p>The class-level {@code @Validated} on the controller is the surface under
 * test for the negative cases — {@code page=-1}, oversize {@code city},
 * {@code size&gt;100}, etc. Without {@code @Validated}, those constraints would
 * be silently ignored on {@code @ModelAttribute} binding (the Phase 6.2
 * regression this slice locks down).</p>
 */
@WebMvcTest(SearchController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import({WebMvcTestSupport.class, GlobalExceptionHandler.class})
@DisplayName("SearchController — @WebMvcTest slice")
class SearchControllerTest {

    private static final Logger log = LoggerFactory.getLogger(SearchControllerTest.class);
    private static final String MASTERS_URL = "/api/v1/search/masters";
    private static final String SALONS_URL = "/api/v1/search/salons";

    /**
     * Minimal {@link SecurityFilterChain} for the search slice. Mirrors the
     * production {@code SecurityConfig} permit-all rule for
     * {@code GET /api/v1/search/**} so the slice exercises the real auth path
     * (anonymous → 200), not a wide-open "everything permitted" chain.
     */
    @TestConfiguration
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                                                    JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/search/**").permitAll()
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
    private SearchService searchService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static final String SAMPLE_STREET = "вул. Хрещатик";
    private static final String SAMPLE_BUILDING_NO = "22А";
    private static final String SAMPLE_LOCATION_NOTE = "Дзвоніть за 10 хв";

    private static MasterSearchResult sampleMasterResult() {
        return new MasterSearchResult(
                UUID.randomUUID(),
                "Olha",
                "Master",
                "Київ",
                "Голосіївський район",
                4.6,
                17,
                null,                               // avatarUrl
                new BigDecimal("250.00"),           // minEffectivePrice
                new BigDecimal("400.00"),           // priceMax (a genuine range: > min)
                List.of("Манікюр", "Педикюр"),      // serviceNames
                SAMPLE_STREET,                       // street (auth-gated)
                SAMPLE_BUILDING_NO,                  // buildingNo (auth-gated)
                SAMPLE_LOCATION_NOTE,                // locationNote (auth-gated)
                List.of()                            // matchedServiceNames (no service filter)
        );
    }

    private static SalonSearchResult sampleSalonResult() {
        return new SalonSearchResult(
                UUID.randomUUID(),
                "Salon Beautica",
                "Львів",
                null,                               // districtLabel
                null,                               // avatarUrl
                new BigDecimal("150.00"),           // priceMin
                new BigDecimal("600.00"),           // priceMax
                List.of("Стрижка", "Фарбування"),   // serviceNames (never null)
                SAMPLE_STREET,                       // street (auth-gated)
                SAMPLE_BUILDING_NO,                  // buildingNo (auth-gated)
                SAMPLE_LOCATION_NOTE,                // locationNote (auth-gated)
                List.of()                            // matchedServiceNames (no service filter)
        );
    }

    // ── GET /api/v1/search/masters ───────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 without authentication (public)")
    void should_return200_when_publicMasterSearch() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        log.debug("Act: GET {}?location.cityId=<uuid> without auth — must be 200", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("location.cityId", UUID.randomUUID().toString())
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when every filter param is provided")
    void should_return200_when_allSearchFiltersProvided() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        log.debug("Act: GET {} with location.cityId/districtId/category/min+maxPrice/minRating — must be 200", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("location.cityId", UUID.randomUUID().toString())
                        .param("location.districtId", UUID.randomUUID().toString())
                        .param("category", "MANICURE")
                        .param("minPrice", "100.00")
                        .param("maxPrice", "999.99")
                        .param("minRating", "4.0")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when page is negative (validates @PositiveOrZero)")
    void should_return400_when_negativePage() throws Exception {
        log.debug("Act: GET {} with page=-1 — must trigger Bean Validation and return 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "-1")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when location.cityId is not a valid UUID (generic 400, no surface leak)")
    void should_return400_when_locationCityIdMalformed() throws Exception {
        log.debug("Act: GET {} with location.cityId=not-a-uuid — binder must reject with a generic 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("location.cityId", "not-a-uuid")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when ?size exceeds 100 (@Max enforcement)")
    void should_return400_when_sizeExceedsMax() throws Exception {
        log.debug("Act: GET {} with size=101 — must fail @Max(100) and return 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "0")
                        .param("size", "101")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when ?page exceeds 500 (tightened cap in Phase 6.5)")
    void should_return400_when_pageExceedsTightenedCap() throws Exception {
        log.debug("Act: GET {} with page=501 — must fail @Max(500) and return 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "501")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── Boundary matrix: page / size @Max + @Positive/@PositiveOrZero edges ───

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when ?size is exactly 100 (at @Max ceiling, accepted)")
    void should_return200_when_sizeAtMaxCeiling() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        log.debug("Act: GET {} with size=100 — exactly at @Max(100), must be accepted (200)", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "0")
                        .param("size", "100")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when ?size is exactly 1 (at @Positive lower edge, accepted)")
    void should_return200_when_sizeAtPositiveLowerEdge() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 1), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        log.debug("Act: GET {} with size=1 — lowest @Positive value, must be accepted (200)", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "0")
                        .param("size", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when ?page is exactly 500 (at @Max ceiling, accepted)")
    void should_return200_when_pageAtMaxCeiling() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(500, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        log.debug("Act: GET {} with page=500 — exactly at @Max(500), must be accepted (200)", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "500")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── Boundary matrix: minPrice / maxPrice negative + precision (@DecimalMin / @Digits) ──

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when minPrice is negative (@DecimalMin(0))")
    void should_return400_when_minPriceNegative() throws Exception {
        log.debug("Act: GET {} with minPrice=-0.01 — must fail @DecimalMin(0) and return 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minPrice", "-0.01")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when maxPrice is negative (@DecimalMin(0))")
    void should_return400_when_maxPriceNegative() throws Exception {
        log.debug("Act: GET {} with maxPrice=-1 — must fail @DecimalMin(0) and return 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("maxPrice", "-1")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when minPrice has excess fraction digits (compact ctor rounds to scale 2 before @Digits)")
    void should_return200_when_minPriceFractionRoundedToScale2() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        log.debug("Act: GET {} with minPrice=1700.0000000000002 — float-slider artifact, must round to 1700.00 and return 200", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minPrice", "1700.0000000000002")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when minPrice has 9 integer digits (@Digits integer=8)")
    void should_return400_when_minPriceExceedsIntegerPrecision() throws Exception {
        log.debug("Act: GET {} with minPrice=123456789 (9 int digits) — must fail @Digits(integer=8) and return 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minPrice", "123456789")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when minPrice has exactly 8 integer + 2 fraction digits (at @Digits ceiling)")
    void should_return200_when_minPriceAtDigitsCeiling() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        log.debug("Act: GET {} with minPrice=12345678.90 — exactly NUMERIC(10,2), must be accepted (200)", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minPrice", "12345678.90")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when maxPrice has excess fraction digits (compact ctor rounds to scale 2 before @Digits)")
    void should_return200_when_maxPriceFractionRoundedToScale2() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        // The exact prod artifact from the mobile float price slider, mirrored on the
        // MAX bound. Pre-fix (no compact ctor) the 13-fraction-digit value trips
        // @Digits(fraction=2) → spurious 400; post-fix it rounds to 1700.00 → 200.
        log.debug("Act: GET {} with maxPrice=1700.0000000000002 — float-slider artifact, must round to 1700.00 and return 200", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("maxPrice", "1700.0000000000002")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when both price bounds round HALF_UP into the same scale-2 bucket (cross-field guard not tripped by raw values)")
    void should_return200_when_priceBoundsRoundHalfUpAndRangeStaysValid() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        // RAW minPrice (99.014) > RAW maxPrice (99.006): a pre-fix DTO would 400 on
        // @AssertTrue (min > max) AND on @Digits (3 fraction digits). The compact ctor
        // rounds BOTH HALF_UP into 99.01 (99.006 rounds UP, 99.014 rounds down), so the
        // cross-field guard sees 99.01 <= 99.01 → valid → 200. This pins ordering:
        // rounding happens before @Digits AND before @AssertTrue.
        log.debug("Act: GET {} with minPrice=99.014 & maxPrice=99.006 — both round HALF_UP to 99.01, range must stay valid → 200", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minPrice", "99.014")
                        .param("maxPrice", "99.006")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when maxPrice rounds to 9 integer digits (magnitude guard survives rounding)")
    void should_return400_when_maxPriceRoundsToNineIntegerDigits() throws Exception {
        // Rounding collapses only excess FRACTION digits — it must never relax the
        // 8-integer-digit cap. 123456789.0000002 rounds to 123456789.00 (9 integer
        // digits) and still fails @Digits(integer=8) → 400.
        log.debug("Act: GET {} with maxPrice=123456789.0000002 — rounds to 9 int digits, must still fail @Digits(integer=8) → 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("maxPrice", "123456789.0000002")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when sub-half-cent negative minPrice rounds to 0.00 (intended benign edge)")
    void should_return200_when_subHalfCentNegativeMinPriceRoundsToZero() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        // Documented benign edge (accepted by security): a sub-half-cent negative
        // (|value| < 0.005) rounds HALF_UP to 0.00, which satisfies @DecimalMin(0) → 200.
        // This is NOT a negative-bypass: -0.01 (>= half a cent) still rounds to -0.01
        // and is rejected by should_return400_when_minPriceNegative.
        log.debug("Act: GET {} with minPrice=-0.001 — rounds to 0.00, intended benign edge → 200", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minPrice", "-0.001")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── Boundary matrix: minRating range 0.0..5.0 (@DecimalMin / @DecimalMax) ──

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when minRating is exactly 0.0 (at @DecimalMin lower edge)")
    void should_return200_when_minRatingAtLowerEdge() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        log.debug("Act: GET {} with minRating=0.0 — exactly at @DecimalMin(0.0), must be accepted (200)", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minRating", "0.0")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when minRating is exactly 5.0 (at @DecimalMax upper edge)")
    void should_return200_when_minRatingAtUpperEdge() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        log.debug("Act: GET {} with minRating=5.0 — exactly at @DecimalMax(5.0), must be accepted (200)", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minRating", "5.0")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when minRating has excess fraction digits (compact ctor rounds to scale 2 before @Digits)")
    void should_return200_when_minRatingFractionRoundedToScale2() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        // Rating slider artifact mirroring the price case: 4.7000000000001 (13 fraction
        // digits) would trip @Digits(integer=1, fraction=2) pre-fix; the compact ctor
        // rounds it to 4.70 (still within 0.00–5.00) → 200.
        log.debug("Act: GET {} with minRating=4.7000000000001 — float artifact, must round to 4.70 and return 200", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minRating", "4.7000000000001")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when minRating exceeds 5.0 (@DecimalMax upper bound)")
    void should_return400_when_minRatingAboveUpperBound() throws Exception {
        log.debug("Act: GET {} with minRating=5.01 — must fail @DecimalMax(5.0) and return 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minRating", "5.01")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when minRating is negative (@DecimalMin lower bound)")
    void should_return400_when_minRatingNegative() throws Exception {
        log.debug("Act: GET {} with minRating=-0.01 — must fail @DecimalMin(0.0) and return 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minRating", "-0.01")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — paginated envelope is present in response JSON")
    void should_returnPaginatedResponse_when_resultPageReturned() throws Exception {
        MasterSearchResult one = sampleMasterResult();
        MasterSearchResult two = sampleMasterResult();
        // PageImpl clamps total when pageable.offset + pageSize > total on the last page,
        // so we use a small pageSize (2) to keep total=7 honored: offset(0)+pageSize(2) <= 7.
        Page<MasterSearchResult> page = new PageImpl<>(List.of(one, two), PageRequest.of(0, 2), 7L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(page);

        log.debug("Act: GET {} expecting PageResponse envelope (data/page/size/totalElements/totalPages)", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "0")
                        .param("size", "2")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data").isArray())
                .andExpect(jsonPath("$.data.data.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(7))
                .andExpect(jsonPath("$.data.totalPages").value(4));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — response must not contain userId (internal UUID must not leak to anonymous callers)")
    void should_not_expose_userId_in_publicMasterSearchResponse() throws Exception {
        MasterSearchResult result = sampleMasterResult();
        Page<MasterSearchResult> page = new PageImpl<>(List.of(result), PageRequest.of(0, 20), 1L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[*].userId").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when minPrice exceeds maxPrice (DTO @AssertTrue fires before @Cacheable)")
    void should_return400_when_minPriceExceedsMaxPrice() throws Exception {
        // No service stub — the @AssertTrue on MasterSearchRequest fires at MVC
        // argument-resolution time, before @Cacheable intercepts, so the mock
        // service is never called. Verifying this is the whole point of the fix:
        // the guard must work on cached paths too.
        log.debug("Act: GET {} with minPrice=500 > maxPrice=100 — DTO @AssertTrue must reject with 400 before service", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minPrice", "500.00")
                        .param("maxPrice", "100.00")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 even on cached pages when minPrice exceeds maxPrice")
    void should_returnBadRequest_when_minPriceExceedsMaxPrice_cachedPath() throws Exception {
        // Arrange: warm the cache with a valid page-0 request.
        Page<MasterSearchResult> validPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(MasterSearchRequest.class), any(Pageable.class)))
                .thenReturn(validPage);

        mockMvc.perform(get(MASTERS_URL)
                        .param("minPrice", "100.00")
                        .param("maxPrice", "500.00")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Act: send an invalid range for the same page-0 (which @Cacheable would
        // otherwise serve from cache). The @AssertTrue on MasterSearchRequest fires
        // at argument-resolution, before @Cacheable, so this must be 400 — not a
        // cached 200 from the previous valid request.
        log.debug("Act: GET {} with minPrice=999 > maxPrice=1 on cached page-0 — must be 400, not a cached 200", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minPrice", "999.00")
                        .param("maxPrice", "1.00")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── GET /api/v1/search/salons ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/search/salons — 200 without authentication (public)")
    void should_return200_when_publicSalonSearch() throws Exception {
        Page<SalonSearchResult> page = new PageImpl<>(List.of(sampleSalonResult()), PageRequest.of(0, 20), 1L);
        when(searchService.searchSalons(any(SalonSearchRequest.class), any(Pageable.class))).thenReturn(page);

        log.debug("Act: GET {}?location.cityId=<uuid> without auth — must be 200", SALONS_URL);
        mockMvc.perform(get(SALONS_URL)
                        .param("location.cityId", UUID.randomUUID().toString())
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 400 when page is negative (validates @PositiveOrZero)")
    void should_return400_when_negativePage_forSalonSearch() throws Exception {
        mockMvc.perform(get(SALONS_URL)
                        .param("page", "-1")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 400 when ?size exceeds 100 (@Max enforcement)")
    void should_return400_when_sizeExceedsMax_forSalonSearch() throws Exception {
        mockMvc.perform(get(SALONS_URL)
                        .param("page", "0")
                        .param("size", "101")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 400 when ?page exceeds 500 (tightened cap)")
    void should_return400_when_pageExceedsTightenedCap_forSalonSearch() throws Exception {
        mockMvc.perform(get(SALONS_URL)
                        .param("page", "501")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 200 when ?size is exactly 100 (at @Max ceiling, accepted)")
    void should_return200_when_sizeAtMaxCeiling_forSalonSearch() throws Exception {
        Page<SalonSearchResult> page = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0L);
        when(searchService.searchSalons(any(SalonSearchRequest.class), any(Pageable.class))).thenReturn(page);

        log.debug("Act: GET {} with size=100 — exactly at @Max(100), must be accepted (200)", SALONS_URL);
        mockMvc.perform(get(SALONS_URL)
                        .param("page", "0")
                        .param("size", "100")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 200 when ?page is exactly 500 (at @Max ceiling, accepted)")
    void should_return200_when_pageAtMaxCeiling_forSalonSearch() throws Exception {
        Page<SalonSearchResult> page = new PageImpl<>(List.of(), PageRequest.of(500, 20), 0L);
        when(searchService.searchSalons(any(SalonSearchRequest.class), any(Pageable.class))).thenReturn(page);

        log.debug("Act: GET {} with page=500 — exactly at @Max(500), must be accepted (200)", SALONS_URL);
        mockMvc.perform(get(SALONS_URL)
                        .param("page", "500")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 400 when ?size is zero (@Positive lower bound)")
    void should_return400_when_sizeIsZero_forSalonSearch() throws Exception {
        mockMvc.perform(get(SALONS_URL)
                        .param("page", "0")
                        .param("size", "0")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when ?size is zero (@Positive lower bound)")
    void should_return400_when_sizeIsZero() throws Exception {
        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "0")
                        .param("size", "0")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 with default paging when page and size are absent")
    void should_return200_with_defaultPaging_when_pageAndSizeAbsentFromRequest() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        mockMvc.perform(get(MASTERS_URL)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── q / sort / serviceNames (Phase — search contract additions) ──────────

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when q and a valid sort are supplied (binds q + enum)")
    void should_return200_when_qAndSortProvided() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        mockMvc.perform(get(MASTERS_URL)
                        .param("q", "Olena")
                        .param("sort", "PRICE_ASC")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when sort is not in the allow-list (enum bind fails → generic 400)")
    void should_return400_when_unknownSort() throws Exception {
        mockMvc.perform(get(MASTERS_URL)
                        .param("sort", "DROP_TABLE")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when q contains a control character (@Pattern)")
    void should_return400_when_qHasControlChar() throws Exception {
        mockMvc.perform(get(MASTERS_URL)
                        .param("q", "<script>")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when q exceeds 100 characters (@Size)")
    void should_return400_when_qTooLong() throws Exception {
        mockMvc.perform(get(MASTERS_URL)
                        .param("q", "a".repeat(101))
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — serviceNames array is present in the result DTO")
    void should_exposeServiceNames_inMasterSearchResponse() throws Exception {
        Page<MasterSearchResult> page = new PageImpl<>(
                List.of(sampleMasterResult()), PageRequest.of(0, 20), 1L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[0].serviceNames").isArray())
                .andExpect(jsonPath("$.data.data[0].serviceNames[0]").value("Манікюр"));
    }

    // ── priceMax semantics (FIXED vs RANGE discriminator) ────────────────────

    @Test
    @DisplayName("GET /api/v1/search/masters — priceMax is present in the result DTO and exceeds minEffectivePrice for a range master")
    void should_exposePriceMax_aboveMin_forRangeMaster() throws Exception {
        // sampleMasterResult() seeds min=250.00, priceMax=400.00 — a genuine range.
        Page<MasterSearchResult> page = new PageImpl<>(
                List.of(sampleMasterResult()), PageRequest.of(0, 20), 1L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[0].minEffectivePrice").value(250.00))
                .andExpect(jsonPath("$.data.data[0].priceMax").value(400.00));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — priceMax equals minEffectivePrice for a single FIXED-price master")
    void should_returnEqualPriceMaxAndMin_forFixedPriceMaster() throws Exception {
        MasterSearchResult fixed = new MasterSearchResult(
                UUID.randomUUID(), "Iryna", "Fixed", "Київ", null,
                4.9, 5, null,
                new BigDecimal("300.00"),   // minEffectivePrice
                new BigDecimal("300.00"),   // priceMax == min → FIXED single price
                List.of("Манікюр"),
                SAMPLE_STREET, SAMPLE_BUILDING_NO, SAMPLE_LOCATION_NOTE,
                List.of());
        Page<MasterSearchResult> page = new PageImpl<>(List.of(fixed), PageRequest.of(0, 20), 1L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[0].minEffectivePrice").value(300.00))
                .andExpect(jsonPath("$.data.data[0].priceMax").value(300.00));
    }

    // ── AUTH-GATE street address (security regression guard, pins SearchController.java:157) ─
    //
    // The mocked SearchService always returns a result WITH street + buildingNo
    // populated (the cache always holds the full object). The controller's
    // isAuthenticated() gate decides whether the per-request strip runs. These
    // four tests pin the exact behaviour so a future refactor to
    // `auth.isAuthenticated()` (which is TRUE for an AnonymousAuthenticationToken)
    // can never silently start leaking masters'/salons' home addresses to anon.

    @Test
    @DisplayName("GET /api/v1/search/masters — ANONYMOUS caller gets street, buildingNo AND locationNote nulled (no address leak)")
    void should_stripStreetAddress_forMasters_when_anonymous() throws Exception {
        Page<MasterSearchResult> page = new PageImpl<>(
                List.of(sampleMasterResult()), PageRequest.of(0, 20), 1L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(page);

        // No authentication() post-processor → the test SecurityFilterChain admits
        // the request as anonymous (AnonymousAuthenticationToken), so the strip fires.
        mockMvc.perform(get(MASTERS_URL)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[0].street").value((Object) null))
                .andExpect(jsonPath("$.data.data[0].buildingNo").value((Object) null))
                .andExpect(jsonPath("$.data.data[0].locationNote").value((Object) null))
                // public locality labels must still be visible to anonymous callers
                .andExpect(jsonPath("$.data.data[0].cityLabel").value("Київ"));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — AUTHENTICATED caller gets street, buildingNo AND locationNote populated")
    void should_returnStreetAddress_forMasters_when_authenticated() throws Exception {
        Page<MasterSearchResult> page = new PageImpl<>(
                List.of(sampleMasterResult()), PageRequest.of(0, 20), 1L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(MASTERS_URL)
                        .with(authentication(authenticatedClient()))
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[0].street").value(SAMPLE_STREET))
                .andExpect(jsonPath("$.data.data[0].buildingNo").value(SAMPLE_BUILDING_NO))
                .andExpect(jsonPath("$.data.data[0].locationNote").value(SAMPLE_LOCATION_NOTE));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — ANONYMOUS caller gets street, buildingNo AND locationNote nulled (no address leak)")
    void should_stripStreetAddress_forSalons_when_anonymous() throws Exception {
        Page<SalonSearchResult> page = new PageImpl<>(
                List.of(sampleSalonResult()), PageRequest.of(0, 20), 1L);
        when(searchService.searchSalons(any(SalonSearchRequest.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(SALONS_URL)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[0].street").value((Object) null))
                .andExpect(jsonPath("$.data.data[0].buildingNo").value((Object) null))
                .andExpect(jsonPath("$.data.data[0].locationNote").value((Object) null))
                .andExpect(jsonPath("$.data.data[0].cityLabel").value("Львів"));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — AUTHENTICATED caller gets street, buildingNo AND locationNote populated")
    void should_returnStreetAddress_forSalons_when_authenticated() throws Exception {
        Page<SalonSearchResult> page = new PageImpl<>(
                List.of(sampleSalonResult()), PageRequest.of(0, 20), 1L);
        when(searchService.searchSalons(any(SalonSearchRequest.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(SALONS_URL)
                        .with(authentication(authenticatedClient()))
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[0].street").value(SAMPLE_STREET))
                .andExpect(jsonPath("$.data.data[0].buildingNo").value(SAMPLE_BUILDING_NO))
                .andExpect(jsonPath("$.data.data[0].locationNote").value(SAMPLE_LOCATION_NOTE));
    }

    // ── salon serviceNames contract (never null) ─────────────────────────────

    @Test
    @DisplayName("GET /api/v1/search/salons — serviceNames is a present array for a salon with priced services")
    void should_exposeServiceNames_inSalonSearchResponse() throws Exception {
        Page<SalonSearchResult> page = new PageImpl<>(
                List.of(sampleSalonResult()), PageRequest.of(0, 20), 1L);
        when(searchService.searchSalons(any(SalonSearchRequest.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(SALONS_URL)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[0].serviceNames").isArray())
                .andExpect(jsonPath("$.data.data[0].serviceNames.length()").value(2))
                .andExpect(jsonPath("$.data.data[0].serviceNames[0]").value("Стрижка"));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — serviceNames serialises as [] (never null) for a salon with no services")
    void should_serialiseEmptyServiceNames_asArray_forSalonWithNoServices() throws Exception {
        SalonSearchResult noServices = new SalonSearchResult(
                UUID.randomUUID(), "Empty Salon", "Київ", null, null,
                null, null,                 // priceMin / priceMax null
                List.of(),                  // serviceNames empty (never null per the contract)
                null, null, null,           // street / buildingNo / locationNote
                List.of());                 // matchedServiceNames (no service filter)
        Page<SalonSearchResult> page = new PageImpl<>(List.of(noServices), PageRequest.of(0, 20), 1L);
        when(searchService.searchSalons(any(SalonSearchRequest.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(SALONS_URL)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data[0].serviceNames").isArray())
                .andExpect(jsonPath("$.data.data[0].serviceNames.length()").value(0));
    }

    /**
     * Builds a non-anonymous {@link org.springframework.security.core.Authentication}
     * for the authenticated-caller auth-gate tests. A
     * {@link org.springframework.security.authentication.UsernamePasswordAuthenticationToken}
     * with authorities is authenticated AND is NOT an
     * {@link org.springframework.security.authentication.AnonymousAuthenticationToken},
     * so {@code SearchController.isAuthenticated()} returns {@code true} and the
     * street-address strip is skipped.
     */
    private static org.springframework.security.core.Authentication authenticatedClient() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "client@test.com",
                "n/a",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CLIENT")));
    }

    // ── salon q / sort / price filter binding ────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/search/salons — 200 when q, sort and price band are supplied")
    void should_return200_when_salonQSortPriceProvided() throws Exception {
        Page<SalonSearchResult> page = new PageImpl<>(
                List.of(sampleSalonResult()), PageRequest.of(0, 20), 1L);
        when(searchService.searchSalons(any(SalonSearchRequest.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(SALONS_URL)
                        .param("q", "Glow")
                        .param("sort", "PRICE_DESC")
                        .param("minPrice", "100.00")
                        .param("maxPrice", "500.00")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 400 when minPrice exceeds maxPrice (@AssertTrue)")
    void should_return400_when_salonMinPriceExceedsMaxPrice() throws Exception {
        mockMvc.perform(get(SALONS_URL)
                        .param("minPrice", "500.00")
                        .param("maxPrice", "100.00")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 400 when minPrice is negative (@DecimalMin)")
    void should_return400_when_salonMinPriceNegative() throws Exception {
        mockMvc.perform(get(SALONS_URL)
                        .param("minPrice", "-1")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 200 when minPrice has excess fraction digits (compact ctor rounds to scale 2 before @Digits)")
    void should_return200_when_salonMinPriceFractionRoundedToScale2() throws Exception {
        Page<SalonSearchResult> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchSalons(any(SalonSearchRequest.class), any(Pageable.class))).thenReturn(page);

        // Salon parity for the prod float-slider artifact on the MIN bound.
        log.debug("Act: GET {} with minPrice=1700.0000000000002 — must round to 1700.00 and return 200", SALONS_URL);
        mockMvc.perform(get(SALONS_URL)
                        .param("minPrice", "1700.0000000000002")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 200 when maxPrice has excess fraction digits (compact ctor rounds to scale 2 before @Digits)")
    void should_return200_when_salonMaxPriceFractionRoundedToScale2() throws Exception {
        Page<SalonSearchResult> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchSalons(any(SalonSearchRequest.class), any(Pageable.class))).thenReturn(page);

        // The exact prod artifact, mirrored on the salon MAX bound.
        log.debug("Act: GET {} with maxPrice=1700.0000000000002 — must round to 1700.00 and return 200", SALONS_URL);
        mockMvc.perform(get(SALONS_URL)
                        .param("maxPrice", "1700.0000000000002")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 200 when both price bounds round HALF_UP into the same scale-2 bucket (cross-field guard not tripped by raw values)")
    void should_return200_when_salonPriceBoundsRoundHalfUpAndRangeStaysValid() throws Exception {
        Page<SalonSearchResult> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchSalons(any(SalonSearchRequest.class), any(Pageable.class))).thenReturn(page);

        // RAW minPrice (99.014) > RAW maxPrice (99.006) — both round HALF_UP to 99.01,
        // so the cross-field @AssertTrue sees a valid range. Pre-fix this 400s on both
        // @Digits (3 fraction digits) and @AssertTrue (raw min > raw max).
        log.debug("Act: GET {} with minPrice=99.014 & maxPrice=99.006 — both round HALF_UP to 99.01, range must stay valid → 200", SALONS_URL);
        mockMvc.perform(get(SALONS_URL)
                        .param("minPrice", "99.014")
                        .param("maxPrice", "99.006")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 400 when maxPrice rounds to 9 integer digits (magnitude guard survives rounding)")
    void should_return400_when_salonMaxPriceRoundsToNineIntegerDigits() throws Exception {
        log.debug("Act: GET {} with maxPrice=123456789.0000002 — rounds to 9 int digits, must still fail @Digits(integer=8) → 400", SALONS_URL);
        mockMvc.perform(get(SALONS_URL)
                        .param("maxPrice", "123456789.0000002")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── serviceTypeSlugs validation (Phase 20.1–20.2 — @Size limit + @Pattern) ─

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when serviceTypeSlugs exceeds the 20-entry limit (@Size(max=20))")
    void should_return400_when_serviceTypeSlugsExceedLimit() throws Exception {
        String[] slugs = new String[21];
        for (int i = 0; i < slugs.length; i++) {
            slugs[i] = "slug-" + i;   // each individually pattern-valid; the LIST size is the violation
        }
        log.debug("Act: GET {} with 21 serviceTypeSlugs — must fail @Size(max=20) and return 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("serviceTypeSlugs", slugs)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 when a serviceTypeSlug violates the lowercase-hyphenated @Pattern")
    void should_return400_when_serviceTypeSlugViolatesPattern() throws Exception {
        log.debug("Act: GET {} with serviceTypeSlugs=Not_A_Slug — must fail the slug @Pattern and return 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("serviceTypeSlugs", "Not_A_Slug")   // upper-case + underscore → invalid
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/salons — 400 when a serviceTypeSlug violates the lowercase-hyphenated @Pattern (salon parity)")
    void should_return400_when_salonServiceTypeSlugViolatesPattern() throws Exception {
        mockMvc.perform(get(SALONS_URL)
                        .param("serviceTypeSlugs", "Bad Slug!")   // space + bang → invalid
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 when serviceTypeSlugs holds valid lowercase-hyphenated slugs (binds the list)")
    void should_return200_when_serviceTypeSlugsValid() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        mockMvc.perform(get(MASTERS_URL)
                        .param("serviceTypeSlugs", "hair-treatment-keratin", "injection-mesotherapy")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
