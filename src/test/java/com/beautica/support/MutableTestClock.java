package com.beautica.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A UTC {@link Clock} a test moves by hand ({@link #set}, {@link #advance}) — for expiry paths that a fixed clock
 * cannot reach in one context. {@code volatile}: concurrency ITs read it from worker threads.
 *
 * <p>Shared form of the private copies in {@code TelegramBotApiClientTest} / {@code CloudflareCachePurgerTest}
 * (not yet rewired onto this one).
 */
public final class MutableTestClock extends Clock {

    private volatile Instant now;

    public MutableTestClock(Instant now) {
        this.now = Objects.requireNonNull(now, "now");
    }

    public void set(Instant instant) {
        now = Objects.requireNonNull(instant, "instant");
    }

    public void advance(Duration by) {
        now = now.plus(by);
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
