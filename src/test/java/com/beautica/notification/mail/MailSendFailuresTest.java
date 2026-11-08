package com.beautica.notification.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 441 — {@link MailSendFailures} is the single point where a {@code JavaMailSender#send} failure is logged: SMTP
 * → ONE {@code service=brevo-smtp} ERROR (no exception attached, no address); brevo-api → WARN only, because
 * {@link BrevoApiMailSender} already logged the ERROR at the call.
 */
@DisplayName("MailSendFailures — one ERROR per failed SMTP send, WARN when brevo-api already logged")
class MailSendFailuresTest {

    private static final String CONTEXT = "admin-notification";
    private static final String RECIPIENT = "owner@salon.example";

    /** Own logger name: the test-intent extension announces each test on the test class's logger. */
    private final Logger logger = (Logger) LoggerFactory.getLogger(MailSendFailuresTest.class.getName() + ".caller");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private List<ILoggingEvent> errors() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    @Test
    @DisplayName("SMTP MailSendException listing a recipient — one ERROR, reason=rejected, class as detail, no address, no throwable")
    void should_logOneBrevoSmtpErrorWithoutAddress_when_smtpSendIsRejected() {
        MailSendException failure = new MailSendException("550 rejected recipient " + RECIPIENT);

        MailSendFailures.log(logger, CONTEXT, failure);

        assertThat(errors()).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).isEqualTo("External call failed service=brevo-smtp op=send http=- "
                    + "reason=rejected to=- detail=" + CONTEXT + " MailSendException");
            assertThat(e.getThrowableProxy()).as("the SMTP message can carry addresses — never attached").isNull();
        });
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(RECIPIENT));
    }

    @Test
    @DisplayName("SMTP MailAuthenticationException — one ERROR with reason=auth")
    void should_logAuthReason_when_smtpAuthenticationFails() {
        MailSendFailures.log(logger, CONTEXT, new MailAuthenticationException("535 bad login"));

        assertThat(errors()).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("External call failed service=brevo-smtp op=send http=- reason=auth to=- detail="
                        + CONTEXT + " MailAuthenticationException");
    }

    @Test
    @DisplayName("SMTP send timing out — one ERROR with reason=timeout, the root cause class as detail")
    void should_logTimeoutReason_when_smtpSendTimesOut() {
        MailSendException failure = new MailSendException("send failed", new SocketTimeoutException("read timed out"));

        MailSendFailures.log(logger, CONTEXT, failure);

        assertThat(errors()).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("External call failed service=brevo-smtp op=send http=- reason=timeout to=- detail="
                        + CONTEXT + " SocketTimeoutException");
    }

    @Test
    @DisplayName("brevo-api permanent failure — WARN only, no second ERROR (BrevoApiMailSender already logged it)")
    void should_logWarnOnly_when_brevoApiAlreadyLoggedTheFailure() {
        MailSendFailures.log(logger, CONTEXT, new BrevoPermanentMailException("invalid_parameter"));

        assertThat(errors()).isEmpty();
        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("reported by the brevo-api transport");
        });
    }

    @Test
    @DisplayName("brevo-api transient failure — WARN only, no second ERROR")
    void should_logWarnOnly_when_brevoApiTransientFailure() {
        MailSendFailures.log(logger, CONTEXT, new BrevoTransientMailException("http_503"));

        assertThat(errors()).isEmpty();
        assertThat(appender.list).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
    }
}
