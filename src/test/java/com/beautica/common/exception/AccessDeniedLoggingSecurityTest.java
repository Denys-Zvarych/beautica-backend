package com.beautica.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression guard for the access-denied logging enrichment (method-security path).
 *
 * <p>Drives a REAL CLIENT-authenticated request through Spring Security's
 * {@code @PreAuthorize} interceptor to {@code GET /api/v1/masters/me} (guarded for
 * {@code SALON_MASTER}/{@code INDEPENDENT_MASTER} only). The denial surfaces as
 * {@link org.springframework.security.authorization.AuthorizationDeniedException}, handled by
 * {@code GlobalExceptionHandler.handleAuthorizationDenied}, which must:
 * <ul>
 *   <li>return HTTP 403 with the unchanged {@code ApiResponse.error("Access denied")} body, and</li>
 *   <li>emit a single enriched WARN carrying the HTTP method + request URI + the principal's
 *       authorities ({@code ROLE_CLIENT}) + the non-PII user id, while logging neither the
 *       email (PII) nor the JWT.</li>
 * </ul>
 *
 * <p>The WARN is captured by attaching a Logback {@link ListAppender} to the live
 * {@code GlobalExceptionHandler} logger — the same mechanism the unit-level
 * {@code GlobalExceptionHandlerTest} uses, here proving the wiring (filter chain →
 * method-security → advice) actually reaches the enriched line.
 *
 * <p>Against the pre-enrichment code this whole class FAILS: no WARN is emitted (Spring's
 * opaque "Access Denied" trace carried no path/principal), so {@code warnEvents} is empty.
 *
 * <p>Coverage call: the app authorises almost entirely via method-security (URL matchers are
 * {@code permitAll}/{@code authenticated} only — filter-chain 403s are rare). The
 * {@code SecurityConfig.accessDeniedHandler()} filter-chain path shares the identical
 * enriched-line format and is unit-covered by {@code GlobalExceptionHandlerTest}'s anonymous
 * case; method-security is the dominant production path and is exercised end-to-end here.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Access-denied logging — enriched WARN regression (method-security e2e)")
class AccessDeniedLoggingSecurityTest extends AbstractIntegrationTest {

    private static final String MASTER_ME_URL = "/api/v1/masters/me";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachListAppender() {
        ch.qos.logback.classic.Logger handlerLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        handlerLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachListAppender() {
        ch.qos.logback.classic.Logger handlerLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        handlerLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    @DisplayName("GET /masters/me as CLIENT — 403 unchanged body + enriched WARN (method, URI, ROLE_CLIENT, user id; no PII)")
    void should_logEnrichedWarnAndReturn403_when_clientHitsMasterOnlyEndpoint() throws Exception {
        // Arrange — a verified CLIENT (email is PII, must never be logged; user id must be).
        UUID clientUserId = UUID.randomUUID();
        String email = "deny-canary-" + System.nanoTime() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                clientUserId, email, passwordEncoder.encode(TEST_PASSWORD));
        String clientToken = loginAndGetToken(email);
        listAppender.list.clear();

        // Act — CLIENT hits a SALON_MASTER/INDEPENDENT_MASTER-only endpoint
        ResponseEntity<String> response = restTemplate.exchange(
                MASTER_ME_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(clientToken)), String.class);

        // Assert — HTTP contract unchanged: 403 with the generic body
        assertThat(response.getStatusCode())
                .as("CLIENT hitting a master-only endpoint must get 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        ApiResponse<Void> body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success())
                .as("success must be false")
                .isFalse();
        assertThat(body.message())
                .as("body must remain the generic 'Access denied' string")
                .isEqualTo("Access denied");

        // Assert — exactly one enriched WARN reached the handler through method-security
        List<ILoggingEvent> warnEvents = listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("Access denied"))
                .toList();
        assertThat(warnEvents)
                .as("method-security denial must emit exactly one enriched WARN (pre-enrichment: zero)")
                .hasSize(1);

        String logLine = warnEvents.get(0).getFormattedMessage();
        assertThat(logLine)
                .as("WARN must carry the HTTP method")
                .contains("GET");
        assertThat(logLine)
                .as("WARN must carry the request URI")
                .contains(MASTER_ME_URL);
        assertThat(logLine)
                .as("WARN must carry the principal's authorities so the missing role is obvious")
                .contains("ROLE_CLIENT");
        assertThat(logLine)
                .as("WARN must carry the non-PII subject (user id), proving getDetails() flows e2e")
                .contains(clientUserId.toString());

        // PII guard — the email must not appear in ANY captured event at any level.
        boolean emailLeaked = listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains(email));
        assertThat(emailLeaked)
                .as("no captured log event may contain the email (PII)")
                .isFalse();

        // JWT guard — the access token must not appear in any captured event.
        boolean tokenLeaked = listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains(clientToken));
        assertThat(tokenLeaked)
                .as("no captured log event may contain the raw JWT")
                .isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<AuthResponse> body = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
