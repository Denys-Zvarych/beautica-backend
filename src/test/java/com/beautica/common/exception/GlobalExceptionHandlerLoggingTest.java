package com.beautica.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 500 — {@link GlobalExceptionHandler#handleBusiness} leaves a traceable line for a 4xx business rejection:
 * 409 at WARN, other 4xx at INFO, naming the handler's path TEMPLATE (never the raw URI). Driven through a real
 * {@code DispatcherServlet} ({@code standaloneSetup}) because the template only exists as the
 * {@code BEST_MATCHING_PATTERN_ATTRIBUTE} a matched handler mapping sets; a direct {@code handleBusiness(ex)} call
 * has no request and could not tell a template from a raw URI.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("GlobalExceptionHandler — business 4xx logging (phase 500)")
class GlobalExceptionHandlerLoggingTest {

    private static final String SUGGEST_TEMPLATE = "/api/v1/service-types/suggest";
    private static final String TOKEN_TEMPLATE = "/api/v1/stub-links/{token}";
    private static final String RAW_TOKEN = "s3cr3t-T0ken-abcdef0123456789";
    private static final String PENDING_MESSAGE = "A matching suggestion is already pending review";
    private static final String BAD_INPUT_MESSAGE = "Booking window is closed";
    private static final String USER_INPUT_MESSAGE = "Unsupported sort property: client-typed-value";

    private static final String BAD_REQUEST_PATH = "/api/v1/stub-bad-request";
    private static final String CONFLICT_LINE = "Business 409 CONFLICT on POST " + SUGGEST_TEMPLATE;
    private static final int OVER_LIMIT = 5;
    private static final String SUMMARY_MARKER = "Business 4xx suppressed";

    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private Level previousHandlerLevel;
    private MockMvc mockMvc;
    private MutableClock clock;

    /** Hand-advanced clock so the throttle window rolls without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-11-22T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @RestController
    @RequestMapping("/api/v1")
    static class StubController {

        @PostMapping("/service-types/suggest")
        void suggest() {
            throw new BusinessException(HttpStatus.CONFLICT, PENDING_MESSAGE);
        }

        @PostMapping("/stub-bad-request")
        void badRequest() {
            throw new BusinessException(HttpStatus.BAD_REQUEST, BAD_INPUT_MESSAGE);
        }

        @GetMapping("/stub-links/{token}")
        void byToken(@PathVariable String token) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "Link can no longer be used");
        }

        @GetMapping("/stub-sorted")
        void sorted() {
            throw BusinessException.withUserInput(HttpStatus.BAD_REQUEST, USER_INPUT_MESSAGE);
        }

        @GetMapping("/stub-unavailable")
        void unavailable() {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "Upstream down");
        }
    }

    @BeforeEach
    void setUp() {
        // Pin DEBUG so the DEBUG-path assertions hold regardless of run order: a Spring context booted earlier in
        // this JVM re-applies application.yml (com.beautica at INFO), and CI runs with -PtestLogLevel=INFO.
        previousHandlerLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);
        clock = new MutableClock();
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
                .setControllerAdvice(new GlobalExceptionHandler(new BusinessLogThrottle(clock)))
                .build();
    }

    @AfterEach
    void restoreHandlerLevel() {
        handlerLogger.setLevel(previousHandlerLevel);
    }

    @Test
    @DisplayName("a 409 logs one WARN line with the path template and the message")
    void should_logWarnWithTemplateAndMessage_when_conflict(CapturedOutput output) throws Exception {
        // Act
        mockMvc.perform(post(SUGGEST_TEMPLATE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Request could not be completed due to a conflict"));

        // Assert
        List<String> lines = businessLines(output);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
                .contains("WARN")
                .contains("Business 409 CONFLICT on POST " + SUGGEST_TEMPLATE + ": " + PENDING_MESSAGE);
    }

    @Test
    @DisplayName("a 400 logs one INFO line, not WARN")
    void should_logInfo_when_badRequest(CapturedOutput output) throws Exception {
        // Act
        mockMvc.perform(post("/api/v1/stub-bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request"));

        // Assert
        List<String> lines = businessLines(output);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
                .contains("INFO")
                .doesNotContain("WARN")
                .contains("Business 400 BAD_REQUEST on POST /api/v1/stub-bad-request: " + BAD_INPUT_MESSAGE);
    }

    @Test
    @DisplayName("a {token} path logs the template, never the raw token or query string")
    void should_logTemplateNotToken_when_pathCarriesToken(CapturedOutput output) throws Exception {
        // Act
        mockMvc.perform(get("/api/v1/stub-links/" + RAW_TOKEN).queryParam("sig", RAW_TOKEN))
                .andExpect(status().isUnprocessableEntity());

        // Assert
        List<String> lines = businessLines(output);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
                .contains("INFO")
                .contains("Business 422 UNPROCESSABLE_ENTITY on GET " + TOKEN_TEMPLATE + ":");
        assertThat(output.getAll()).doesNotContain(RAW_TOKEN);
    }

    @Test
    @DisplayName("a message carrying user input stays at DEBUG, without the Business status line")
    void should_keepDebug_when_messageCarriesUserInput(CapturedOutput output) throws Exception {
        // Act
        mockMvc.perform(get("/api/v1/stub-sorted"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request"));

        // Assert
        assertThat(businessLines(output)).isEmpty();
        assertThat(linesContaining(output, USER_INPUT_MESSAGE))
                .as("setUp pins the handler logger at DEBUG, so the DEBUG line must be visible")
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("DEBUG").doesNotContain("INFO").doesNotContain("WARN"));
    }

    @Test
    @DisplayName("a 5xx keeps its previous DEBUG path")
    void should_keepDebug_when_serverError(CapturedOutput output) throws Exception {
        // Act
        mockMvc.perform(get("/api/v1/stub-unavailable"))
                .andExpect(status().isServiceUnavailable());

        // Assert
        assertThat(businessLines(output)).isEmpty();
        assertThat(linesContaining(output, "Business exception: Upstream down"))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("DEBUG"));
    }

    @Test
    @DisplayName("throttle: past the per-window limit the line is suppressed, the body is not")
    void should_suppressLines_when_limitReachedWithinWindow(CapturedOutput output) throws Exception {
        // Act
        performConflicts(BusinessLogThrottle.LINES_PER_WINDOW + OVER_LIMIT);

        // Assert
        assertThat(linesContaining(output, CONFLICT_LINE)).hasSize(BusinessLogThrottle.LINES_PER_WINDOW);
        assertThat(linesContaining(output, SUMMARY_MARKER)).isEmpty();
    }

    @Test
    @DisplayName("throttle: the first hit after the window rolls logs ONE summary line, then the line again")
    void should_logOneSummaryLine_when_windowRollsOver(CapturedOutput output) throws Exception {
        // Arrange
        performConflicts(BusinessLogThrottle.LINES_PER_WINDOW + OVER_LIMIT);
        clock.advance(BusinessLogThrottle.WINDOW);

        // Act
        performConflicts(2);

        // Assert
        assertThat(linesContaining(output, SUMMARY_MARKER))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains("WARN")
                        .contains("Business 4xx suppressed " + OVER_LIMIT + " lines on POST " + SUGGEST_TEMPLATE));
        assertThat(linesContaining(output, CONFLICT_LINE)).hasSize(BusinessLogThrottle.LINES_PER_WINDOW + 2);
    }

    @Test
    @DisplayName("throttle: no summary line when the rolled window suppressed nothing")
    void should_notLogSummary_when_previousWindowUnderLimit(CapturedOutput output) throws Exception {
        // Arrange
        performConflicts(1);
        clock.advance(BusinessLogThrottle.WINDOW);

        // Act
        performConflicts(1);

        // Assert
        assertThat(linesContaining(output, SUMMARY_MARKER)).isEmpty();
        assertThat(linesContaining(output, CONFLICT_LINE)).hasSize(2);
    }

    @Test
    @DisplayName("throttle: each path template has its own budget")
    void should_logOtherTemplate_when_oneTemplateIsThrottled(CapturedOutput output) throws Exception {
        // Arrange
        performConflicts(BusinessLogThrottle.LINES_PER_WINDOW + OVER_LIMIT);

        // Act
        mockMvc.perform(post(BAD_REQUEST_PATH)).andExpect(status().isBadRequest());

        // Assert
        assertThat(linesContaining(output, "Business 400 BAD_REQUEST on POST " + BAD_REQUEST_PATH)).hasSize(1);
    }

    @Test
    @DisplayName("throttle: the tracked-template map stays bounded")
    void should_boundTrackedTemplates_when_manyDistinctTemplatesSeen() {
        // Arrange
        BusinessLogThrottle throttle = new BusinessLogThrottle(clock);
        int distinct = BusinessLogThrottle.MAX_TRACKED_KEYS * 3;

        // Act
        for (int i = 0; i < distinct; i++) {
            throttle.acquire("GET", "/api/v1/template-" + i);
        }

        // Assert
        assertThat(throttle.trackedKeys()).isLessThanOrEqualTo(BusinessLogThrottle.MAX_TRACKED_KEYS);
    }

    private void performConflicts(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            mockMvc.perform(post(SUGGEST_TEMPLATE))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Request could not be completed due to a conflict"));
        }
    }

    private static List<String> businessLines(CapturedOutput output) {
        return linesContaining(output, "Business 4");
    }

    private static List<String> linesContaining(CapturedOutput output, String fragment) {
        return output.getAll().lines().filter(line -> line.contains(fragment)).toList();
    }
}
