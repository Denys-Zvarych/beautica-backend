package com.beautica.moderation.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ContentReportRateLimitedException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.moderation.dto.CreateReportRequest;
import com.beautica.moderation.dto.ReportResponse;
import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportStatus;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.service.ContentReportService;
import com.beautica.moderation.service.ContentReportService.CreateReportResult;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

/**
 * {@code @WebMvcTest} slice for {@link ReportController} (phase 395): HTTP contract only — validation, 401,
 * 201 vs 200, error status mapping (404 / 400 / 429). Business rules are covered by
 * {@code ContentReportServiceTest}; end to end by {@code ContentReportIT}.
 */
@WebMvcTest(ReportController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("ReportController — @WebMvcTest slice (phase 395)")
class ReportControllerTest {

    private static final String URL = "/api/v1/reports";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final UUID REPORT_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-10-28T10:15:30Z");

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
                throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
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
    private ContentReportService contentReportService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── success shapes ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("new report → 201 with { id, status, createdAt }")
    void should_return201_when_reportCreated() throws Exception {
        when(contentReportService.createReport(eq(USER_ID), eq(Role.CLIENT), any()))
                .thenReturn(result(true));

        mockMvc.perform(post(URL).with(authenticatedAs(Role.CLIENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REVIEW", TARGET_ID.toString(), "OFFENSIVE", "optional")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(REPORT_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-28T10:15:30Z"));

        verify(contentReportService).createReport(USER_ID, Role.CLIENT,
                new CreateReportRequest(ReportTargetType.REVIEW, TARGET_ID, ReportReason.OFFENSIVE, "optional"));
    }

    @Test
    @DisplayName("idempotent repeat → 200 with the existing report")
    void should_return200_when_reportAlreadyExisted() throws Exception {
        when(contentReportService.createReport(eq(USER_ID), eq(Role.SALON_OWNER), any()))
                .thenReturn(result(false));

        mockMvc.perform(post(URL).with(authenticatedAs(Role.SALON_OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CLIENT_PROFILE", TARGET_ID.toString(), "HARASSMENT", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(REPORT_ID.toString()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Role.class)
    @DisplayName("every role may report (D8)")
    void should_acceptReport_when_anyRole(Role role) throws Exception {
        when(contentReportService.createReport(eq(USER_ID), eq(role), any())).thenReturn(result(true));

        mockMvc.perform(post(URL).with(authenticatedAs(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SALON_PROFILE", TARGET_ID.toString(), "SPAM", null)))
                .andExpect(status().isCreated());
    }

    // ── 401 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no authentication → 401, service never called")
    void should_return401_when_unauthenticated() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REVIEW", TARGET_ID.toString(), "SPAM", null)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(contentReportService);
    }

    // ── validation (400) ───────────────────────────────────────────────────────

    @Test
    @DisplayName("missing targetType → 400")
    void should_return400_when_targetTypeMissing() throws Exception {
        assertBadRequest(body(null, TARGET_ID.toString(), "SPAM", null));
    }

    @Test
    @DisplayName("missing targetId → 400")
    void should_return400_when_targetIdMissing() throws Exception {
        assertBadRequest(body("REVIEW", null, "SPAM", null));
    }

    @Test
    @DisplayName("missing reason → 400")
    void should_return400_when_reasonMissing() throws Exception {
        assertBadRequest(body("REVIEW", TARGET_ID.toString(), null, null));
    }

    @Test
    @DisplayName("comment longer than 500 characters → 400")
    void should_return400_when_commentTooLong() throws Exception {
        assertBadRequest(body("REVIEW", TARGET_ID.toString(), "OTHER", "x".repeat(501)));
    }

    @ParameterizedTest(name = "comment with {0} → 400")
    @ValueSource(strings = {"\\u0000", "\\u0007", "\\u001b", "\\r", "\\t", "\\u007f", "\\u0085",
            "\\u202a", "\\u202e", "\\u2066", "\\u2069",
            "\\u200e", "\\u200f", "\\u061c", "\\u2028", "\\u2029"})
    @DisplayName("comment with a control or bidi-override character → 400")
    void should_return400_when_commentHasControlOrBidiChar(String jsonEscape) throws Exception {
        assertBadRequest(body("REVIEW", TARGET_ID.toString(), "OTHER", "before" + jsonEscape + "after"));
    }

    @Test
    @DisplayName("comment with a newline and non-ASCII text → accepted")
    void should_return201_when_commentHasNewline() throws Exception {
        when(contentReportService.createReport(eq(USER_ID), eq(Role.CLIENT), any()))
                .thenReturn(result(true));

        mockMvc.perform(post(URL).with(authenticatedAs(Role.CLIENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REVIEW", TARGET_ID.toString(), "OTHER", "Рядок один\\nрядок два ✓")))
                .andExpect(status().isCreated());

        verify(contentReportService).createReport(USER_ID, Role.CLIENT, new CreateReportRequest(
                ReportTargetType.REVIEW, TARGET_ID, ReportReason.OTHER, "Рядок один\nрядок два ✓"));
    }

    @Test
    @DisplayName("unknown targetType → generic 400 that does not leak the enum constants")
    void should_return400WithoutEnumLeak_when_targetTypeUnknown() throws Exception {
        mockMvc.perform(post(URL).with(authenticatedAs(Role.CLIENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("BOOKING_NOTE", TARGET_ID.toString(), "SPAM", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", not(containsString("CLIENT_PROFILE"))))
                .andExpect(jsonPath("$.message", not(containsString("PORTFOLIO_PHOTO"))));

        verifyNoInteractions(contentReportService);
    }

    @Test
    @DisplayName("malformed targetId → 400")
    void should_return400_when_targetIdNotUuid() throws Exception {
        assertBadRequest(body("REVIEW", "not-a-uuid", "SPAM", null));
    }

    // ── service error mapping ──────────────────────────────────────────────────

    @Test
    @DisplayName("target not visible → 404 with the generic not-found body")
    void should_return404_when_targetNotVisible() throws Exception {
        when(contentReportService.createReport(any(), any(), any()))
                .thenThrow(new NotFoundException("Report target not found"));

        mockMvc.perform(post(URL).with(authenticatedAs(Role.CLIENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REVIEW", TARGET_ID.toString(), "SPAM", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    @DisplayName("self-report → 400")
    void should_return400_when_selfReport() throws Exception {
        when(contentReportService.createReport(any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST, "Own content cannot be reported"));

        mockMvc.perform(post(URL).with(authenticatedAs(Role.INDEPENDENT_MASTER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("MASTER_PROFILE", TARGET_ID.toString(), "SPAM", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("rate limit exhausted → 429 with Retry-After")
    void should_return429_when_rateLimited() throws Exception {
        when(contentReportService.createReport(any(), any(), any()))
                .thenThrow(new ContentReportRateLimitedException(86_400));

        mockMvc.perform(post(URL).with(authenticatedAs(Role.CLIENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REVIEW", TARGET_ID.toString(), "SPAM", null)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "86400"))
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void assertBadRequest(String json) throws Exception {
        mockMvc.perform(post(URL).with(authenticatedAs(Role.CLIENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(contentReportService);
    }

    private static CreateReportResult result(boolean created) {
        return new CreateReportResult(new ReportResponse(REPORT_ID, ReportStatus.OPEN, CREATED_AT), created);
    }

    private static RequestPostProcessor authenticatedAs(Role role) {
        var token = new UsernamePasswordAuthenticationToken(
                "reporter@beautica.test", null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(USER_ID);
        return authentication(token);
    }

    private static String body(String targetType, String targetId, String reason, String comment) {
        StringBuilder json = new StringBuilder("{");
        appendField(json, "targetType", targetType);
        appendField(json, "targetId", targetId);
        appendField(json, "reason", reason);
        appendField(json, "comment", comment);
        if (json.length() > 1) {
            json.setLength(json.length() - 1);
        }
        return json.append('}').toString();
    }

    private static void appendField(StringBuilder json, String name, String value) {
        if (value != null) {
            json.append('"').append(name).append("\":\"").append(value).append("\",");
        }
    }
}
