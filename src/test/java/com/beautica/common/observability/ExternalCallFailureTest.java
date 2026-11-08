package com.beautica.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExternalCallFailure — phase 441 stable ERROR line for failed outbound calls")
class ExternalCallFailureTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("phase441.external-call-failure-test");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("logs ONE ERROR with the constant template and every field, '-' for the absent ones")
    void should_logErrorWithStableTemplate_when_logged() {
        ExternalCallFailure.of("smsclub", "send").http(400).reason("SENDER_INVALID").to("+380***4567")
                .detail("Sender name is incorrect").log(logger);

        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getMessage()).isEqualTo(ExternalCallFailure.MESSAGE);
            assertThat(e.getFormattedMessage()).isEqualTo("External call failed service=smsclub op=send http=400 "
                    + "reason=sender_invalid to=+380***4567 detail=Sender name is incorrect");
        });
    }

    @Test
    @DisplayName("absent fields render as '-' and a cause is attached as the throwable")
    void should_renderDashesAndAttachCause_when_fieldsAbsent() {
        RuntimeException cause = new IllegalStateException("boom");

        ExternalCallFailure.of("r2", "upload").log(logger, cause);

        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage())
                    .isEqualTo("External call failed service=r2 op=upload http=- reason=- to=- detail=-");
            assertThat(e.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        });
    }

    @Test
    @DisplayName("detail is redacted (phone, e-mail), flattened to one line and capped at 200 chars")
    void should_redactFlattenAndCapDetail_when_providerTextIsUnsafe() {
        String raw = "rejected +380671234567 owner@example.com\nline2 " + "x".repeat(400);

        String detail = ExternalCallFailure.sanitizeDetail(raw);

        assertThat(detail).doesNotContain("380671234567").doesNotContain("owner@example.com").doesNotContain("\n")
                .hasSize(ExternalCallFailure.MAX_DETAIL_CHARS).startsWith("rejected [phone] [email] line2");
    }

    @Test
    @DisplayName("transport failures map to stable reason tokens")
    void should_mapTransportReasons_when_causeChainVaries() {
        assertThat(ExternalCallFailure.transportReason(
                new ResourceAccessException("io", new HttpTimeoutException("t")))).isEqualTo("timeout");
        assertThat(ExternalCallFailure.transportReason(new CancellationException())).isEqualTo("timeout");
        assertThat(ExternalCallFailure.transportReason(
                new ResourceAccessException("io", new ConnectException("c")))).isEqualTo("connect");
        assertThat(ExternalCallFailure.transportReason(
                new ResourceAccessException("io", new UnknownHostException("h")))).isEqualTo("dns");
        assertThat(ExternalCallFailure.transportReason(
                new ResourceAccessException("io", new java.io.IOException("eof")))).isEqualTo("io");
        assertThat(ExternalCallFailure.transportReason(new IllegalStateException())).isEqualTo("unexpected");
    }
}
