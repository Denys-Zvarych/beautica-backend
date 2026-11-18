package com.beautica.user.security;

import com.beautica.auth.LoginFailureLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CurrentPasswordCheck — the AUTH-D19 password gate on a phone / email change (B516)")
class CurrentPasswordCheckTest {

    private static final String PASSWORD = "test-password";
    private static final String WRONG = "Wr0ng!Passw0rd#";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final String HASH = ENCODER.encode(PASSWORD);
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final CurrentPasswordCheck check = new CurrentPasswordCheck(ENCODER, new LoginFailureLimiter(clock));

    private void failTimes(UUID userId, int times) {
        for (int i = 0; i < times; i++) {
            check.verify(userId, HASH, WRONG);
        }
    }

    @Test
    @DisplayName("the account's own password matches")
    void should_match_when_passwordIsCurrent() {
        assertThat(check.verify(USER_ID, HASH, PASSWORD)).isTrue();
    }

    @Test
    @DisplayName("a different password does not match")
    void should_notMatch_when_passwordWrong() {
        assertThat(check.verify(USER_ID, HASH, WRONG)).isFalse();
    }

    @Test
    @DisplayName("a missing password never matches")
    void should_notMatch_when_passwordNull() {
        assertThat(check.verify(USER_ID, HASH, null)).isFalse();
    }

    @Test
    @DisplayName("an empty password never matches")
    void should_notMatch_when_passwordEmpty() {
        assertThat(check.verify(USER_ID, ENCODER.encode(""), "")).as("even if the stored hash is of \"\"").isFalse();
    }

    @Test
    @DisplayName("an account without a password hash (social-only, B506) never matches")
    void should_notMatch_when_accountHasNoPasswordHash() {
        assertThat(check.verify(USER_ID, null, PASSWORD)).isFalse();
    }

    @Test
    @DisplayName("after 10 wrong passwords the account is locked: even the correct password is refused")
    void should_refuseCorrectPassword_when_accountLockedAfterMaxFailures() {
        failTimes(USER_ID, LoginFailureLimiter.MAX_FAILURES);

        boolean result = check.verify(USER_ID, HASH, PASSWORD);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("9 wrong passwords do not lock the account yet")
    void should_acceptCorrectPassword_when_belowMaxFailures() {
        failTimes(USER_ID, LoginFailureLimiter.MAX_FAILURES - 1);

        boolean result = check.verify(USER_ID, HASH, PASSWORD);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("the correct password clears the count, so earlier failures do not add up with later ones")
    void should_clearFailures_when_correctPasswordAccepted() {
        failTimes(USER_ID, LoginFailureLimiter.MAX_FAILURES - 1);
        check.verify(USER_ID, HASH, PASSWORD);
        failTimes(USER_ID, LoginFailureLimiter.MAX_FAILURES - 1);

        boolean result = check.verify(USER_ID, HASH, PASSWORD);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("the lock lifts 15 minutes after the last failure")
    void should_acceptCorrectPassword_when_lockWindowPassed() {
        failTimes(USER_ID, LoginFailureLimiter.MAX_FAILURES);
        clock.advance(LoginFailureLimiter.LOCK_WINDOW.plusSeconds(1));

        boolean result = check.verify(USER_ID, HASH, PASSWORD);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("the lock is per account: another account is not affected")
    void should_acceptOtherAccount_when_oneAccountLocked() {
        failTimes(USER_ID, LoginFailureLimiter.MAX_FAILURES);

        boolean result = check.verify(UUID.randomUUID(), HASH, PASSWORD);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("concurrent wrong passwords get at most 10 password checks between them")
    void should_capPasswordChecksAtMax_when_manyConcurrentAttempts() throws Exception {
        AtomicInteger encoderCalls = new AtomicInteger();
        PasswordEncoder countingEncoder = new CountingEncoder(encoderCalls);
        CurrentPasswordCheck concurrentCheck =
                new CurrentPasswordCheck(countingEncoder, new LoginFailureLimiter(clock));
        int threads = 40;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return concurrentCheck.verify(USER_ID, HASH, WRONG);
            }));
        }

        start.countDown();
        for (Future<Boolean> result : results) {
            assertThat(result.get(10, TimeUnit.SECONDS)).isFalse();
        }
        pool.shutdown();

        assertThat(encoderCalls.get()).isEqualTo(LoginFailureLimiter.MAX_FAILURES);
    }

    private record CountingEncoder(AtomicInteger calls) implements PasswordEncoder {
        @Override
        public String encode(CharSequence raw) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            calls.incrementAndGet();
            return false;
        }
    }

    /** A test clock the limiter's Caffeine ticker reads. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration by) {
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
}
