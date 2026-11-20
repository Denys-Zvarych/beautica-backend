package com.beautica.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoginFailureLimiter — per identifier + IP lockout (B514, as AUTH-06)")
class LoginFailureLimiterTest {

    private static final String KEY = "+380501234567";
    private static final String IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.9";

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-17T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @Test
    @DisplayName("the tenth failure from one IP locks that identifier for that IP")
    void should_lock_when_maxFailuresReached() {
        LoginFailureLimiter limiter = new LoginFailureLimiter(clock);

        for (int i = 0; i < LoginFailureLimiter.MAX_FAILURES - 1; i++) {
            limiter.recordFailure(KEY, IP);
        }
        boolean lockedBefore = limiter.isLocked(KEY, IP);
        limiter.recordFailure(KEY, IP);

        assertThat(lockedBefore).isFalse();
        assertThat(limiter.isLocked(KEY, IP)).isTrue();
        assertThat(limiter.isLocked("other@example.com", IP)).isFalse();
    }

    @Test
    @DisplayName("the lock lifts once the window has passed since the last failure")
    void should_unlock_when_windowElapsedSinceLastFailure() {
        LoginFailureLimiter limiter = new LoginFailureLimiter(clock);
        for (int i = 0; i < LoginFailureLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure(KEY, IP);
        }

        now.set(now.get().plus(LoginFailureLimiter.LOCK_WINDOW).plusSeconds(1));

        assertThat(limiter.isLocked(KEY, IP)).isFalse();
    }

    @Test
    @DisplayName("a successful login clears the failure count")
    void should_clearCount_when_reset() {
        LoginFailureLimiter limiter = new LoginFailureLimiter(clock);
        for (int i = 0; i < LoginFailureLimiter.MAX_FAILURES - 1; i++) {
            limiter.recordFailure(KEY, IP);
        }

        limiter.reset(KEY, IP);
        limiter.recordFailure(KEY, IP);

        assertThat(limiter.isLocked(KEY, IP)).isFalse();
    }

    @Test
    @DisplayName("failures from one IP never lock the same identifier on another IP")
    void should_notLockOtherIp_when_maxFailuresReachedFromOneIp() {
        LoginFailureLimiter limiter = new LoginFailureLimiter(clock);

        for (int i = 0; i < LoginFailureLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure(KEY, IP);
        }

        assertThat(limiter.isLocked(KEY, IP)).isTrue();
        assertThat(limiter.isLocked(KEY, OTHER_IP)).isFalse();
    }

    @Test
    @DisplayName("a flood of failed logins past the login cache bound never evicts an account's current-password lock")
    void should_keepAccountLock_when_loginCacheOverflowed() {
        int smallBound = 8;
        LoginFailureLimiter limiter = new LoginFailureLimiter(clock, smallBound, smallBound);
        String account = "current-password:33333333-3333-3333-3333-333333333333";
        for (int i = 0; i < LoginFailureLimiter.MAX_FAILURES; i++) {
            limiter.tryAcquireAccountAttempt(account);
        }

        for (int i = 0; i < smallBound * 50; i++) {
            limiter.recordFailure("+38050" + String.format("%07d", i), IP);
        }
        long trackedPairs = limiter.trackedCount();

        assertThat(trackedPairs).isLessThanOrEqualTo(smallBound);
        assertThat(limiter.tryAcquireAccountAttempt(account)).isFalse();
    }

    @Test
    @DisplayName("the correct current password clears the account count")
    void should_clearAccountCount_when_resetAccount() {
        LoginFailureLimiter limiter = new LoginFailureLimiter(clock);
        String account = "current-password:33333333-3333-3333-3333-333333333333";
        for (int i = 0; i < LoginFailureLimiter.MAX_FAILURES; i++) {
            limiter.tryAcquireAccountAttempt(account);
        }

        limiter.resetAccount(account);

        assertThat(limiter.tryAcquireAccountAttempt(account)).isTrue();
    }
}
