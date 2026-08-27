package com.beautica.notification.sms;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 22.7 — the disabled-gate implementation.
 *
 * <p>Three properties are load-bearing and each is asserted directly rather than inferred:
 * it never throws (a deliberate state is not a failure), it logs the recipient MASKED, and it
 * never logs the body (which carries a client's appointment details — Anti-Bug §I).
 *
 * <p>The formatted message is read via {@link ILoggingEvent#getFormattedMessage()}, not the raw
 * pattern: asserting on the pattern would pass even if the unmasked phone were interpolated into
 * it, which is precisely the leak this class exists to prevent.
 */
@DisplayName("NoOpSmsService — suppressed sends are logged, never delivered")
class NoOpSmsServiceTest {

    private static final String PHONE = "+380671234567";
    /** Deliberately shaped like a real walk-in body: master, service, date, time. */
    private static final String BODY = "Beautica: Запис підтверджено!\nМарія Левченко, Манікюр\n10.06.2026 о 12:00";

    private NoOpSmsService service;
    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        service = new NoOpSmsService();

        serviceLogger = (Logger) LoggerFactory.getLogger(NoOpSmsService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
        serviceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("a suppressed send returns normally — a deliberate state is not a failure")
    void should_returnNormally_when_sendIsCalled() {
        assertThatCode(() -> service.send(PHONE, BODY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null or blank recipient still returns normally — masking must never NPE")
    void should_returnNormally_when_recipientIsNullOrBlank() {
        // Callers wrap this in a warn-and-continue catch, so a masking NPE would be swallowed and
        // the suppression would go unrecorded. Defensive input handling is asserted, not assumed.
        assertThatCode(() -> service.send(null, BODY)).doesNotThrowAnyException();
        assertThatCode(() -> service.send("  ", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one send produces exactly one INFO audit line, never a warn storm")
    void should_logExactlyOneInfoLine_when_sendIsCalled() {
        service.send(PHONE, BODY);

        assertThat(logAppender.list)
                .as("one send, one audit line — no per-send warn storm for a deliberate state")
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
    }

    @Test
    @DisplayName("the audit line names app.booking.sms.enabled so an operator can find the switch")
    void should_nameTheFlagThatCausedSuppression_when_logging() {
        service.send(PHONE, BODY);

        // An operator reading the log must be able to reach the switch without reading the code.
        // hasSize(1) BEFORE allSatisfy, deliberately: allSatisfy on an EMPTY list passes, so a
        // regression that deleted the log line entirely would leave this test — and the two below —
        // green while the suppression became invisible to operators (a test that cannot fail is
        // worse than no test).
        assertThat(formattedMessages()).hasSize(1).allSatisfy(line ->
                assertThat(line).contains("app.booking.sms.enabled=false"));
    }

    @Test
    @DisplayName("the recipient is logged only through PhoneMask, never in full")
    void should_logTheRecipientMasked_when_sending() {
        service.send(PHONE, BODY);

        assertThat(formattedMessages()).hasSize(1).allSatisfy(line -> {
            assertThat(line).contains("+380***4567");
            assertThat(line).as("the full number is PII and must never reach a log").doesNotContain(PHONE);
            assertThat(line).as("the subscriber digits must not leak").doesNotContain("671234");
        });
    }

    @Test
    @DisplayName("the message body never reaches the log, not even in fragments")
    void should_neverLogTheMessageBody_when_sending() {
        service.send(PHONE, BODY);

        assertThat(formattedMessages()).hasSize(1).allSatisfy(line -> {
            assertThat(line).doesNotContain(BODY);
            // Assert on the body's PARTS too: a truncated or reflowed body would still be a leak,
            // and a whole-string doesNotContain would not catch it.
            assertThat(line).doesNotContain("Марія Левченко");
            assertThat(line).doesNotContain("Манікюр");
            assertThat(line).doesNotContain("10.06.2026");
        });
    }

    private List<String> formattedMessages() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
