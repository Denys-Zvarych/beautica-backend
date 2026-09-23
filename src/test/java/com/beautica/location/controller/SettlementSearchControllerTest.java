package com.beautica.location.controller;

import com.beautica.auth.JwtTokenProvider;
import com.beautica.common.exception.GlobalExceptionHandler;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.location.dto.SettlementSearchResponse;
import com.beautica.location.entity.SettlementType;
import com.beautica.location.service.SettlementSearchService;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link SettlementSearchController} — the HTTP contract only. The
 * ranking, the index and the seeded data are {@code SettlementSearchIT}'s; the normalisation and
 * routing are {@code SettlementSearchServiceTest}'s. A full {@code @SpringBootTest} here would
 * stand up Tomcat and PostgreSQL to assert a JSON shape (§M-1).
 *
 * <p>The inner {@code @TestConfiguration} reproduces the production {@code SecurityConfig}
 * contract for this path: {@code GET /api/v1/settlements} is {@code permitAll()} and everything
 * else is {@code authenticated()}. That makes the slice a guard against matcher over-broadening
 * too — a representative non-settlement request must still be rejected with 401.
 */
@WebMvcTest(SettlementSearchController.class)
@Import({WebMvcTestSupport.class, GlobalExceptionHandler.class})
@DisplayName("SettlementSearchController — @WebMvcTest slice")
class SettlementSearchControllerTest {

    private static final String URL = "/api/v1/settlements";

    // ── Security configuration — mirrors production SecurityConfig for this path ──

    @TestConfiguration
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/settlements").permitAll()
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
    private SettlementSearchService settlementSearchService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static SettlementSearchResponse lviv(UUID id) {
        return new SettlementSearchResponse(id, "Львів", SettlementType.CITY, "Львівська");
    }

    @Test
    @DisplayName("GET /settlements?query=льв — 200 unauthenticated, full field shape")
    void should_return200WithSettlements_when_queryIsExecutableAndNoAuth() throws Exception {
        UUID id = UUID.randomUUID();
        when(settlementSearchService.search("льв")).thenReturn(List.of(lviv(id)));

        mockMvc.perform(get(URL).param("query", "льв").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].settlementId").value(id.toString()))
                .andExpect(jsonPath("$.data[0].nameUk").value("Львів"))
                .andExpect(jsonPath("$.data[0].settlementType").value("CITY"))
                .andExpect(jsonPath("$.data[0].oblastNameUk").value("Львівська"));
    }

    @Test
    @DisplayName("GET /settlements — no internal ids leak on this permitAll response (§I)")
    void should_omitInternalIdentifiers_when_responseIsRenderedForAnonymousCaller() throws Exception {
        when(settlementSearchService.search(any())).thenReturn(List.of(lviv(UUID.randomUUID())));

        mockMvc.perform(get(URL).param("query", "льв").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // settlementId is the ONE identifier the client needs (D6). oblastId and the
                // KATOTTH code are deliberately absent — the retiring cascade exposes both, and
                // copying that shape onto an unauthenticated autocomplete would be a regression.
                .andExpect(jsonPath("$.data[0].oblastId").doesNotExist())
                .andExpect(jsonPath("$.data[0].katotthCode").doesNotExist())
                .andExpect(jsonPath("$.data[0].oblast").doesNotExist());
    }

    @Test
    @DisplayName("GET /settlements with no query — 200, no hint, service decides the payload")
    void should_return200WithoutHint_when_queryParameterIsAbsent() throws Exception {
        when(settlementSearchService.search(null))
                .thenReturn(List.of(lviv(UUID.randomUUID())));

        mockMvc.perform(get(URL).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("GET /settlements?query=ль — 200 with an empty list and the Ukrainian hint")
    void should_return200WithHintAndEmptyList_when_queryIsBelowMinimumLength() throws Exception {
        when(settlementSearchService.search("ль")).thenReturn(List.of());

        mockMvc.perform(get(URL).param("query", "ль").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.message").value("Введіть щонайменше 3 символи"));
    }

    @Test
    @DisplayName("GET /settlements with a 51-character query — 400 from @Size, never a query")
    void should_return400_when_queryExceedsMaxLength() throws Exception {
        // The ceiling is 50, halved from 100. @Size counts CHARACTERS and the scan pays for BYTES:
        // 100 x U+2022 is 300 bytes, and the residual cost of any term that survives the other
        // controls scales with byte-length x rows (3-char punctuation 26 ms, 100-char 119 ms). No
        // Ukrainian settlement name approaches 50 characters, so the halving costs nothing real.
        mockMvc.perform(get(URL).param("query", "л".repeat(51)).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(settlementSearchService);
    }

    @Test
    @DisplayName("GET /settlements with a 50-character query — still served; the cap is inclusive")
    void should_return200_when_queryIsExactlyAtMaxLength() throws Exception {
        // The boundary in the ALLOWING direction. Without it, tightening @Size again (to 20, say)
        // would leave the test above green while silently rejecting legitimate input.
        String atCap = "л".repeat(SettlementSearchController.MAX_QUERY_LENGTH);
        when(settlementSearchService.search(atCap)).thenReturn(List.of());

        mockMvc.perform(get(URL).param("query", atCap).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(settlementSearchService).search(atCap);
    }

    @Test
    @DisplayName("GET /settlements with a control character — 400 from @Pattern, never a query")
    void should_return400_when_queryContainsControlCharacter() throws Exception {
        // U+202E RIGHT-TO-LEFT OVERRIDE — category Cf, which ASCII \p{Cntrl} would have let through.
        mockMvc.perform(get(URL).param("query", "льв‮").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(settlementSearchService);
    }

    @Test
    @DisplayName("POST /settlements — 401; the permitAll matcher is GET-scoped, not path-scoped")
    void should_return401_when_methodIsNotGet() throws Exception {
        // The production matcher is requestMatchers(HttpMethod.GET, "/api/v1/settlements"), so a
        // POST to the same path falls through to anyRequest().authenticated() and never reaches
        // the dispatcher (there is no POST mapping either — settlement rows are Flyway-written).
        // Asserting 401 rather than 405 is what pins the METHOD half of the allow-list.
        mockMvc.perform(post(URL).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(settlementSearchService);
    }

    @Test
    @DisplayName("an unrelated GET is still 401 — the permitAll matcher is not over-broad")
    void should_return401_when_requestIsOutsideTheSettlementPath() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(settlementSearchService);
    }
}
