package com.beautica.search.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.GlobalExceptionHandler;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.MasterSearchResult;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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

    private static MasterSearchResult sampleMasterResult() {
        return new MasterSearchResult(
                UUID.randomUUID(),
                "Olha",
                "Master",
                "Київ",
                4.6,
                17,
                null,
                new BigDecimal("250.00")
        );
    }

    private static SalonSearchResult sampleSalonResult() {
        return new SalonSearchResult(
                UUID.randomUUID(),
                "Salon Beautica",
                "Львів",
                "Lviv Oblast",
                null
        );
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ── GET /api/v1/search/masters ───────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/search/masters — 200 without authentication (public)")
    void should_return200_when_publicMasterSearch() throws Exception {
        Page<MasterSearchResult> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(searchService.searchMasters(any(), any(Pageable.class))).thenReturn(empty);

        log.debug("Act: GET {}?city=Київ without auth — must be 200", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("city", "Київ")
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

        log.debug("Act: GET {} with city/region/category/min+maxPrice/minRating — must be 200", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("city", "Київ")
                        .param("region", "Kyiv Oblast")
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
    @DisplayName("GET /api/v1/search/masters — 400 when ?city length exceeds 100 chars (@Size enforcement)")
    void should_return400_when_cityExceeds100Chars() throws Exception {
        String oversizedCity = repeat('a', 101);

        log.debug("Act: GET {} with city length=101 — must fail @Size and return 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("city", oversizedCity)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
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
    @DisplayName("GET /api/v1/search/masters — 400 when minPrice exceeds maxPrice (service-layer cross-field check)")
    void should_return400_when_minPriceExceedsMaxPrice() throws Exception {
        when(searchService.searchMasters(any(MasterSearchRequest.class), any(Pageable.class)))
                .thenThrow(new BusinessException("minPrice must not exceed maxPrice"));

        log.debug("Act: GET {} with minPrice > maxPrice — service raises BusinessException → 400", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("minPrice", "500.00")
                        .param("maxPrice", "100.00")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("minPrice must not exceed maxPrice")));
    }

    @Test
    @DisplayName("GET /api/v1/search/masters — 400 with safe message when city contains a control character")
    void should_return400AndNotEchoRawInput_when_cityContainsControlChar() throws Exception {
        // U+0007 BEL — caught by the @Pattern "^[^\\p{Cntrl}]*$" on MasterSearchRequest.city.
        String controlCharInput = "Київ";

        log.debug("Act: GET {} with city containing BEL — must reject without echoing the raw bytes", MASTERS_URL);
        mockMvc.perform(get(MASTERS_URL)
                        .param("city", controlCharInput)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("control character")))
                .andExpect(jsonPath("$.message").value(not(containsString(""))));
    }

    // ── GET /api/v1/search/salons ────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/search/salons — 200 without authentication (public)")
    void should_return200_when_publicSalonSearch() throws Exception {
        Page<SalonSearchResult> page = new PageImpl<>(List.of(sampleSalonResult()), PageRequest.of(0, 20), 1L);
        when(searchService.searchSalons(eq("Львів"), any(), any(Pageable.class))).thenReturn(page);

        log.debug("Act: GET {}?city=Львів without auth — must be 200", SALONS_URL);
        mockMvc.perform(get(SALONS_URL)
                        .param("city", "Львів")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }
}
