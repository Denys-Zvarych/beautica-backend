package com.beautica.auth.phoneotp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phone-first hardening B8 — the OTP funnel alert rules (one ERROR per tripped rule, counts only). */
@DisplayName("OtpFunnelMonitor — send-error share, verify ratio, send spike (B8)")
class OtpFunnelMonitorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OtpFunnelMonitor monitor = new OtpFunnelMonitor(meterRegistry);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(OtpFunnelMonitor.class);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private List<String> errors() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Feeds {@code steps} 10-min snapshots, each adding the given per-step deltas to the running totals. */
    private void feed(int steps, double sendOkPerStep, double errorsPerStep, double verifyOkPerStep,
                      double[] totals) {
        for (int i = 0; i < steps; i++) {
            totals[0] += sendOkPerStep;
            totals[1] += errorsPerStep;
            totals[2] += verifyOkPerStep;
            monitor.record(new OtpFunnelMonitor.Snapshot(totals[0], totals[1], totals[2]));
        }
    }

    @Test
    @DisplayName("should_stayQuiet_when_healthyFunnel")
    void should_stayQuiet_when_healthyFunnel() {
        double[] totals = new double[3];

        feed(OtpFunnelMonitor.SNAPSHOTS_KEPT, 10, 0, 8, totals);

        assertThat(errors()).isEmpty();
    }

    @Test
    @DisplayName("should_logError_when_providerErrorShareAbove20Percent")
    void should_logError_when_providerErrorShareAbove20Percent() {
        double[] totals = new double[3];

        feed(OtpFunnelMonitor.SNAPSHOTS_PER_HOUR + 1, 6, 3, 5, totals);

        assertThat(errors()).anyMatch(m -> m.contains("rule=otp-send-errors"));
    }

    @Test
    @DisplayName("should_logError_when_verifiesBelow30PercentOfSends")
    void should_logError_when_verifiesBelow30PercentOfSends() {
        double[] totals = new double[3];

        feed(OtpFunnelMonitor.SNAPSHOTS_PER_HOUR + 1, 10, 0, 1, totals);

        assertThat(errors()).anyMatch(m -> m.contains("rule=otp-verify-ratio"));
    }

    @Test
    @DisplayName("should_stayQuiet_when_fewerThan20SendsInHour")
    void should_stayQuiet_when_fewerThan20SendsInHour() {
        double[] totals = new double[3];

        feed(OtpFunnelMonitor.SNAPSHOTS_PER_HOUR + 1, 1, 2, 0, totals); // 18 attempts in the hour

        assertThat(errors()).isEmpty();
    }

    @Test
    @DisplayName("should_logError_when_sendsSpikeAbove3xTrailingMean")
    void should_logError_when_sendsSpikeAbove3xTrailingMean() {
        double[] totals = new double[3];
        feed(OtpFunnelMonitor.SNAPSHOTS_PER_HOUR * 4, 5, 0, 5, totals);
        appender.list.clear();

        feed(OtpFunnelMonitor.SNAPSHOTS_PER_HOUR, 40, 0, 40, totals);

        assertThat(errors()).anyMatch(m -> m.contains("rule=otp-send-spike"));
    }

    @Test
    @DisplayName("should_neverLogPhoneOrCode_when_alerting")
    void should_neverLogPhoneOrCode_when_alerting() {
        double[] totals = new double[3];

        feed(OtpFunnelMonitor.SNAPSHOTS_PER_HOUR + 1, 10, 10, 0, totals);

        assertThat(errors()).isNotEmpty().allMatch(m -> !m.contains("+380"));
    }

    @Test
    @DisplayName("should_readTheOtpCounters_when_evaluating")
    void should_readTheOtpCounters_when_evaluating() {
        meterRegistry.counter(PhoneOtpService.SEND_METRIC, "purpose", "registration", "result", "ok").increment(30);
        meterRegistry.counter(PhoneOtpService.SEND_METRIC, "purpose", "registration", "result", "provider_error")
                .increment(30);

        for (int i = 0; i <= OtpFunnelMonitor.SNAPSHOTS_PER_HOUR; i++) {
            if (i == OtpFunnelMonitor.SNAPSHOTS_PER_HOUR) {
                meterRegistry.counter(PhoneOtpService.SEND_METRIC, "purpose", "registration", "result", "ok")
                        .increment(20);
                meterRegistry.counter(PhoneOtpService.SEND_METRIC, "purpose", "guest_booking", "result",
                        "ambiguous").increment(20);
            }
            monitor.evaluate();
        }

        assertThat(errors()).anyMatch(m -> m.contains("rule=otp-send-errors"));
    }
}
