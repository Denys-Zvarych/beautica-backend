package com.beautica.config.appcheck;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AppCheckLogOnlyAlarm — hourly ERROR while prod runs App Check in LOG_ONLY (B514, AUTH-54)")
class AppCheckLogOnlyAlarmTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(AppCheckLogOnlyAlarm.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private static AppCheckLogOnlyAlarm alarm(AppCheckMode mode) {
        AppCheckProperties properties = new AppCheckProperties();
        properties.setMode(mode);
        return new AppCheckLogOnlyAlarm(properties);
    }

    @Test
    @DisplayName("each tick in LOG_ONLY logs the alert at ERROR, so Sentry raises it")
    void should_logError_when_modeIsLogOnly() {
        AppCheckLogOnlyAlarm alarm = alarm(AppCheckMode.LOG_ONLY);

        alarm.alertWhileLogOnly();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo(AppCheckLogOnlyAlarm.LOG_ONLY_ALERT);
        });
    }

    @Test
    @DisplayName("a tick in ENFORCE logs nothing")
    void should_logNothing_when_modeIsEnforce() {
        AppCheckLogOnlyAlarm alarm = alarm(AppCheckMode.ENFORCE);

        alarm.alertWhileLogOnly();

        assertThat(appender.list).isEmpty();
    }
}
