package com.beautica.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the RESOLVER-ORDERING half of {@link GlobalExceptionHandler}: that Spring picks the
 * specific 405/406 handlers over {@code @ExceptionHandler(Exception.class)}.
 *
 * <p><strong>Why a MockMvc test and not a unit call.</strong> Invoking
 * {@code handler.handleMethodNotSupported(ex)} directly proves the method returns 405, which was
 * never in doubt. The DEFECT was that the method did not exist, so
 * {@code ExceptionHandlerExceptionResolver} (order 0) matched {@code Exception.class} and
 * shadowed {@code DefaultHandlerExceptionResolver} (order 2) — the framework component that would
 * otherwise have produced the 405. Only a request driven through a real resolver chain can tell
 * "the handler works" apart from "the handler is reached". Deleting either handler must turn this
 * class red.
 *
 * <p><strong>Why a stub controller and not {@code PublicBookingController}.</strong> The
 * production trigger is {@code GET /api/v1/book/{slug}/booking} — {@code /api/v1/book/**} is
 * {@code permitAll} for guest booking, so an anonymous caller could loop it and mint an unbounded
 * stream of ERROR-level stack traces (HTTP 500 + {@code log.error("Unhandled exception", ex)}) on
 * a metered host. What the resolver sees is a path with mappings but not for this verb; the real
 * controller would drag its whole dependency graph in to reproduce a condition Spring's
 * {@code RequestMappingHandlerMapping} raises identically here. The stub mirrors the production
 * path shape exactly, including the sibling {@code GET} mapping that makes the path itself exist.
 *
 * <p>Slice-shaped per Anti-Bug §M1: {@code standaloneSetup} registers the same
 * {@code ExceptionHandlerExceptionResolver} &rarr; {@code ResponseStatusExceptionResolver} &rarr;
 * {@code DefaultHandlerExceptionResolver} chain in the same order a full context does, so a whole
 * {@code @SpringBootTest} (Tomcat + Postgres) would buy nothing.
 */
@DisplayName("GlobalExceptionHandler — resolver ordering (405/406 must not fall through to 500)")
class GlobalExceptionHandlerResolverOrderTest {

    private static final String POST_ONLY_PATH = "/api/v1/book/some-slug/booking";

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> listAppender;
    private Level previousHandlerLevel;

    /**
     * The appender goes on the ROOT logger, not on {@link GlobalExceptionHandler}'s own.
     *
     * <p>Scoping it to the handler's logger is what let the 406 defect ship green: the expensive
     * log line is not written by {@code com.beautica} at all. When an {@code @ExceptionHandler}
     * returns a body that content negotiation cannot write, the framework re-throws and
     * {@code ExceptionHandlerExceptionResolver} logs {@code "Failure in @ExceptionHandler ..."}
     * WITH THE THROWABLE at WARN under {@code org.springframework} — a logger the old appender
     * could not see. Root catches every logger's events by additivity, so the assertion now covers
     * the framework's amplifier as well as ours.
     *
     * <p>Assertions read through {@link #relevantEvents()} rather than the raw list — see that
     * method for why the narrowing happens at read time and why it cannot narrow any further.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StubBookingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        previousHandlerLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);

        listAppender = new ListAppender<>();
        listAppender.start();
        rootLogger().addAppender(listAppender);
    }

    @AfterEach
    void detachListAppender() {
        rootLogger().detachAppender(listAppender);
        listAppender.stop();
        ((Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class))
                .setLevel(previousHandlerLevel);
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }

    /**
     * Read-side filter, deliberately NOT a capture-side one: the appender keeps collecting every
     * logger's events, so a future test in this class can still inspect the unfiltered list.
     *
     * <p>{@code build.gradle.kts} sets {@code forkEvery = 100}, so roughly a hundred test classes
     * share one JVM and cached Spring contexts, Hikari housekeeping and Testcontainers daemon
     * threads outlive the class that created them. Any of those logging WARN-or-above WITH a
     * throwable during these four tests would land in the ROOT appender and trip
     * {@code allMatch(getThrowableProxy() == null)} — a cross-class false red that the old
     * handler-scoped appender made structurally impossible.
     *
     * <p>The two retained prefixes are exactly the loggers this class is asserting about:
     * {@code com.beautica} for {@link GlobalExceptionHandler}'s own DEBUG lines, and
     * {@code org.springframework.web} for the amplifier the throwable assertion exists to catch —
     * {@code ExceptionHandlerExceptionResolver} lives in
     * {@code org.springframework.web.servlet.mvc.method.annotation}. Narrowing further, or
     * dropping the framework prefix, would make the falsification stop failing and leave the test
     * decorative.
     */
    private List<ILoggingEvent> relevantEvents() {
        return listAppender.list.stream()
                .filter(event -> event.getLoggerName().startsWith("com.beautica")
                        || event.getLoggerName().startsWith("org.springframework.web"))
                .toList();
    }

    @Test
    @DisplayName("GET on a POST-only booking path returns 405, not 500")
    void should_return405_when_getOnPostOnlyBookingPath() throws Exception {
        mockMvc.perform(get(POST_ONLY_PATH))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(header().string("Allow", "POST"));
    }

    /**
     * The log side of the same defect, and the reason it was rated a security finding rather than
     * a cosmetic one: the shadowed path wrote a full stack trace at ERROR for a request an
     * unauthenticated caller controls entirely. A wrong verb is client error and belongs at DEBUG.
     */
    @Test
    @DisplayName("a wrong-verb request logs at DEBUG with no stack trace, never at ERROR")
    void should_notLogAtError_when_getOnPostOnlyBookingPath() throws Exception {
        mockMvc.perform(get(POST_ONLY_PATH)).andExpect(status().isMethodNotAllowed());

        assertThat(relevantEvents())
                .as("an anonymous caller looping this path must not be able to mint ERROR-level "
                        + "stack traces — that is log-volume exhaustion on a metered host")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(relevantEvents())
                .anyMatch(event -> event.getLevel() == Level.DEBUG
                        && event.getFormattedMessage().contains("Method not allowed"));
        assertThat(relevantEvents())
                .as("no throwable may be attached at any level — the stack trace is the payload "
                        + "that made this expensive")
                .allMatch(event -> event.getThrowableProxy() == null);
    }

    /**
     * {@code Accept} is caller-controlled on every request, {@code permitAll} ones included, so
     * this reached the same shadowed 500 from the same anonymous surface. The response carries no
     * body, so a 406 discloses nothing about what the endpoint can produce.
     */
    @Test
    @DisplayName("an unsatisfiable Accept header returns 406, not 500")
    void should_return406_when_acceptHeaderIsUnsatisfiable() throws Exception {
        // No body assertion, deliberately: the caller said it accepts only image/jpeg, so the
        // ApiResponse error envelope cannot be written EITHER — the handler returns a null body
        // and the 406 goes out empty. That is correct and unavoidable (you cannot serialise JSON
        // to a client that refuses JSON), and it is what production does too. The STATUS is the
        // whole contract here; asserting a JSON body would only be satisfiable by ignoring the
        // Accept header, which is the bug, not the fix.
        mockMvc.perform(get("/api/v1/book/some-slug/info").accept(MediaType.IMAGE_JPEG))
                .andExpect(status().isNotAcceptable());

        assertThat(relevantEvents())
                .as("Accept is caller-controlled on every request including permitAll ones, so an "
                        + "ERROR-level stack trace here is the same anonymous log amplifier as the "
                        + "405 case")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(relevantEvents())
                .as("the handler must still have run — otherwise the status above could be coming "
                        + "from somewhere other than the code this class exists to guard")
                .anyMatch(event -> event.getLevel() == Level.DEBUG
                        && event.getFormattedMessage().contains("Not acceptable"));
    }

    /**
     * The assertion the FIRST version of this class could not make, and the reason the 406 handler
     * shipped with a live defect behind a green test.
     *
     * <p>Returning a non-null {@code ApiResponse} body from
     * {@code handleMediaTypeNotAcceptable} cannot succeed: content negotiation already failed once
     * — that IS the exception — so it fails again on the handler's own return value,
     * {@code AbstractMessageConverterMethodProcessor} re-throws, and
     * {@code ExceptionHandlerExceptionResolver} logs {@code "Failure in @ExceptionHandler"} WITH
     * THE FULL STACK TRACE at WARN. {@code org.springframework} is WARN-enabled in
     * {@code application.yml} and not overridden in prod, so the anonymous log-flooding vector the
     * handler was added to CLOSE simply moved from {@code com.beautica} ERROR to Spring WARN.
     * Status-only assertions cannot see that: the response is a 406 either way.
     *
     * <p>Falsification: revert the handler to {@code .body(ApiResponse.error(...))} and this test
     * must go red on the throwable assertion. It does.
     */
    @Test
    @DisplayName("an unsatisfiable Accept header logs no stack trace anywhere, at any level")
    void should_logNoStackTraceAnywhere_when_acceptHeaderIsUnsatisfiable() throws Exception {
        mockMvc.perform(get("/api/v1/book/some-slug/info").accept(MediaType.IMAGE_JPEG))
                .andExpect(status().isNotAcceptable());

        assertThat(relevantEvents())
                .as("no throwable may be attached on ANY logger — a WARN stack trace from "
                        + "ExceptionHandlerExceptionResolver is the same unbounded amplifier as an "
                        + "ERROR one from GlobalExceptionHandler, just under a different name")
                .allMatch(event -> event.getThrowableProxy() == null);
        assertThat(relevantEvents())
                .as("the framework must not have had to rescue this handler at all")
                .noneMatch(event -> event.getFormattedMessage().contains("Failure in @Exception"));
    }

    /**
     * Stub mirroring {@code PublicBookingController}'s mapping shape: {@code /api/v1/book} with a
     * POST-only {@code /{slug}/booking} beside a GET {@code /{slug}/info}. The sibling GET is what
     * makes the wrong-verb case reachable — without a mapped path there is nothing to reject the
     * verb ON.
     */
    @RestController
    @RequestMapping("/api/v1/book")
    static class StubBookingController {

        @PostMapping("/{slug}/booking")
        String createBooking(@PathVariable String slug) {
            return slug;
        }

        /**
         * Returns a POJO, not a String. {@code StringHttpMessageConverter} advertises the
         * wildcard media type and would happily write a String as {@code image/jpeg}, which makes
         * the 406 case unreachable and this test vacuously green. Only Jackson can write a Map,
         * and Jackson is JSON-only.
         */
        @GetMapping("/{slug}/info")
        Map<String, String> info(@PathVariable String slug) {
            return Map.of("slug", slug);
        }
    }
}
