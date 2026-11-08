package com.beautica.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.sentry.SentryEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 441 audit-fix — through the real logback {@code SentryAppender}: every external failure still writes its ERROR
 * line, while Sentry sees at most 5 events per (service, reason) per window, each fingerprinted to its own issue.
 */
@DisplayName("External-call failures — all ERROR lines kept, Sentry events grouped and capped")
class ExternalCallSentryVolumeTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> logLines;
    private SentryEventCapture sentry;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("phase441.external-call-volume");
        logger.setLevel(Level.INFO);
        logLines = new ListAppender<>();
        logLines.start();
        logger.addAppender(logLines);
        sentry = SentryEventCapture.attach(logger);
    }

    @AfterEach
    void tearDown() {
        sentry.close();
        logger.detachAppender(logLines);
    }

    private long errorLines() {
        return logLines.list.stream().filter(e -> e.getLevel() == Level.ERROR).count();
    }

    @Test
    @DisplayName("20 identical (fcm, reason) failures — 20 ERROR lines, at most 5 Sentry events")
    void should_capSentryEventsButKeepEveryErrorLine_when_twentyIdenticalFcmFailures() {
        for (int i = 0; i < 20; i++) {
            ExternalCallFailure.of("fcm", "send").reason("UNAVAILABLE").to("device:" + i).log(logger);
        }

        assertThat(errorLines()).isEqualTo(20);
        assertThat(sentry.forwarded()).hasSizeLessThanOrEqualTo(5).isNotEmpty()
                .allSatisfy(e -> assertThat(e.getFingerprints()).containsExactly("external-call", "fcm", "unavailable"));
    }

    @Test
    @DisplayName("visitor-driven smsclub PHONE_INVALID flood — every ERROR line kept, at most 5 Sentry events")
    void should_capSentryEvents_when_manyPhoneInvalidRejections() {
        for (int i = 0; i < 200; i++) {
            ExternalCallFailure.of("smsclub", "send").http(200).reason("PHONE_INVALID").to("+380***" + i)
                    .detail("incorrect phone number").log(logger);
        }

        assertThat(errorLines()).isEqualTo(200);
        assertThat(sentry.forwarded()).hasSizeLessThanOrEqualTo(5).isNotEmpty();
    }

    @Test
    @DisplayName("distinct (service, reason) pairs each reach Sentry under their own fingerprint")
    void should_openOneIssuePerServiceAndReason_when_failuresDiffer() {
        ExternalCallFailure.of("smsclub", "send").reason("auth").log(logger);
        ExternalCallFailure.of("smsclub", "send").reason("phone_invalid").log(logger);
        ExternalCallFailure.of("brevo-api", "send").reason("auth").log(logger, new IllegalStateException("boom"));

        List<List<String>> fingerprints = sentry.forwarded().stream().map(SentryEvent::getFingerprints).toList();

        assertThat(fingerprints).containsExactlyInAnyOrder(
                List.of("external-call", "smsclub", "auth"),
                List.of("external-call", "smsclub", "phone_invalid"),
                List.of("external-call", "brevo-api", "auth"));
        assertThat(MDC.get(ExternalCallFailure.TAG_SERVICE)).isNull();
        assertThat(MDC.get(ExternalCallFailure.TAG_REASON)).isNull();
    }

    @Test
    @DisplayName("an ordinary ERROR line is not budgeted and gets no external-call fingerprint")
    void should_forwardEveryOrdinaryError_when_notAnExternalCall() {
        for (int i = 0; i < 10; i++) {
            logger.error("Unrelated failure {}", i);
        }

        assertThat(sentry.forwarded()).hasSize(10)
                .allSatisfy(e -> assertThat(e.getFingerprints()).isNullOrEmpty());
    }
}
