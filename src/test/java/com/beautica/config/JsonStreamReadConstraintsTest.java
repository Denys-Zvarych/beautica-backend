package com.beautica.config;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
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
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the global Jackson {@code StreamReadConstraints.maxStringLength} cap
 * ({@link JsonConfig} + {@link JsonConstraintsProperties}) is applied to the actual
 * request-parsing {@code ObjectMapper} wired into Spring MVC's
 * {@code MappingJackson2HttpMessageConverter}.
 *
 * <p>The cap rejects an oversized JSON string token at the <em>parse</em> phase — before
 * any {@code @Size} validation runs and before the value is materialised onto the heap.
 * The resulting {@code StreamConstraintsException} is wrapped by Spring in
 * {@code HttpMessageNotReadableException}, which {@code GlobalExceptionHandler} maps to a
 * generic HTTP 400 (no internal detail leak), never a 500 and never an OOM.
 *
 * <p>A minimal test-only controller ({@link JsonCapEchoTestController}) is used so the
 * slice exercises only the parse path, independent of any feature controller's
 * collaborators. The cap is pinned via {@code app.json.max-string-length} so the boundary
 * is deterministic regardless of the shipped default.
 */
@WebMvcTest(JsonCapEchoTestController.class)
@Import({WebMvcTestSupport.class, JsonConfig.class})
@TestPropertySource(properties = {
        "app.json.max-string-length=65536",
        "app.json.max-nesting-depth=64",
        "app.json.max-number-length=100"
})
@DisplayName("Jackson StreamReadConstraints — global parse caps (string / nesting / number)")
class JsonStreamReadConstraintsTest {

    private static final Logger log = LoggerFactory.getLogger(JsonStreamReadConstraintsTest.class);

    private static final String ECHO_URL = "/test-only/json-cap/echo";
    private static final String RAW_URL = "/test-only/json-cap/raw";

    /** Mirrors the configured caps so the boundaries in this test are explicit. */
    private static final int MAX_STRING_LENGTH = 65_536;
    private static final int MAX_NESTING_DEPTH = 64;
    private static final int MAX_NUMBER_LENGTH = 100;

    @TestConfiguration
    static class SecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("string token exceeding the cap is rejected with a clean 400 (parse-time), never 500/OOM")
    void should_return400_when_jsonStringExceedsMaxLength() throws Exception {
        // Arrange: a single string value one character over the cap.
        String oversized = "a".repeat(MAX_STRING_LENGTH + 1);
        String body = "{\"note\":\"" + oversized + "\"}";

        // Act + Assert: parser aborts → HttpMessageNotReadableException → generic 400.
        log.debug("Act: POST a {}-char string token (cap={}) — must 400 at parse time", oversized.length(), MAX_STRING_LENGTH);
        mvc.perform(post(ECHO_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Request body is malformed or missing required fields"));
    }

    @Test
    @DisplayName("normal-sized string (2000 chars) parses successfully — no false rejection")
    void should_return200_when_jsonStringWithinLimit() throws Exception {
        // Arrange: 2000 chars — the largest common free-text field size, well under the cap.
        String normal = "b".repeat(2000);
        String body = "{\"note\":\"" + normal + "\"}";

        // Act + Assert: parses cleanly and the controller echoes the value back.
        log.debug("Act: POST a 2000-char string token — must parse and reach the controller");
        mvc.perform(post(ECHO_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    // ── nesting depth cap ─────────────────────────────────────────

    @Test
    @DisplayName("JSON nested deeper than maxNestingDepth is rejected with a clean 400 (parse-time), never 500/OOM/StackOverflow")
    void should_return400_when_jsonNestingExceedsMaxDepth() throws Exception {
        // Arrange: 70 nested arrays — 6 levels past the cap of 64. The parser must abort
        // at depth 65, before recursion can exhaust the stack or build the tree.
        int depth = MAX_NESTING_DEPTH + 6;
        String body = "[".repeat(depth) + "]".repeat(depth);

        // Act + Assert: StreamConstraintsException → HttpMessageNotReadableException → generic 400.
        log.debug("Act: POST {}-level nested array (cap={}) — must 400 at parse time", depth, MAX_NESTING_DEPTH);
        mvc.perform(post(RAW_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Request body is malformed or missing required fields"));
    }

    @Test
    @DisplayName("JSON nested exactly at maxNestingDepth still parses — no off-by-one false rejection")
    void should_return200_when_jsonNestingAtExactLimit() throws Exception {
        // Arrange: exactly 64 nested arrays — the deepest the cap permits.
        String body = "[".repeat(MAX_NESTING_DEPTH) + "]".repeat(MAX_NESTING_DEPTH);

        // Act + Assert: parses cleanly and reaches the raw controller (200).
        log.debug("Act: POST {}-level nested array at exact cap — must parse and reach the controller", MAX_NESTING_DEPTH);
        mvc.perform(post(RAW_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    // ── number length cap ─────────────────────────────────────────

    @Test
    @DisplayName("numeric token longer than maxNumberLength is rejected with a clean 400 (parse-time), never 500")
    void should_return400_when_jsonNumberExceedsMaxLength() throws Exception {
        // Arrange: a single integer token of 101 digits — one past the cap of 100.
        String oversizedNumber = "1".repeat(MAX_NUMBER_LENGTH + 1);
        String body = "{\"amount\":" + oversizedNumber + "}";

        // Act + Assert: StreamConstraintsException → HttpMessageNotReadableException → generic 400.
        log.debug("Act: POST a {}-digit number token (cap={}) — must 400 at parse time", oversizedNumber.length(), MAX_NUMBER_LENGTH);
        mvc.perform(post(RAW_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Request body is malformed or missing required fields"));
    }

    @Test
    @DisplayName("numeric token exactly at maxNumberLength still parses — no off-by-one false rejection")
    void should_return200_when_jsonNumberAtExactLimit() throws Exception {
        // Arrange: a 100-digit integer token — the longest the cap permits.
        String maxNumber = "1".repeat(MAX_NUMBER_LENGTH);
        String body = "{\"amount\":" + maxNumber + "}";

        // Act + Assert: parses cleanly and reaches the raw controller (200).
        log.debug("Act: POST a {}-digit number token at exact cap — must parse and reach the controller", MAX_NUMBER_LENGTH);
        mvc.perform(post(RAW_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
