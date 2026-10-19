package com.beautica.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SchedulingConfig error handler — Phase 371 audit-fix cycle 1, finding 3")
class SchedulingConfigTest {

    private static final String DETAIL = "Detail: Key (email)=(canary@x.ua) already exists.";

    private final Logger logger = (Logger) LoggerFactory.getLogger(SchedulingConfig.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("scheduled-task error — ERROR with the throwable attached; message text never a log parameter")
    void should_logThrowableNotMessage_when_scheduledTaskFails() {
        SchedulingConfig.logScheduledTaskError(new DataIntegrityViolationException(DETAIL));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage())
                .isEqualTo("Scheduled task error [DataIntegrityViolationException]")
                .doesNotContain("canary@x.ua");
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getClassName())
                .isEqualTo(DataIntegrityViolationException.class.getName());
    }

    @Test
    @DisplayName("Phase 374 — scheduler grace exceeds one prod outbox entry (Brevo API email + FCM push, from the production yml)")
    void should_coverOneProdOutboxEntry_when_schedulerGraceCompared() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withUserConfiguration(com.beautica.notification.mail.MailTransportConfig.class)
                .run(ctx -> {
                    var brevo = ctx.getBean(com.beautica.notification.mail.BrevoApiProperties.class);
                    var binder = org.springframework.boot.context.properties.bind.Binder.get(ctx.getEnvironment());
                    java.time.Duration push = binder.bind("app.firebase.connect-timeout", java.time.Duration.class).get()
                            .plus(binder.bind("app.firebase.read-timeout", java.time.Duration.class).get());
                    long entryWorstCase = brevo.connectTimeout().plus(brevo.readTimeout()).plus(push).toSeconds();

                    assertThat(SchedulingConfig.SCHEDULER_SHUTDOWN_GRACE_SECONDS)
                            .as("a shorter grace cuts an in-flight outbox entry; it is re-sent on next boot (duplicate)")
                            .isGreaterThan((int) entryWorstCase)
                            .isEqualTo(30);
                });
    }
}
