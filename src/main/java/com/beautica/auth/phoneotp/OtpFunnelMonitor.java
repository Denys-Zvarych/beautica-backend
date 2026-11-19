package com.beautica.auth.phoneotp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Set;

/**
 * Phone-first hardening B8 — SMS-code funnel alerts without a metrics backend: every 10 min reads the cumulative
 * {@value PhoneOtpService#SEND_METRIC} / {@value PhoneOtpService#VERIFY_METRIC} counters, keeps one snapshot per run
 * for 24 h, and logs ONE {@code ERROR} per tripped rule (Sentry issue alert, the {@code RecipientInvalidSpikeAlarm} /
 * phase 441 pattern):
 * <ul>
 *   <li>{@code otp-send-errors}: provider errors (incl. ambiguous) above {@value #MAX_ERROR_SHARE_PERCENT} % of the
 *       send attempts of the last hour;</li>
 *   <li>{@code otp-verify-ratio}: successful verifies below {@value #MIN_VERIFY_RATIO_PERCENT} % of delivered sends
 *       over the last hour;</li>
 *   <li>{@code otp-send-spike}: delivered sends of the last hour above {@value #SPIKE_FACTOR}× the hourly mean of
 *       the 23 h before it.</li>
 * </ul>
 * Every rule needs at least {@value #MIN_SENDS} sends in the hour (no alert on a quiet night). In memory, per
 * instance, lost on restart (single Railway instance). Logs carry counts only — never a phone, never a code.
 */
@Component
public class OtpFunnelMonitor {

    private static final Logger log = LoggerFactory.getLogger(OtpFunnelMonitor.class);

    static final int SNAPSHOTS_PER_HOUR = 6;
    static final int SNAPSHOTS_KEPT = 24 * SNAPSHOTS_PER_HOUR + 1;
    static final int MIN_SENDS = 20;
    static final int MAX_ERROR_SHARE_PERCENT = 20;
    static final int MIN_VERIFY_RATIO_PERCENT = 30;
    static final int SPIKE_FACTOR = 3;
    private static final Set<String> SEND_ERROR_RESULTS = Set.of(
            PhoneOtpService.SendResult.PROVIDER_ERROR.tag(), PhoneOtpService.SendResult.AMBIGUOUS.tag());

    /** Cumulative totals at one run. */
    record Snapshot(double sendOk, double sendErrors, double verifyOk) {
    }

    private final MeterRegistry meterRegistry;
    private final Deque<Snapshot> history = new ArrayDeque<>();

    public OtpFunnelMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${app.otp-funnel-monitor.fixed-delay-ms:600000}",
            initialDelayString = "${app.otp-funnel-monitor.fixed-delay-ms:600000}")
    public void evaluate() {
        record(readTotals());
    }

    /** Package-private seam: appends one snapshot and evaluates the rules against the kept history. */
    synchronized void record(Snapshot now) {
        history.addLast(now);
        while (history.size() > SNAPSHOTS_KEPT) {
            history.removeFirst();
        }
        if (history.size() <= SNAPSHOTS_PER_HOUR) {
            return;
        }
        Snapshot hourAgo = nthFromEnd(SNAPSHOTS_PER_HOUR);
        double sends = now.sendOk() - hourAgo.sendOk();
        double errors = now.sendErrors() - hourAgo.sendErrors();
        double verifies = now.verifyOk() - hourAgo.verifyOk();
        if (sends + errors < MIN_SENDS) {
            return;
        }
        checkErrorShare(sends, errors);
        checkVerifyRatio(sends, verifies);
        checkSpike(sends, hourAgo);
    }

    private void checkErrorShare(double sends, double errors) {
        if (errors * 100 > MAX_ERROR_SHARE_PERCENT * (sends + errors)) {
            log.error("OTP funnel alert rule=otp-send-errors lastHourSends={} lastHourErrors={}",
                    (long) sends, (long) errors);
        }
    }

    private void checkVerifyRatio(double sends, double verifies) {
        if (sends >= MIN_SENDS && verifies * 100 < MIN_VERIFY_RATIO_PERCENT * sends) {
            log.error("OTP funnel alert rule=otp-verify-ratio lastHourSends={} lastHourVerified={}",
                    (long) sends, (long) verifies);
        }
    }

    private void checkSpike(double sends, Snapshot hourAgo) {
        Snapshot oldest = history.peekFirst();
        int trailingSnapshots = history.size() - 1 - SNAPSHOTS_PER_HOUR;
        if (oldest == null || trailingSnapshots < SNAPSHOTS_PER_HOUR) {
            return; // less than one trailing hour of history: no baseline yet
        }
        double trailingHours = (double) trailingSnapshots / SNAPSHOTS_PER_HOUR;
        double hourlyMean = (hourAgo.sendOk() - oldest.sendOk()) / trailingHours;
        if (hourlyMean > 0 && sends > SPIKE_FACTOR * hourlyMean) {
            log.error("OTP funnel alert rule=otp-send-spike lastHourSends={} trailingHourlyMean={}",
                    (long) sends, Math.round(hourlyMean));
        }
    }

    private Snapshot nthFromEnd(int n) {
        Iterator<Snapshot> it = history.descendingIterator();
        Snapshot s = null;
        for (int i = 0; i <= n && it.hasNext(); i++) {
            s = it.next();
        }
        return s;
    }

    private Snapshot readTotals() {
        double sendOk = 0;
        double sendErrors = 0;
        for (Counter counter : meterRegistry.find(PhoneOtpService.SEND_METRIC).counters()) {
            String result = counter.getId().getTag("result");
            if (PhoneOtpService.SendResult.OK.tag().equals(result)) {
                sendOk += counter.count();
            } else if (SEND_ERROR_RESULTS.contains(result)) {
                sendErrors += counter.count();
            }
        }
        double verifyOk = meterRegistry.find(PhoneOtpService.VERIFY_METRIC)
                .tag("result", PhoneOtpService.VerifyResult.OK.tag())
                .counters().stream().mapToDouble(Counter::count).sum();
        return new Snapshot(sendOk, sendErrors, verifyOk);
    }
}
