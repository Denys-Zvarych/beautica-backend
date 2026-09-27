package com.beautica.search.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.common.exception.GlobalExceptionHandler;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.search.dto.LocationFilter;
import com.beautica.search.dto.SearchSuggestionResponse;
import com.beautica.search.dto.SuggestionType;
import com.beautica.search.service.SearchSuggestionService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link SearchSuggestionController} (Phase 331) — the HTTP
 * contract only. Ranking/matching is {@code SearchSuggestionServiceTest}'s; the seeded-data
 * round trip is {@code SearchSuggestionIT}'s.
 *
 * <p>Mirrors {@code SearchControllerTest} / {@code SettlementSearchControllerTest}: pass-through
 * filters from {@link WebMvcTestSupport}, an inner {@code @TestConfiguration} reproducing
 * production {@code SecurityConfig}'s {@code GET /api/v1/search/**} permit-all rule, and direct
 * mocking of the controller's one collaborator.
 */
@WebMvcTest(SearchSuggestionController.class)
@Import({WebMvcTestSupport.class, GlobalExceptionHandler.class})
@DisplayName("SearchSuggestionController — @WebMvcTest slice")
class SearchSuggestionControllerTest {

    private static final String URL = "/api/v1/search/suggestions";

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
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SearchSuggestionService searchSuggestionService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static SearchSuggestionResponse category(String label, String key) {
        return new SearchSuggestionResponse(SuggestionType.CATEGORY, label, key, null);
    }

    @Test
    @DisplayName("GET /search/suggestions?q=нар — 200 unauthenticated, full field shape")
    void should_return200WithSuggestions_when_qIsValidAndNoAuth() throws Exception {
        when(searchSuggestionService.suggest(eq("нар"), eq(8), any()))
                .thenReturn(List.of(category("Нарощення вій", "LASH_EXTENSIONS")));

        mockMvc.perform(get(URL).param("q", "нар").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("CATEGORY"))
                .andExpect(jsonPath("$.data[0].label").value("Нарощення вій"))
                .andExpect(jsonPath("$.data[0].categoryKey").value("LASH_EXTENSIONS"))
                .andExpect(jsonPath("$.data[0].serviceTypeSlug").doesNotExist());
    }

    @Test
    @DisplayName("no match -> 200 with an empty list, never 404")
    void should_return200WithEmptyList_when_nothingMatches() throws Exception {
        when(searchSuggestionService.suggest(eq("zzz"), eq(8), any())).thenReturn(List.of());

        mockMvc.perform(get(URL).param("q", "zzz").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("limit omitted -> defaults to 8")
    void should_defaultLimitToEight_when_limitOmitted() throws Exception {
        when(searchSuggestionService.suggest(eq("ман"), eq(8), any())).thenReturn(List.of());

        mockMvc.perform(get(URL).param("q", "ман").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(searchSuggestionService).suggest("ман", 8, null);
    }

    @Test
    @DisplayName("limit=3 -> forwarded as-is")
    void should_forwardExplicitLimit() throws Exception {
        when(searchSuggestionService.suggest(eq("ман"), eq(3), any())).thenReturn(List.of());

        mockMvc.perform(get(URL).param("q", "ман").param("limit", "3").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(searchSuggestionService).suggest("ман", 3, null);
    }

    @Test
    @DisplayName("location.cityId binds into LocationFilter and is forwarded to the service")
    void should_bindLocationCityId_intoLocationFilter() throws Exception {
        UUID cityId = UUID.randomUUID();
        when(searchSuggestionService.suggest(any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get(URL).param("q", "ман")
                        .param("location.cityId", cityId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        ArgumentCaptor<LocationFilter> captor = ArgumentCaptor.forClass(LocationFilter.class);
        verify(searchSuggestionService).suggest(eq("ман"), eq(8), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().cityId()).isEqualTo(cityId);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().districtId()).isNull();
    }

    // ── validation — @Validated must actually fire on the @ModelAttribute-bound record ────────

    @Test
    @DisplayName("q blank -> 400, service never invoked")
    void should_return400_when_qIsBlank() throws Exception {
        mockMvc.perform(get(URL).param("q", "").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(searchSuggestionService);
    }

    @Test
    @DisplayName("q missing entirely -> 400, service never invoked")
    void should_return400_when_qIsAbsent() throws Exception {
        mockMvc.perform(get(URL).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(searchSuggestionService);
    }

    @Test
    @DisplayName("q at 51 characters -> 400 from @Size, never a query")
    void should_return400_when_qExceeds50Characters() throws Exception {
        mockMvc.perform(get(URL).param("q", "н".repeat(51)).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(searchSuggestionService);
    }

    @Test
    @DisplayName("q at exactly 50 characters -> still served, the cap is inclusive")
    void should_return200_when_qIsExactlyAtMaxLength() throws Exception {
        String atCap = "н".repeat(50);
        when(searchSuggestionService.suggest(eq(atCap), eq(8), any())).thenReturn(List.of());

        mockMvc.perform(get(URL).param("q", atCap).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(searchSuggestionService).suggest(atCap, 8, null);
    }

    @Test
    @DisplayName("q with a control character -> 400 from @Pattern, never a query")
    void should_return400_when_qContainsControlCharacter() throws Exception {
        // U+202E RIGHT-TO-LEFT OVERRIDE — category Cf.
        mockMvc.perform(get(URL).param("q", "нар‮").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(searchSuggestionService);
    }

    @Test
    @DisplayName("limit=9 -> 400 from @Max")
    void should_return400_when_limitExceedsEight() throws Exception {
        mockMvc.perform(get(URL).param("q", "ман").param("limit", "9").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(searchSuggestionService);
    }

    @Test
    @DisplayName("limit=0 -> 400 from @Min")
    void should_return400_when_limitIsZero() throws Exception {
        mockMvc.perform(get(URL).param("q", "ман").param("limit", "0").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(searchSuggestionService);
    }

    @Test
    @DisplayName("location.cityId=not-a-uuid -> 400, value not echoed")
    void should_return400_when_cityIdIsMalformed() throws Exception {
        mockMvc.perform(get(URL).param("q", "ман").param("location.cityId", "not-a-uuid")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("not-a-uuid"))));

        verifyNoInteractions(searchSuggestionService);
    }

    @Test
    @DisplayName("location.districtId=not-a-uuid -> 400, value not echoed")
    void should_return400_when_districtIdIsMalformed() throws Exception {
        mockMvc.perform(get(URL).param("q", "ман").param("location.districtId", "not-a-uuid")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("not-a-uuid"))));

        verifyNoInteractions(searchSuggestionService);
    }

    @Test
    @DisplayName("POST /search/suggestions — 401; the permitAll matcher is GET-scoped, not path-scoped "
            + "(mirrors SettlementSearchControllerTest: the security matcher rejects the method before "
            + "the dispatcher's own 405 would ever fire, and there is no POST mapping either)")
    void should_return401_when_methodIsPost() throws Exception {
        mockMvc.perform(post(URL).param("q", "ман").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(searchSuggestionService);
    }
}
